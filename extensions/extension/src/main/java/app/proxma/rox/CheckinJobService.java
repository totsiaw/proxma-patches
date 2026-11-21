package app.proxma.rox;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Runs the daily check-in in the background. A one-off job is planned for shortly after the ROX
 * day starts (midnight, Pakistan time), a few minutes after the wake-up alarm (CheckinAlarm) set
 * for the same run, so the alarm starts the unlock service first; when the job runs, today's
 * check-in is not done yet and the
 * phone is unlocked, it opens ROX's screen see-through and presses Check-in there (WakeScreen,
 * AutoCheckin). With the phone locked it opens nothing and looks again two hours later. After a
 * miss it tries again two hours later, up to three times a day, then plans tomorrow. Once today is
 * done, from the background or from an app open, the next job is tomorrow, so ROX is woken at
 * most a few times a day instead of every few hours. The job survives reboots; force-stopping ROX
 * cancels it until ROX is opened again.
 */
public final class CheckinJobService extends JobService {

    private static final String TAG = "PROXMA_ROX";
    private static final int JOB_ID = 0x52_4f_58_01;
    // A second id for the run the wake-up alarm asks for, so it never replaces the planned job.
    private static final int NOW_JOB_ID = 0x52_4f_58_02;
    static final String KEY_PLANNED_AT = "bg_planned_at";
    private static final long MINUTE = 60L * 1000L;
    private static final long FIRST_DELAY_MS = 20L * MINUTE;
    private static final long RETRY_DELAY_MS = 120L * MINUTE;
    // The next day's run, and its alarm, at 00:01 Pakistan time with no spread: the unlock service
    // it starts must be up before the first unlock of the day, however early that is.
    private static final long DAY_START_OFFSET_MS = MINUTE;
    // The job runs this long after the run's alarm. Both at 00:01 let the job win on a locked
    // phone, whose retry then moved the alarm, and with it the unlock service, two hours later.
    private static final long JOB_AFTER_ALARM_MS = 5L * MINUTE;

    private static Boolean sDeclared;

    private final Handler handler = new Handler(Looper.getMainLooper());

    public static void onActivityCreate(Activity activity) {
        try {
            if (activity == null) return;
            ensurePlanned(activity.getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, "BGJOB_ENTRY_ERR " + t);
        }
    }

    /** True when the background patch added this service to the manifest. */
    static boolean declared(Context context) {
        if (sDeclared != null) return sDeclared;
        try {
            context.getPackageManager().getServiceInfo(
                new ComponentName(context, CheckinJobService.class), 0);
            sDeclared = true;
        } catch (PackageManager.NameNotFoundException e) {
            sDeclared = false;
        } catch (Throwable t) {
            return false;
        }
        return sDeclared;
    }

    /** On app open: keep a planned one-off job, replace the old every-3-hours job or plan one. */
    private static void ensurePlanned(Context context) {
        JobScheduler scheduler = scheduler(context);
        if (scheduler == null) return;
        JobInfo pending = scheduler.getPendingJob(JOB_ID);
        if (pending != null && !pending.isPeriodic()) {
            long at = AutoCheckin.prefs(context).getLong(KEY_PLANNED_AT, 0L);
            Log.i(TAG, "BGJOB already planned for " + AutoCheckin.clock(at));
            // Builds before the wake-up alarm planned only the job; add the alarm for the same time.
            // A pending alarm that is earlier is kept: it is never moved later.
            long alarm = CheckinAlarm.pendingAt(context);
            if (at > System.currentTimeMillis() && (alarm == 0L || alarm > at)) {
                CheckinAlarm.set(context, at);
            }
            return;
        }
        if (pending != null) Log.i(TAG, "BGJOB replacing the old repeating job");
        planNext(context);
    }

    /** Plans the next run from today's state: tomorrow when done, else soon or after a retry gap. */
    static void planNext(Context context) {
        try {
            if (!declared(context)) return;
            long delay;
            String why;
            boolean keepEarlierAlarm = false;
            if (AutoCheckin.doneToday(context)) {
                delay = untilTomorrow();
                why = "done today";
            } else if (AutoCheckin.signedOutToday(context)) {
                delay = untilTomorrow();
                why = "signed out";
            } else if (UnlockService.active()) {
                // It checks in at the first unlock and stops; the next look is the next day start.
                delay = untilTomorrow();
                why = "the unlock service waits for today's first unlock";
            } else if (UnlockService.ranToday(context)) {
                // One try a day at the first unlock; its result is already in a notice.
                delay = untilTomorrow();
                why = "tried at today's first unlock";
            } else {
                int tries = AutoCheckin.backgroundTriesToday(context);
                if (tries >= AutoCheckin.MAX_BG_TRIES_PER_DAY) {
                    delay = untilTomorrow();
                    why = "tries used up";
                } else if (WakeScreen.lockedOrDark(context)) {
                    // A run that finds the phone locked uses no try and opens nothing; look again
                    // after the retry gap rather than soon, so a locked night costs few wake-ups.
                    delay = Math.min(RETRY_DELAY_MS, untilTomorrow());
                    why = "phone locked, earlier tries " + tries;
                    // A job that ran before today's alarm must not push that alarm, and so the
                    // unlock service it starts, back by the retry gap.
                    keepEarlierAlarm = true;
                } else {
                    delay = tries == 0 ? FIRST_DELAY_MS : RETRY_DELAY_MS;
                    // A retry must not cross into the next day's first run.
                    delay = Math.min(delay, untilTomorrow());
                    why = "not done yet, earlier tries " + tries;
                }
            }
            schedule(context, delay, why, keepEarlierAlarm);
        } catch (Throwable t) {
            Log.w(TAG, "BGJOB_PLAN_ERR " + t);
        }
    }

    private static long untilTomorrow() {
        Calendar next = Calendar.getInstance(TimeZone.getTimeZone("Asia/Karachi"));
        next.add(Calendar.DAY_OF_YEAR, 1);
        next.set(Calendar.HOUR_OF_DAY, 0);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        return Math.max(MINUTE, next.getTimeInMillis() - System.currentTimeMillis() + DAY_START_OFFSET_MS);
    }

    private static JobScheduler scheduler(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) Log.w(TAG, "BGJOB no job scheduler");
        return scheduler;
    }

    /**
     * Plans the next run: the wake-up alarm at now + delay and the job JOB_AFTER_ALARM_MS after it.
     * With keepEarlierAlarm, a wake-up alarm still waiting to fire before that time is left alone
     * (never moved later) and the run is planned for it.
     */
    private static void schedule(Context context, long delayMs, String why, boolean keepEarlierAlarm) {
        JobScheduler scheduler = scheduler(context);
        if (scheduler == null) return;
        long now = System.currentTimeMillis();
        long at = now + delayMs;
        long jobDelay = delayMs + JOB_AFTER_ALARM_MS;
        boolean keep = false;
        if (keepEarlierAlarm) {
            long pending = CheckinAlarm.pendingAt(context);
            if (pending > 0 && pending < at) {
                keep = true;
                why = why + ", keeping the wake-up alarm already set for " + AutoCheckin.clock(pending);
                // An alarm that is due but hasn't fired yet (inexact, held by Doze) keeps the
                // retry gap for the job, so the job doesn't come back every few minutes.
                if (pending > now) jobDelay = (pending - now) + JOB_AFTER_ALARM_MS;
                at = pending;
            }
        }
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(context, CheckinJobService.class))
            .setMinimumLatency(jobDelay)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .build();
        int result = scheduler.schedule(info);
        if (result == JobScheduler.RESULT_SUCCESS) {
            // The run's time is its alarm's; the job follows a few minutes later.
            AutoCheckin.prefs(context).edit().putLong(KEY_PLANNED_AT, at).apply();
            Log.i(TAG, "BGJOB planned for " + AutoCheckin.clock(now + jobDelay) + ", run due "
                + AutoCheckin.clock(at) + " (" + why + ")");
        } else {
            Log.w(TAG, "BGJOB plan failed " + result + " (" + why + ")");
        }
        // Doze holds the job back at night; the alarm fires on time and starts the unlock service.
        // Whichever comes first does the check-in, the other then finds nothing to do.
        if (keep) {
            Log.i(TAG, "ALARM kept for " + AutoCheckin.clock(at));
        } else {
            CheckinAlarm.set(context, at);
        }
    }

    /** From the wake-up alarm: run now as an expedited job, which Doze does not hold back. */
    static void runNow(Context context) {
        JobScheduler scheduler = scheduler(context);
        if (scheduler == null) return;
        ComponentName service = new ComponentName(context, CheckinJobService.class);
        int result = JobScheduler.RESULT_FAILURE;
        try {
            result = scheduler.schedule(new JobInfo.Builder(NOW_JOB_ID, service)
                .setExpedited(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build());
        } catch (Throwable t) {
            Log.w(TAG, "BGJOB expedited not accepted " + t);
        }
        if (result == JobScheduler.RESULT_SUCCESS) {
            Log.i(TAG, "BGJOB expedited run asked for");
            return;
        }
        // Out of expedited quota: a plain job with no delay, which runs at the next chance.
        try {
            result = scheduler.schedule(new JobInfo.Builder(NOW_JOB_ID, service)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build());
            Log.i(TAG, "BGJOB plain run asked for, result " + result);
        } catch (Throwable t) {
            Log.w(TAG, "BGJOB_NOW_ERR " + t);
        }
    }

    @Override public boolean onStartJob(final JobParameters params) {
        Log.i(TAG, "BGJOB start, " + (params.getJobId() == NOW_JOB_ID ? "from the wake-up alarm" : "planned run")
            + (Build.VERSION.SDK_INT >= 31 && params.isExpeditedJob() ? ", expedited" : ""));
        final Context context = getApplicationContext();
        String skip = null;
        if (UnlockService.active()) {
            skip = "the unlock service handles today";
        } else if (!AutoCheckin.doneToday(context) && UnlockService.ranToday(context)) {
            skip = "already tried at today's first unlock";
        }
        if (skip != null) {
            Log.i(TAG, "BGJOB nothing to do, " + skip);
            handler.post(new Runnable() {
                @Override public void run() {
                    planNext(context);
                }
            });
            return false;
        }
        return begin(params, context);
    }

    /** Starts the check-in; with the phone locked or dark nothing is opened (see startBackground). */
    private boolean begin(final JobParameters params, final Context context) {
        try {
            boolean started = AutoCheckin.startBackground(this, new Runnable() {
                @Override public void run() {
                    Log.i(TAG, "BGJOB finished");
                    try {
                        jobFinished(params, false);
                    } catch (Throwable t) {
                        Log.w(TAG, "BGJOB_FINISH_ERR " + t);
                    }
                    // Same job id: plan only after this run is finished, or it would be stopped.
                    planNext(context);
                }
            });
            if (!started) {
                Log.i(TAG, "BGJOB nothing to do");
                handler.post(new Runnable() {
                    @Override public void run() {
                        planNext(context);
                    }
                });
            }
            return started;
        } catch (Throwable t) {
            Log.w(TAG, "BGJOB_START_ERR " + t);
            handler.post(new Runnable() {
                @Override public void run() {
                    planNext(context);
                }
            });
            return false;
        }
    }

    @Override public boolean onStopJob(JobParameters params) {
        Log.i(TAG, "BGJOB stopped by the system");
        handler.post(new Runnable() {
            @Override public void run() {
                AutoCheckin.stopBackground("job stopped by the system");
            }
        });
        // Ending the session plans the next run itself, so the system must not retry this one.
        return false;
    }
}
