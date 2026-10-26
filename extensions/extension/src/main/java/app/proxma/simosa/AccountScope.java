package app.proxma.simosa;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.security.MessageDigest;
import java.util.Map;

/**
 * Per-account scope for everything this extension persists.
 *
 * <p>A Simosa install outlives the account signed into it, so a global preferences key would hand
 * one account's day latch or refresh token to the next. Both users of this class key on the first
 * six bytes of SHA-256(msisdn) rendered as 12 hex digits — the same account id the ladder/check-in
 * latch has used since 1.23, so latches written by earlier builds keep working.
 *
 * <p>When the DataManager singleton is unreachable (cold WorkManager start with no prior Activity),
 * falls back to the account hash embedded in our own stored token key. After a sign-out with no
 * new sign-in, a cold worker may therefore run once more under the previous account; the next warm
 * launch replaces the stored token via {@code saveToken} in {@code acquireBearer}.
 *
 * <p>Returns {@code null} when nobody is signed in and no stored token exists.
 */
final class AccountScope {

    private static final String TAG = "PROXMA_CLAIM";
    private static final String PREFS = "proxma_simosa";
    private static final String TOKEN_PREFIX = "proxma_token_";

    private AccountScope() {}

    /** 12 hex digits for the signed-in msisdn, or null when there is no account. */
    static String id(Context context) {
        if (context == null) {
            return null;
        }
        String fromManager = idFromDataManager(context);
        if (fromManager != null) {
            return fromManager;
        }
        return idFromStoredToken(context);
    }

    private static String idFromDataManager(Context context) {
        try {
            Class<?> dataManager = Class.forName("com.jazz.jazzworld.data.manager.DataManager");
            Object companion = dataManager.getField("Companion").get(null);
            if (companion == null) {
                Log.i(TAG, "ACCOUNT_MGR_NULL at=companion");
                return null;
            }
            Object instance = companion.getClass().getMethod("getInstance").invoke(companion);
            if (instance == null) {
                Log.i(TAG, "ACCOUNT_MGR_NULL at=instance");
                return null;
            }
            Object userData = dataManager.getMethod("getUserData", Context.class).invoke(instance, context);
            if (userData == null) {
                Log.i(TAG, "ACCOUNT_MGR_NULL at=userData");
                return null;
            }
            Object msisdn = userData.getClass().getMethod("getMsisdn").invoke(userData);
            if (!(msisdn instanceof String) || ((String) msisdn).isEmpty()) {
                Log.i(TAG, "ACCOUNT_MGR_NULL at=msisdn");
                return null;
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(((String) msisdn).getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                builder.append(hex(digest[i] & 0xff));
            }
            return builder.toString();
        } catch (Throwable t) {
            Log.i(TAG, "ACCOUNT_MGR_NULL at=throw:" + t.getClass().getSimpleName());
            return null;
        }
    }

    private static String idFromStoredToken(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
            Map<String, ?> all = prefs.getAll();
            for (String key : all.keySet()) {
                if (key.startsWith(TOKEN_PREFIX)) {
                    String hash = key.substring(TOKEN_PREFIX.length());
                    if (hash.length() == 12) {
                        Log.i(TAG, "ACCOUNT_COLD_START using stored hash");
                        return hash;
                    }
                }
            }
        } catch (Throwable t) {
            // prefs unavailable
        }
        return null;
    }

    private static String hex(int value) {
        String digits = "0123456789abcdef";
        return "" + digits.charAt((value >> 4) & 0xf) + digits.charAt(value & 0xf);
    }
}
