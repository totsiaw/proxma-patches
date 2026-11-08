package app.proxma.rox;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Runs the daily check-in without opening ROX. A periodic job, set up the first time the app is
 * opened, wakes the app every few hours; if today's check-in is not done yet it starts the app's
 * Flutter code off-screen and presses Check-in there (see AutoCheckin). Once today is done, the
 * remaining runs that day return straight away. The job survives reboots; force-stopping ROX or
 * swiping it away on some phones cancels it until ROX is opened again.
 */
public final class CheckinJobService extends JobService {

    private static final String TAG = "PROXMA_ROX";
    private static final int JOB_ID = 0x52_4f_58_01;
    private static final long PERIOD_MS = 3L * 60L * 60L * 1000L;
    private static final long FLEX_MS = 60L * 60L * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    public static void onActivityCreate(Activity activity) {
        try {
            if (activity == null) return;
            schedule(activity.getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, "BGJOB_ENTRY_ERR " + t);
        }
    }

    private static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            Log.w(TAG, "BGJOB no job scheduler");
            return;
        }
        // Rescheduling on every app open would restart the period each time, so keep a matching job.
        JobInfo pending = scheduler.getPendingJob(JOB_ID);
        if (pending != null && pending.getIntervalMillis() == PERIOD_MS && pending.isPersisted()) {
            Log.i(TAG, "BGJOB already scheduled");
            return;
        }
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(context, CheckinJobService.class))
            .setPeriodic(PERIOD_MS, FLEX_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .build();
        int result = scheduler.schedule(info);
        Log.i(TAG, result == JobScheduler.RESULT_SUCCESS ? "BGJOB scheduled every 3h" : "BGJOB schedule failed " + result);
    }

    @Override public boolean onStartJob(final JobParameters params) {
        Log.i(TAG, "BGJOB start");
        try {
            boolean started = AutoCheckin.startBackground(this, new Runnable() {
                @Override public void run() {
                    Log.i(TAG, "BGJOB finished");
                    try {
                        jobFinished(params, false);
                    } catch (Throwable t) {
                        Log.w(TAG, "BGJOB_FINISH_ERR " + t);
                    }
                }
            });
            if (!started) Log.i(TAG, "BGJOB nothing to do");
            return started;
        } catch (Throwable t) {
            Log.w(TAG, "BGJOB_START_ERR " + t);
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
        return false;
    }
}
