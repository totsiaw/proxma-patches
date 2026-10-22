package app.proxma.simosa;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WorkManager-based background daily claim + check-in worker.
 *
 * <p>Runs the Simosa daily reward ladder claim (via the app's Hilt DI graph,
 * accessed through reflection) and then the daily check-in (via
 * {@link BackgroundCheckin}). Posts a notification via {@link WorkerNotify}.
 *
 * <p>All app-internal types are accessed by reflection so this class compiles
 * against only the Android SDK + WorkManager (compileOnly).
 */
public final class ClaimWorker extends Worker {

    private static final String TAG = "PROXMA_CLAIM";
    private static final String WORK_NAME = "proxma_autoclaim";

    private CountDownLatch latch;

    public ClaimWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();

        Log.i(TAG, "DOWORK_ENTER");
        WorkerNotify.log("DOWORK_ENTER");

        if (WorkerNotify.isClaimedToday(ctx)) {
            WorkerNotify.log("WORKER_SKIP already claimed today");
            BackgroundCheckin.doCheckIn(ctx);
            armTomorrow(ctx);
            WorkerNotify.notifyStatus(ctx, "already done", "tomorrow ~00:35", "Skip");
            return Result.success();
        }

        int attempt = WorkerNotify.bumpAttempt(ctx);
        if (attempt > 36) {
            WorkerNotify.log("CHAIN_CAP tries_today=36 stop");
            armTomorrow(ctx);
            return Result.success();
        }

        // Try to get the user session via K8.F.Y(context)
        Object userSession = getUserSession(ctx);
        if (userSession == null) {
            WorkerNotify.log("NO_SESSION skip_ladder run_checkin");
            BackgroundCheckin.doCheckIn(ctx);

            if (WorkerNotify.isClaimedToday(ctx)) {
                WorkerNotify.log("WORKER_OK checkin_only stop");
                armTomorrow(ctx);
                WorkerNotify.notifyStatus(ctx, "checkin only (no session)", "tomorrow ~00:35", "Claimed");
                return Result.success();
            } else {
                WorkerNotify.log("WORKER_RETRY no_session checkin_pending 10m");
                armIn(ctx, 10);
                WorkerNotify.notifyStatus(ctx, "no session, checkin pending", "~10 min", "Retry");
                return Result.success();
            }
        }

        // Set user data on the DataManager singleton
        try {
            Class<?> dmClass = Class.forName("com.jazz.jazzworld.data.manager.DataManager");
            Object companion = dmClass.getField("Companion").get(null);
            Object dm = companion.getClass().getMethod("getInstance").invoke(companion);
            dmClass.getMethod("setUserData",
                Class.forName("com.jazz.jazzworld.data.manager.UserDataModel"))
                .invoke(dm, userSession);
        } catch (Throwable t) {
            WorkerNotify.log("SET_USER_DATA_ERR " + t);
        }

        // Build the DailyRewardRepository via Hilt's generated component (reflection)
        Object repo = null;
        try {
            repo = buildRepo(ctx);
        } catch (Throwable t) {
            WorkerNotify.log("NO_REPO " + t);
            armIn(ctx, 10);
            return Result.success();
        }

        if (repo == null) {
            WorkerNotify.log("NO_REPO null");
            armIn(ctx, 10);
            return Result.success();
        }

        WorkerNotify.log("REPO_OK");

        // Set up the latch for the async ladder claim callback
        latch = new CountDownLatch(1);

        try {
            // Create the list callback and trigger the ladder claim
            Object listCb = createListCallback(repo);
            Method registerCb = repo.getClass().getMethod("c",
                Class.forName("com.jazz.jazzworld.data.network.genericapis.dailyreward.OnDailyRewardListListeners"));
            registerCb.invoke(repo, listCb);
        } catch (Throwable t) {
            WorkerNotify.log("CALLBACK_ERR " + t);
        }

        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        // Run check-in after ladder attempt
        BackgroundCheckin.doCheckIn(ctx);

        if (WorkerNotify.isClaimedToday(ctx)) {
            WorkerNotify.log("WORKER_OK claimed stop");
            armTomorrow(ctx);
            WorkerNotify.notifyStatus(ctx, "claimed!", "tomorrow ~00:35", "Claimed");
            return Result.success();
        } else {
            WorkerNotify.log("WORKER_RETRY not claimed next 10m");
            armIn(ctx, 10);
            WorkerNotify.notifyStatus(ctx, "pending", "~10 min", "Retry");
            return Result.success();
        }
    }

    /** Schedule the worker to run at ~00:35 PKT tomorrow. */
    public static void schedule(Context context) {
        try {
            Calendar now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Karachi"));
            Calendar target = (Calendar) now.clone();
            target.set(Calendar.HOUR_OF_DAY, 0);
            target.set(Calendar.MINUTE, 35);
            target.set(Calendar.SECOND, 0);
            target.set(Calendar.MILLISECOND, 0);
            if (!target.after(now)) {
                target.add(Calendar.DAY_OF_MONTH, 1);
            }

            long delayMs = target.getTimeInMillis() - now.getTimeInMillis();
            if (delayMs < 60_000) delayMs = 60_000;

            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(ClaimWorker.class)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build();

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req);

            Log.i(TAG, "SCHEDULED delay=" + (delayMs / 60000) + "min");
        } catch (Throwable t) {
            Log.w(TAG, "schedule err: " + t);
        }
    }

    /** Count down the latch (called from the ladder claim callback). */
    void countDown() {
        CountDownLatch l = latch;
        if (l != null) l.countDown();
    }

    // --- Scheduling helpers ---

    private static void armTomorrow(Context context) {
        schedule(context);
    }

    private static void armIn(Context context, long minutes) {
        try {
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(ClaimWorker.class)
                .setInitialDelay(minutes, TimeUnit.MINUTES)
                .build();
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req);
            Log.i(TAG, "ARM_IN " + minutes + "min");
        } catch (Throwable t) {
            Log.w(TAG, "armIn err: " + t);
        }
    }

    // --- Reflection helpers for Hilt DI graph ---

    private static Object getUserSession(Context context) {
        try {
            Class<?> k8f = Class.forName("K8.F");
            Object instance = k8f.getField("a").get(null);
            return k8f.getMethod("Y", Context.class).invoke(instance, context);
        } catch (Throwable t) {
            return null;
        }
    }

    private Object buildRepo(Context context) throws Exception {
        Object app = context.getApplicationContext();
        Class<?> hiltApp = Class.forName("com.jazz.jazzworld.presentation.Hilt_MyApplication");
        Object component = hiltApp.getMethod("generatedComponent").invoke(app);

        Class<?> compClass = Class.forName("com.jazz.jazzworld.presentation.a$h");
        Object cast = compClass.cast(component);

        // Get the providers from the Hilt component fields
        Object ktorProvider = getProviderValue(cast, compClass, "x");
        Object remoteDs = getProviderValue(cast, compClass, "i0");
        Object subscribedDs = getProviderValue(cast, compClass, "j0");
        Object claimedDs = getProviderValue(cast, compClass, "k0");

        // Build the repository: V7.j.a(context, ktor, remoteDs, subscribedDs, claimedDs)
        Class<?> repoFactory = Class.forName("V7.j");
        for (Method m : repoFactory.getMethods()) {
            if (m.getName().equals("a") && m.getParameterTypes().length == 5) {
                return m.invoke(null, context, ktorProvider, remoteDs, subscribedDs, claimedDs);
            }
        }
        return null;
    }

    private static Object getProviderValue(Object component, Class<?> compClass, String fieldName) throws Exception {
        Field f = compClass.getDeclaredField(fieldName);
        f.setAccessible(true);
        Object provider = f.get(component);
        return provider.getClass().getMethod("get").invoke(provider);
    }

    private Object createListCallback(Object repo) throws Exception {
        Class<?> listenerClass = Class.forName(
            "com.jazz.jazzworld.data.network.genericapis.dailyreward.OnDailyRewardListListeners");
        return java.lang.reflect.Proxy.newProxyInstance(
            listenerClass.getClassLoader(),
            new Class<?>[]{ listenerClass },
            (proxy, method, args) -> {
                String name = method.getName();
                WorkerNotify.log("LIST_CB " + name);

                if ("onDailyRewardListSuccess".equals(name) && args != null && args.length > 0) {
                    try {
                        triggerLadderClaim(repo, args[0]);
                    } catch (Throwable t) {
                        WorkerNotify.log("LADDER_CLAIM_ERR " + t);
                        countDown();
                    }
                } else {
                    countDown();
                }
                return null;
            }
        );
    }

    private void triggerLadderClaim(Object repo, Object response) {
        try {
            Object data = response.getClass().getMethod("getData").invoke(response);
            if (data == null) {
                WorkerNotify.log("LADDER no_data");
                countDown();
                return;
            }

            // Data has flat fields: getClaimed(), getCurrentDay(), getCurrentStatus()
            Object claimedObj = data.getClass().getMethod("getClaimed").invoke(data);
            String claimed = claimedObj != null ? String.valueOf(claimedObj) : "";
            if ("true".equals(claimed)) {
                WorkerNotify.log("LIST claimed=true already");
                WorkerNotify.markClaimed(getApplicationContext());
                countDown();
                return;
            }

            Object currentDayObj = data.getClass().getMethod("getCurrentDay").invoke(data);
            String currentDay = currentDayObj != null ? String.valueOf(currentDayObj) : "";

            Object statusObj = data.getClass().getMethod("getCurrentStatus").invoke(data);
            String currentStatus = statusObj != null ? String.valueOf(statusObj) : "";

            // Find the DaysListItem matching currentDay
            Object daysList = data.getClass().getMethod("getDaysList").invoke(data);
            if (!(daysList instanceof java.util.List)) {
                WorkerNotify.log("LADDER no_days_list");
                countDown();
                return;
            }

            java.util.List<?> days = (java.util.List<?>) daysList;
            Object targetItem = null;
            for (int i = 0; i < days.size(); i++) {
                Object item = days.get(i);
                Object dayVal = item.getClass().getMethod("getDay").invoke(item);
                if (currentDay.equals(String.valueOf(dayVal))) {
                    targetItem = item;
                    break;
                }
            }

            if (targetItem == null) {
                WorkerNotify.log("LADDER no_matching_day item for " + currentDay);
                countDown();
                return;
            }

            WorkerNotify.log("LADDER claiming day=" + currentDay);

            // Create subscribe callback
            Class<?> subListenerClass = Class.forName(
                "com.jazz.jazzworld.data.network.genericapis.dailyreward.OnDailyRewardSubscriptionListeners");
            final Object dayItem = targetItem;
            Object subCb = java.lang.reflect.Proxy.newProxyInstance(
                subListenerClass.getClassLoader(),
                new Class<?>[]{ subListenerClass },
                (proxy, method, args) -> {
                    String name = method.getName();
                    WorkerNotify.log("SUB_CB " + name);
                    if ("onDailyRewardSubscriptionSuccess".equals(name)) {
                        WorkerNotify.markClaimed(getApplicationContext());
                    }
                    countDown();
                    return null;
                }
            );

            // repo.e(dayItem, currentStatus, "autoclaim", subCb)
            Class<?> daysListItemClass = Class.forName(
                "com.jazz.jazzworld.data.appmodels.dailyreward.response.DaysListItem");
            Method subscribeMethod = repo.getClass().getMethod("e",
                daysListItemClass, String.class, String.class, subListenerClass);
            subscribeMethod.invoke(repo, targetItem, currentStatus, "autoclaim", subCb);
            WorkerNotify.log("CLAIM_SENT");
        } catch (Throwable t) {
            WorkerNotify.log("LADDER_ERR " + t);
            countDown();
        }
    }
}
