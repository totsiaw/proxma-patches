package app.proxma.simosa;

import android.content.Context;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * The daily check-in as a WorkManager job of our own.
 *
 * <p>Nothing in the stock app can be hooked for this: Simosa 3.3.5 ships androidx.work (2.8+, the
 * enum {@code ExistingPeriodicWorkPolicy} has {@code UPDATE}) and initialises it through
 * {@code androidx.startup}, but it has no worker subclass of its own, so the only way to run code
 * off the UI thread on a schedule is to add one.
 *
 * <p>androidx.work is {@code compileOnly} in this module: the app's own copy is what loads at
 * runtime, so the extension dex must not carry a second one.
 *
 * <p>Enqueue is idempotent — {@code ExistingPeriodicWorkPolicy.KEEP} keeps the existing chain, so
 * the hook on every {@code MainActivity.onCreate} cannot reset the schedule.
 */
public final class DailyCheckinWorker extends Worker {

    private static final String TAG = "PROXMA_CLAIM";
    private static final String UNIQUE_WORK = "proxma_simosa_checkin";
    private static final long INTERVAL_HOURS = 24L;

    public DailyCheckinWorker(Context context, WorkerParameters parameters) {
        super(context, parameters);
    }

    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        try {
            BackgroundCheckin.doCheckIn(context);
            WorkerNotify.notifyStatus(context, claimStatus(context), "next run within 24 h",
                "Simosa daily check-in");
        } catch (Throwable t) {
            // A worker that throws is a retried worker; the check-in already logs its own failures.
            Log.w(TAG, "WORKER_ERR " + t);
        }
        return Result.success();
    }

    /** Called from the patched {@code MainActivity.onCreate}; safe to call on every launch. */
    public static void schedule(Context context) {
        if (context == null) {
            return;
        }
        try {
            Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                DailyCheckinWorker.class, INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request);
            Log.i(TAG, "WORKER_SCHEDULED " + UNIQUE_WORK);
        } catch (Throwable t) {
            // WorkManager missing or not initialised is not worth crashing a launch over.
            Log.w(TAG, "WORKER_SCHEDULE_ERR " + t);
        }
    }

    private static String claimStatus(Context context) {
        String why = AutoClaim.check(context, "ladder");
        if (why == null) {
            return "not claimed yet";
        }
        return why.contains("latched") ? "claimed today" : "skipped (" + why + ")";
    }
}
