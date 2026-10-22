package app.proxma.simosa;

import android.content.Context;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public final class DailyCheckinWorker extends Worker {

    private static final String TAG = "PROXMA_CLAIM";
    private static final String WORK_IMMEDIATE = "proxma_simosa_now";
    private static final String WORK_MIDNIGHT = "proxma_simosa_midnight";

    public DailyCheckinWorker(Context context, WorkerParameters parameters) {
        super(context, parameters);
    }

    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        try {
            BackgroundCheckin.doCheckIn(context);
            WorkerNotify.notifyStatus(context, claimStatus(context), "next run ~00:05 PKT",
                "Simosa daily check-in");
        } catch (Throwable t) {
            Log.w(TAG, "WORKER_ERR " + t);
        }
        // Chain: REPLACE so the next midnight run is always fresh after this one completes
        scheduleMidnight(context, ExistingWorkPolicy.REPLACE);
        return Result.success();
    }

    /** Called from the patched {@code MainActivity.onCreate}; safe to call on every launch. */
    public static void schedule(Context context) {
        if (context == null) {
            return;
        }
        try {
            WorkManager wm = WorkManager.getInstance(context);

            // Cancel stale work names from older patch versions
            wm.cancelUniqueWork("proxma_simosa_checkin");
            wm.cancelUniqueWork("proxma_simosa_checkin_now");

            Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

            // Immediate one-shot for first launch (KEEP = won't re-run if already pending/running)
            OneTimeWorkRequest immediate = new OneTimeWorkRequest.Builder(DailyCheckinWorker.class)
                .setConstraints(constraints)
                .build();
            wm.enqueueUniqueWork(WORK_IMMEDIATE, ExistingWorkPolicy.KEEP, immediate);

            // Schedule next midnight run (KEEP = don't cancel a pending midnight if user opens app at 00:04)
            scheduleMidnight(context, ExistingWorkPolicy.KEEP);

            Log.i(TAG, "WORKER_SCHEDULED");
        } catch (Throwable t) {
            Log.w(TAG, "WORKER_SCHEDULE_ERR " + t);
        }
    }

    private static void scheduleMidnight(Context context, ExistingWorkPolicy policy) {
        try {
            long delayMs = msUntilNextMidnight();
            Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
            OneTimeWorkRequest midnight = new OneTimeWorkRequest.Builder(DailyCheckinWorker.class)
                .setConstraints(constraints)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build();
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_MIDNIGHT, policy, midnight);
            Log.i(TAG, "WORKER_MIDNIGHT delay=" + (delayMs / 60000) + "min policy=" + policy);
        } catch (Throwable t) {
            Log.w(TAG, "WORKER_MIDNIGHT_ERR " + t);
        }
    }

    /** Milliseconds until next 00:05 Asia/Karachi. */
    private static long msUntilNextMidnight() {
        Calendar target = Calendar.getInstance(TimeZone.getTimeZone("Asia/Karachi"));
        target.add(Calendar.DAY_OF_YEAR, 1);
        target.set(Calendar.HOUR_OF_DAY, 0);
        target.set(Calendar.MINUTE, 5);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        long delay = target.getTimeInMillis() - System.currentTimeMillis();
        // Safety: if somehow we computed a negative or tiny delay, push to next day
        if (delay < 60000) {
            delay += 86400000L;
        }
        return delay;
    }

    private static String claimStatus(Context context) {
        String why = AutoClaim.check(context, "ladder");
        if (why == null) {
            return "not claimed yet";
        }
        return why.contains("latched") ? "claimed today" : "skipped (" + why + ")";
    }
}
