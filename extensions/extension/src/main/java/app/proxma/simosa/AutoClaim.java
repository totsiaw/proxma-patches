package app.proxma.simosa;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runtime half of the Simosa auto-claim: a per-account, per-day latch, one jittered attempt per day,
 * and the {@code PROXMA_CLAIM} logcat lines. Nothing here is user-visible.
 *
 * <p>Latch: SharedPreferences file {@code proxma_simosa}, key
 * {@code proxma_claim_<sha256(msisdn)[0..6]>_<yyyyMMdd>} (Asia/Karachi day), int bitmask
 * 1 = ladder, 2 = check-in. It is committed <em>before</em> the app's own claim call, so a repeat
 * attempt cannot happen even if the process dies mid-claim.
 *
 * <p>The app types this touches (DataManager, UserDataModel) are reached by reflection, so the
 * extension does not depend on the app's build and cannot break the patch on a new version.
 */
public final class AutoClaim {

    private static final String TAG = "PROXMA_CLAIM";
    private static final String PREFS = "proxma_simosa";
    private static final String PREFIX = "proxma_claim_";

    /** Kinds currently waiting out their delay, so repeated state responses do not stack attempts. */
    private static final ConcurrentHashMap<String, String> scheduled = new ConcurrentHashMap<>();

    /** Kinds with an attempt in flight that has not reported a result yet. */
    private static final ConcurrentHashMap<String, String> pending = new ConcurrentHashMap<>();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AutoClaim() {}

    public static void log(String kind, String detail) {
        try {
            Log.i(TAG, kind + " " + detail);
        } catch (Throwable ignored) {
            // logging must never break a claim
        }
    }

    private static int bit(String kind) {
        return "ladder".equals(kind) ? 1 : 2;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, 0);
    }

    /**
     * {@code proxma_claim_<msisdn-hash>_<yyyyMMdd>}, or null when no account is signed in — in which
     * case the attempt is skipped rather than fired without a latch.
     */
    private static String key(Context context) {
        if (context == null) {
            return null;
        }
        try {
            Class<?> dataManager = Class.forName("com.jazz.jazzworld.data.manager.DataManager");
            Object companion = dataManager.getField("Companion").get(null);
            if (companion == null) {
                return null;
            }
            Method getInstance = companion.getClass().getMethod("getInstance");
            Object instance = getInstance.invoke(companion);
            if (instance == null) {
                return null;
            }
            Method getUserData = dataManager.getMethod("getUserData", Context.class);
            Object userData = getUserData.invoke(instance, context);
            if (userData == null) {
                return null;
            }
            Object msisdn = userData.getClass().getMethod("getMsisdn").invoke(userData);
            if (!(msisdn instanceof String) || ((String) msisdn).isEmpty()) {
                return null;
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(((String) msisdn).getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder(PREFIX);
            for (int i = 0; i < 6; i++) {
                builder.append(hex(digest[i] & 0xff));
            }
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("Asia/Karachi"));
            return builder.append('_').append(format.format(new Date())).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String hex(int value) {
        String digits = "0123456789abcdef";
        return "" + digits.charAt((value >> 4) & 0xf) + digits.charAt(value & 0xf);
    }

    /** Null when the attempt may proceed, otherwise the reason it must not. */
    public static String check(Context context, String kind) {
        String key = key(context);
        if (key == null) {
            return "skip no-account";
        }
        int mask = prefs(context).getInt(key, 0);
        if ((mask & bit(kind)) != 0) {
            return "skip latched";
        }
        return null;
    }

    /** Sets today's bit for {@code kind}, synchronously, before the claim is fired. */
    public static void latch(Context context, String kind) {
        final String key = key(context);
        if (key == null) {
            return;
        }
        SharedPreferences preferences = prefs(context);
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String existing = entry.getKey();
            if (existing.startsWith(PREFIX) && !existing.equals(key)) {
                editor.remove(existing);
            }
        }
        editor.putInt(key, preferences.getInt(key, 0) | bit(kind));
        editor.commit();
    }

    /**
     * Entry point from the ViewModel hooks: one jittered attempt per kind, 2-6 s after the app has
     * applied the response. Runs on the main thread, which is where the app's own button runs.
     */
    public static void schedule(final ClaimTarget target, final String kind) {
        try {
            String why = check(target.proxmaContext(), kind);
            if (why != null) {
                log(kind, why);
                return;
            }
            if (scheduled.putIfAbsent(kind, kind) != null) {
                return;
            }
            final int delay = 2000 + ThreadLocalRandom.current().nextInt(4001);
            log(kind, "scheduled delay=" + delay + "ms");
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    scheduled.remove(kind);
                    try {
                        target.proxmaFire();
                    } catch (Throwable t) {
                        unschedule(kind);
                        log(kind, "error fire " + t);
                    }
                }
            }, delay);
        } catch (Throwable t) {
            unschedule(kind);
            log(kind, "error schedule " + t);
        }
    }

    public static void unschedule(String kind) {
        scheduled.remove(kind);
    }

    /** Called by {@code proxmaFire()} after the latch is written, right before the app's claim call. */
    public static void fired(String kind, String day, String status) {
        pending.put(kind, "auto");
        log(kind, "fire day=" + day + " status=" + status);
    }

    /**
     * The user's own tap went through the patched check-in path: latch today so the automatic
     * attempt does not repeat it.
     */
    public static void manual(Context context, String kind) {
        latch(context, kind);
        pending.put(kind, "manual");
        log(kind, "fire manual-tap");
    }

    /** Logs one {@code success|error <auto|manual> <detail>} line per fired attempt; no-op otherwise. */
    private static void result(String kind, boolean success, String detail) {
        Object origin = pending.remove(kind);
        if (origin == null) {
            return;
        }
        log(kind, (success ? "success " : "error ") + origin + " " + detail);
    }

    /** Hooked at the top of the ladder's {@code onDailyRewardSubscriptionSuccess}. */
    public static void ladderSuccess(Object response) {
        String detail = "";
        try {
            detail = describe(response);
        } catch (Throwable ignored) {
            // the log line is not worth a crash
        }
        result("ladder", true, detail);
    }

    private static String describe(Object response) throws Exception {
        if (response == null) {
            return "";
        }
        Method getData = response.getClass().getMethod("getData");
        Object data = getData.invoke(response);
        if (data == null) {
            return "";
        }
        Class<?> type = data.getClass();
        return "day=" + String.valueOf(type.getMethod("getCurrentDay").invoke(data))
            + " status=" + String.valueOf(type.getMethod("getCurrentStatus").invoke(data))
            + " claimed=" + String.valueOf(type.getMethod("getClaimed").invoke(data));
    }

    /** Hooked at the top of {@code onDailyRewardSubscriptionFailed(errorCode, message)}. */
    public static void ladderFailed(String errorCode, String message) {
        result("ladder", false, "code=" + errorCode + " msg=" + message);
    }

    /**
     * Hooked at the top of the check-in result collector. Its argument is the app's
     * Loading / Success / Failure wrapper; only a settled result is reported.
     */
    public static void checkinResult(Object resources) {
        if (resources == null) {
            return;
        }
        String name = resources.getClass().getSimpleName();
        if ("Loading".equals(name)) {
            return;
        }
        if ("Success".equals(name)) {
            result("checkin", true, "");
        } else {
            result("checkin", false, String.valueOf(resources));
        }
    }
}
