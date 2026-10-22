package app.proxma.simosa;

import android.content.Context;

import java.security.MessageDigest;

/**
 * Per-account scope for everything this extension persists.
 *
 * <p>A Simosa install outlives the account signed into it, so a global preferences key would hand
 * one account's day latch or refresh token to the next. Both users of this class key on the first
 * six bytes of SHA-256(msisdn) rendered as 12 hex digits — the same account id the ladder/check-in
 * latch has used since 1.23, so latches written by earlier builds keep working.
 *
 * <p>Returns {@code null} when nobody is signed in; callers must skip the attempt rather than fire
 * it unscoped.
 */
final class AccountScope {

    private AccountScope() {}

    /** 12 hex digits for the signed-in msisdn, or null when there is no account. */
    static String id(Context context) {
        if (context == null) {
            return null;
        }
        try {
            Class<?> dataManager = Class.forName("com.jazz.jazzworld.data.manager.DataManager");
            Object companion = dataManager.getField("Companion").get(null);
            if (companion == null) {
                return null;
            }
            Object instance = companion.getClass().getMethod("getInstance").invoke(companion);
            if (instance == null) {
                return null;
            }
            Object userData = dataManager.getMethod("getUserData", Context.class).invoke(instance, context);
            if (userData == null) {
                return null;
            }
            Object msisdn = userData.getClass().getMethod("getMsisdn").invoke(userData);
            if (!(msisdn instanceof String) || ((String) msisdn).isEmpty()) {
                return null;
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(((String) msisdn).getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                builder.append(hex(digest[i] & 0xff));
            }
            return builder.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String hex(int value) {
        String digits = "0123456789abcdef";
        return "" + digits.charAt((value >> 4) & 0xf) + digits.charAt(value & 0xf);
    }
}
