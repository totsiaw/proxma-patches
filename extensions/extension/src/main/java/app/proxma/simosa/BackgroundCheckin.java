package app.proxma.simosa;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * B26 daily check-in: two-tier token (cached + refresh), 419/401 retry,
 * 409 day-gate, JSON data-wrapper fallback.
 *
 * <p>Called from the patched {@code ClaimWorker.doWork()} after the ladder claim.
 * Self-contained: uses only standard Android + reflection for app internals.
 */
public final class BackgroundCheckin {

    private static final String TAG = "PROXMA_CLAIM";
    private static final String PREFS_NAME = "proxma_claim";
    private static final String CHECKIN_URL = "https://api-prod-dailycheckin.simosa.app/checkin";
    private static final String REFRESH_URL = "https://api-prod-dailycheckin.simosa.app/auth/refresh";
    private static final int TIMEOUT_MS = 15000;

    static String sSession = "none";
    static String sCheckin = "n/a";
    static String sLastBearer = null;

    private BackgroundCheckin() {}

    public static void doCheckIn(Context context) {
        sSession = "none";
        sCheckin = "n/a";
        try {
            doCheckInInner(context);
        } catch (Throwable t) {
            log("CHECKIN_ERR " + t);
        }
    }

    public static String getSession() { return sSession; }
    public static String getCheckin() { return sCheckin; }
    public static String getLastBearer() { return sLastBearer; }

    private static void doCheckInInner(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        if (prefs == null) {
            log("CHECKIN_ERR no_prefs");
            return;
        }

        int dayKey = dayKey();
        if (prefs.getInt("checkin_day", 0) == dayKey) {
            log("CHECKIN_DONE_TODAY");
            sCheckin = "already done";
            return;
        }

        log("CHECKIN_START");
        sSession = "cached";

        String bearer = null;
        boolean fromCache = false;

        // === Tier 1: cached token ===
        try {
            Object cached = getCachedToken(context);
            if (cached != null) {
                String rt = invoke(cached, "getRefresh_token");
                String uuid = invoke(cached, "getUuid");
                if (rt != null && uuid != null) {
                    prefs.edit()
                        .putString("simosa_refresh_token", rt)
                        .putString("simosa_uuid", uuid)
                        .apply();
                }
                String at = invoke(cached, "resolveAccessToken");
                if (at != null && !at.isEmpty()) {
                    bearer = "Bearer " + at;
                    fromCache = true;
                }
            }
        } catch (Throwable t) {
            log("CHECKIN_CACHE_ERR " + t);
        }

        // === Tier 2: refresh fallback ===
        if (bearer == null) {
            bearer = tryRefresh(prefs);
            fromCache = false;
        }

        if (bearer == null) return;
        sLastBearer = bearer;

        // === POST check-in ===
        HttpResult r = httpPost(CHECKIN_URL, "{}", bearer);

        if (r.code >= 200 && r.code < 300) {
            log("CHECKIN_OK " + r.code + " " + truncate(r.body));
            prefs.edit().putInt("checkin_day", dayKey).apply();
            sCheckin = "checked in";
            MilestoneClaim.tryClaimMilestone(context, bearer);
            return;
        }

        // B26: 401/419 with cached token → retry via refresh
        if (fromCache && (r.code == 401 || r.code == 419)) {
            log("CHECKIN_TOKEN_EXPIRED retrying via refresh");
            String fresh = tryRefresh(prefs);
            if (fresh != null) {
                sLastBearer = fresh;
                HttpResult r2 = httpPost(CHECKIN_URL, "{}", fresh);
                if (r2.code >= 200 && r2.code < 300) {
                    log("CHECKIN_OK " + r2.code + " " + truncate(r2.body));
                    prefs.edit().putInt("checkin_day", dayKey).apply();
                    sCheckin = "checked in";
                    MilestoneClaim.tryClaimMilestone(context, fresh);
                    return;
                }
                r = r2;
            }
        }

        // B26: 409 → mark checkin_day (already done today)
        if (r.code == 409) {
            prefs.edit().putInt("checkin_day", dayKey).apply();
            sCheckin = "already done";
            MilestoneClaim.tryClaimMilestone(context, bearer);
            return;
        }

        log("CHECKIN_FAIL " + r.code + " " + truncate(r.body));
        sCheckin = "fail";
    }

    private static Object getCachedToken(Context context) {
        try {
            Class<?> ncm = Class.forName("com.jazz.jazzworld.data.network.networkcache.NetworkCacheManager");
            Object instance = ncm.getField("INSTANCE").get(null);
            Class<?> tokenClass = Class.forName(
                "com.jazz.jazzworld.data.appmodels.dailyreward.response.DailyRewardAuthTokenResponse");

            String rawKey = "key_gamification_authToken";
            String hashedKey = rawKey;
            try {
                hashedKey = (String) Class.forName("K8.X")
                    .getMethod("M", String.class).invoke(null, rawKey);
            } catch (Throwable ignored) {}

            Object cacheData = ncm.getMethod("getCacheData",
                Context.class, Class.class, String.class, long.class, long.class)
                .invoke(instance, context, tokenClass, hashedKey, Long.MAX_VALUE, 0L);
            if (cacheData == null) return null;

            return cacheData.getClass().getMethod("getData").invoke(cacheData);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String tryRefresh(SharedPreferences prefs) {
        String rt = prefs.getString("simosa_refresh_token", null);
        String uuid = prefs.getString("simosa_uuid", null);
        if (rt == null || uuid == null) {
            log("CHECKIN_NO_TOKEN no_refresh");
            sCheckin = "no token";
            return null;
        }

        try {
            String jsonBody = "{\"refresh_token\":\"" + rt + "\",\"uuid\":\"" + uuid + "\"}";
            HttpResult r = httpPost(REFRESH_URL, jsonBody, null);
            if (r.code < 200 || r.code >= 300) {
                log("CHECKIN_REFRESH_FAIL " + r.code + " " + truncate(r.body));
                return null;
            }

            // B26: JSON data wrapper fallback
            JSONObject root = new JSONObject(r.body);
            JSONObject data = root.optJSONObject("data");
            if (data == null) data = root;

            String at = data.optString("access_token", null);
            if (at == null || at.isEmpty()) {
                log("CHECKIN_REFRESH_FAIL no_access_token");
                return null;
            }

            // Rotate refresh_token if present
            String newRt = data.optString("refresh_token", null);
            if (newRt != null && !newRt.isEmpty()) {
                SharedPreferences.Editor ed = prefs.edit();
                ed.putString("simosa_refresh_token", newRt);
                String newUuid = data.optString("uuid", null);
                if (newUuid != null && !newUuid.isEmpty()) {
                    ed.putString("simosa_uuid", newUuid);
                }
                ed.apply();
            }

            log("CHECKIN_REFRESHED");
            sSession = "refreshed";
            return "Bearer " + at;
        } catch (Throwable t) {
            log("CHECKIN_REFRESH_FAIL " + t);
            return null;
        }
    }

    // --- helpers ---

    private static class HttpResult {
        final int code;
        final String body;
        HttpResult(int code, String body) { this.code = code; this.body = body; }
    }

    private static HttpResult httpPost(String urlStr, String body, String auth) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-app-build", "163");
            if (auth != null) conn.setRequestProperty("Authorization", auth);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            java.io.InputStream is = (code >= 200 && code < 300)
                ? conn.getInputStream() : conn.getErrorStream();

            StringBuilder sb = new StringBuilder();
            if (is != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
            }
            return new HttpResult(code, sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Object obj, String method) {
        try {
            return (T) obj.getClass().getMethod(method).invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    static int dayKey() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Karachi"));
        return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
    }
}
