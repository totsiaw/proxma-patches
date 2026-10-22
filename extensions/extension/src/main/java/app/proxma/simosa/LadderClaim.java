package app.proxma.simosa;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Claims today's Daily Rewards MB rung from the background worker via reflection into the app's
 * {@code DailyRewardRemoteDataSource} (load) and {@code DailyRewardSubscribedRemoteDataSource}
 * (claim). Both carry the full MSA encrypted pipeline, so we don't need to reimplement it.
 *
 * <p>Latches only on success — opposite to the in-app pre-latch, so a worker failure leaves the
 * in-app claim path intact. Double-fire is harmless (server rejects duplicate claims).
 */
public final class LadderClaim {

    private static final String TAG = "PROXMA_CLAIM";

    private static final String DATA_SOURCE_LOAD =
        "com.jazz.jazzworld.data.network.genericapis.dailyreward.DailyRewardRemoteDataSource";
    private static final String DATA_SOURCE_CLAIM =
        "com.jazz.jazzworld.data.network.genericapis.dailyreward.DailyRewardSubscribedRemoteDataSource";
    private static final String LISTENER_LOAD =
        "com.jazz.jazzworld.data.network.genericapis.dailyreward.OnDailyRewardListListeners";
    private static final String LISTENER_CLAIM =
        "com.jazz.jazzworld.data.network.genericapis.dailyreward.OnDailyRewardSubscriptionListeners";
    private static final String DAYS_LIST_ITEM =
        "com.jazz.jazzworld.data.appmodels.dailyreward.response.DaysListItem";

    private static final int TIMEOUT_SECONDS = 45;

    static String sResult = "n/a";

    private LadderClaim() {}

    public static String getResult() { return sResult; }

    /** @return true if settled; false if transient (retry-worthy). */
    public static boolean tryClaimLadder(Context context) {
        sResult = "n/a";
        try {
            return tryClaimInner(context);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            log("LADDER_REFLECT " + e);
            sResult = "reflect: " + e.getMessage();
            return true; // permanent: app structure changed
        } catch (Throwable t) {
            log("LADDER_ERR " + t);
            sResult = "error: " + t.getMessage();
            return false;
        }
    }

    private static boolean tryClaimInner(Context context) throws Exception {
        String skip = AutoClaim.check(context, "ladder");
        if (skip != null) {
            log("LADDER_SKIP " + skip);
            sResult = skip;
            return true;
        }

        // Step 1: Load today's ladder state via DailyRewardRemoteDataSource
        log("LADDER_LOAD_START");
        Class<?> loadDsClass = Class.forName(DATA_SOURCE_LOAD);
        Constructor<?> loadCtor = loadDsClass.getConstructor(Context.class);
        Object loadDs = loadCtor.newInstance(context);

        Class<?> loadListenerClass = Class.forName(LISTENER_LOAD);

        final CountDownLatch loadLatch = new CountDownLatch(1);
        final AtomicReference<Object> loadResponse = new AtomicReference<>(null);
        final AtomicReference<String> loadError = new AtomicReference<>(null);

        Object loadProxy = Proxy.newProxyInstance(
            context.getClassLoader(),
            new Class<?>[] { loadListenerClass },
            new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    try {
                        String name = method.getName();
                        if ("onDailyRewardListSuccess".equals(name) && args != null && args.length > 0) {
                            loadResponse.set(args[0]);
                        } else if ("onDailyRewardListFailed".equals(name)) {
                            String text = args != null && args.length > 0 ? String.valueOf(args[0]) : "";
                            String code = args != null && args.length > 1 ? String.valueOf(args[1]) : "";
                            loadError.set(text + " code=" + code);
                        } else if ("toString".equals(name)) {
                            return "ProxmaLadderLoadListener";
                        } else if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        } else if ("equals".equals(name)) {
                            return proxy == args[0];
                        }
                    } catch (Throwable t) {
                        log("LADDER_LOAD_CB_ERR " + t);
                    } finally {
                        if (!"toString".equals(method.getName()) && !"hashCode".equals(method.getName())
                            && !"equals".equals(method.getName())) {
                            loadLatch.countDown();
                        }
                    }
                    return null;
                }
            }
        );

        Method requestList = loadDsClass.getMethod("requestDailyRewardList", loadListenerClass);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    requestList.invoke(loadDs, loadProxy);
                } catch (Throwable t) {
                    log("LADDER_LOAD_INVOKE_ERR " + t);
                    loadError.set("invoke: " + t);
                    loadLatch.countDown();
                }
            }
        });

        if (!loadLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log("LADDER_LOAD_TIMEOUT");
            sResult = "load timeout";
            return false;
        }

        if (loadError.get() != null) {
            log("LADDER_LOAD_FAIL " + loadError.get());
            sResult = "load fail: " + loadError.get();
            return false;
        }

        Object response = loadResponse.get();
        if (response == null) {
            log("LADDER_LOAD_NULL");
            sResult = "load null";
            return false;
        }

        // Step 2: Parse response → find today's rung
        Method getData = response.getClass().getMethod("getData");
        Object data = getData.invoke(response);
        if (data == null) {
            log("LADDER_NO_DATA");
            sResult = "no data";
            return false;
        }

        String claimed = (String) data.getClass().getMethod("getClaimed").invoke(data);
        String currentDay = (String) data.getClass().getMethod("getCurrentDay").invoke(data);
        String currentStatus = (String) data.getClass().getMethod("getCurrentStatus").invoke(data);
        List<?> daysList = (List<?>) data.getClass().getMethod("getDaysList").invoke(data);

        log("LADDER_STATE claimed=" + claimed + " day=" + currentDay + " status=" + currentStatus);

        if ("true".equals(claimed)) {
            AutoClaim.latch(context, "ladder");
            log("LADDER_ALREADY_CLAIMED");
            sResult = "already claimed";
            return true;
        }

        if ("1".equals(currentStatus)) {
            log("LADDER_SKIP_PAID status=1");
            sResult = "skip paid";
            return true;
        }

        if (daysList == null || daysList.isEmpty()) {
            log("LADDER_NO_DAYS");
            sResult = "no days list";
            return false;
        }

        // Find the rung matching currentDay
        Object rung = null;
        for (Object item : daysList) {
            String day = (String) item.getClass().getMethod("getDay").invoke(item);
            if (day != null && day.equals(currentDay)) {
                rung = item;
                break;
            }
        }

        if (rung == null) {
            StringBuilder sb = new StringBuilder("LADDER_NO_RUNG day=" + currentDay + " available=[");
            for (int i = 0; i < daysList.size(); i++) {
                Object item = daysList.get(i);
                try {
                    sb.append(item.getClass().getMethod("getDay").invoke(item));
                } catch (Throwable t) {
                    sb.append("?");
                }
                if (i < daysList.size() - 1) sb.append(",");
            }
            sb.append("]");
            log(sb.toString());
            sResult = "no rung for day " + currentDay;
            return false;
        }

        // Step 3: Claim via DailyRewardSubscribedRemoteDataSource
        log("LADDER_CLAIM_START day=" + currentDay);
        Class<?> claimDsClass = Class.forName(DATA_SOURCE_CLAIM);
        Constructor<?> claimCtor = claimDsClass.getConstructor(Context.class);
        Object claimDs = claimCtor.newInstance(context);

        Class<?> claimListenerClass = Class.forName(LISTENER_CLAIM);
        Class<?> daysListItemClass = Class.forName(DAYS_LIST_ITEM);

        final CountDownLatch claimLatch = new CountDownLatch(1);
        final AtomicBoolean claimSuccess = new AtomicBoolean(false);
        final AtomicReference<String> claimFailText = new AtomicReference<>(null);

        Object claimProxy = Proxy.newProxyInstance(
            context.getClassLoader(),
            new Class<?>[] { claimListenerClass },
            new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    try {
                        String name = method.getName();
                        if ("onDailyRewardSubscriptionSuccess".equals(name)) {
                            claimSuccess.set(true);
                        } else if ("onDailyRewardSubscriptionFailed".equals(name)) {
                            String text = args != null && args.length > 0 ? String.valueOf(args[0]) : "";
                            String code = args != null && args.length > 1 ? String.valueOf(args[1]) : "";
                            claimFailText.set(text + " code=" + code);
                        } else if ("toString".equals(name)) {
                            return "ProxmaLadderClaimListener";
                        } else if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        } else if ("equals".equals(name)) {
                            return proxy == args[0];
                        }
                    } catch (Throwable t) {
                        log("LADDER_CLAIM_CB_ERR " + t);
                    } finally {
                        if (!"toString".equals(method.getName()) && !"hashCode".equals(method.getName())
                            && !"equals".equals(method.getName())) {
                            claimLatch.countDown();
                        }
                    }
                    return null;
                }
            }
        );

        String statusArg = currentStatus != null ? currentStatus : "";
        final Object finalRung = rung;
        Method requestClaim = claimDsClass.getMethod("requestSubscribeDailyReward",
            daysListItemClass, String.class, String.class, claimListenerClass);

        final String fStatus = statusArg;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    requestClaim.invoke(claimDs, finalRung, fStatus, "auto_claim", claimProxy);
                } catch (Throwable t) {
                    log("LADDER_CLAIM_INVOKE_ERR " + t);
                    claimFailText.set("invoke: " + t);
                    claimLatch.countDown();
                }
            }
        });

        if (!claimLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log("LADDER_CLAIM_TIMEOUT");
            sResult = "claim timeout";
            return false;
        }

        if (claimSuccess.get()) {
            AutoClaim.latch(context, "ladder");
            log("LADDER_CLAIMED day=" + currentDay);
            sResult = "claimed day " + currentDay;
            return true;
        }

        String failText = claimFailText.get();
        log("LADDER_CLAIM_FAIL " + failText);
        if (failText != null && failText.toLowerCase().contains("already")) {
            AutoClaim.latch(context, "ladder");
            sResult = "already claimed (from fail)";
            return true;
        }
        sResult = "claim fail: " + failText;
        return false;
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
    }
}
