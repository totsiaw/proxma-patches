package app.proxma.mytelenor;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Platform JobScheduler instead of WorkManager: MyTelenor's R8 build renames the
 * androidx.work API (WorkManager, OneTimeWorkRequest, Constraints, ...), so any
 * extension class naming it fails with NoClassDefFoundError.
 */
public final class ClaimJobService extends JobService {

    private static final String TAG = "PROXMA_MT";
    private static final String PREFS = "proxma_mt_job";
    private static final String KEY_ATTEMPTS = "attempts";
    private static final String KEY_LAST_STATUS = "last_status";

    private static final int JOB_NOW = 0x50524F01;
    private static final int JOB_MIDNIGHT = 0x50524F02;
    private static final int JOB_PERIODIC = 0x50524F03;

    private static final int MAX_ATTEMPTS = 6;
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;

    public static void schedule(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (js.getPendingJob(JOB_NOW) == null) {
            submit(js, builder(context, JOB_NOW).setMinimumLatency(30_000L));
        }
        if (js.getPendingJob(JOB_MIDNIGHT) == null) {
            scheduleMidnight(context, js);
        }
        if (js.getPendingJob(JOB_PERIODIC) == null) {
            submit(js, builder(context, JOB_PERIODIC).setPeriodic(6 * HOUR, HOUR));
        }
        Log.i(TAG, "JOBS_SCHEDULED");
    }

    private static void scheduleMidnight(Context context, JobScheduler js) {
        long delay = msUntilNextMidnight();
        submit(js, builder(context, JOB_MIDNIGHT).setMinimumLatency(delay));
        Log.i(TAG, "JOB_MIDNIGHT delay=" + (delay / MINUTE) + "min");
    }

    private static JobInfo.Builder builder(Context context, int id) {
        return new JobInfo.Builder(id, new ComponentName(context, ClaimJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(10 * MINUTE, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
    }

    private static void submit(JobScheduler js, JobInfo.Builder b) {
        try {
            js.schedule(b.setPersisted(true).build());
        } catch (Throwable persistedRejected) {
            try {
                js.schedule(b.setPersisted(false).build());
            } catch (Throwable t) {
                Log.w(TAG, "JOB_SCHEDULE_ERR " + t);
            }
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context context = getApplicationContext();
        new Thread(() -> {
            boolean reschedule = false;
            try {
                reschedule = runClaims(context, params.getJobId());
            } catch (Throwable t) {
                Log.w(TAG, "JOB_ERR " + t);
            } finally {
                if (params.getJobId() == JOB_MIDNIGHT && !reschedule) {
                    JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
                    if (js != null) scheduleMidnight(context, js);
                }
                jobFinished(params, reschedule);
            }
        }, "proxma-mt-claim").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    /** Returns true when the job should be retried with backoff. */
    private static synchronized boolean runClaims(Context context, int jobId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int attempt = prefs.getInt(KEY_ATTEMPTS, 0);
        Log.i(TAG, "JOB_START id=" + Integer.toHexString(jobId) + " attempt=" + attempt);

        boolean streakOk = false;
        try {
            streakOk = StreakClaim.tryClaimStreak(context);
        } catch (Throwable t) {
            Log.w(TAG, "STREAK_ERR " + t);
        }
        boolean quizOk = false;
        try {
            quizOk = QuizClaim.tryClaimQuiz(context);
        } catch (Throwable t) {
            Log.w(TAG, "QUIZ_ERR " + t);
        }

        if ((!streakOk || !quizOk) && attempt + 1 < MAX_ATTEMPTS) {
            prefs.edit().putInt(KEY_ATTEMPTS, attempt + 1).apply();
            Log.i(TAG, "JOB_RETRY attempt=" + attempt + " streak=" + streakOk + " quiz=" + quizOk);
            return true;
        }
        prefs.edit().putInt(KEY_ATTEMPTS, 0).apply();

        String streak = StreakClaim.getResult();
        String quiz = QuizClaim.getResult();
        String status = streak + "|" + quiz;
        if (!status.equals(prefs.getString(KEY_LAST_STATUS, null))) {
            WorkerNotify.notifyStatus(context, streak, quiz, "next ~00:05 PKT");
            prefs.edit().putString(KEY_LAST_STATUS, status).apply();
        }
        Log.i(TAG, "JOB_DONE streak=" + streak + " quiz=" + quiz);
        return false;
    }

    private static long msUntilNextMidnight() {
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Karachi"));
        Calendar target = (Calendar) now.clone();
        target.add(Calendar.DAY_OF_YEAR, 1);
        target.set(Calendar.HOUR_OF_DAY, 0);
        target.set(Calendar.MINUTE, 5);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        return Math.max(MINUTE, target.getTimeInMillis() - now.getTimeInMillis());
    }
}
