package app.proxma.simosa;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Claims milestone level rewards (day 10/20/30 etc.) after the daily check-in.
 *
 * Flow:
 * 1. GET /checkin/state → current_streak, progress.current_level
 * 2. Compute if streak just crossed a level boundary
 * 3. GET /levels → reward details for that level
 * 4. POST to Jazz MSA gateway gamification/claimCheckIn with reward params
 */
public final class MilestoneClaim {

    private static final String TAG = "PROXMA_CLAIM";
    private static final String PREFS_NAME = "proxma_claim";
    private static final String STATE_URL = "https://api-prod-dailycheckin.simosa.app/checkin/state";
    private static final String LEVELS_URL = "https://api-prod-dailycheckin.simosa.app/levels";
    private static final String CLAIM_URL = "https://selfcare-msa-prod.jazz.com.pk/gamification/claimCheckIn";
    private static final int TIMEOUT_MS = 15000;

    static String sResult = "n/a";

    private MilestoneClaim() {}

    public static String getResult() { return sResult; }

    /**
     * Attempt to claim a milestone reward if one just became available.
     * Call AFTER the daily check-in succeeds.
     *
     * @param bearer Simosa bearer token (with "Bearer " prefix)
     */
    public static void tryClaimMilestone(Context context, String bearer) {
        sResult = "n/a";
        try {
            tryClaimInner(context, bearer);
        } catch (Throwable t) {
            log("MILESTONE_ERR " + t);
            sResult = "error: " + t.getMessage();
        }
    }

    private static void tryClaimInner(Context context, String bearer) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);

        // 1. Get current state
        HttpResult stateRes = httpGet(STATE_URL, bearer);
        if (stateRes.code < 200 || stateRes.code >= 300) {
            log("MILESTONE_STATE_FAIL " + stateRes.code);
            sResult = "state fail " + stateRes.code;
            return;
        }

        JSONObject stateRoot = new JSONObject(stateRes.body);
        if (!stateRoot.optBoolean("success", false)) {
            log("MILESTONE_STATE_ERR " + stateRes.body);
            sResult = "state error";
            return;
        }

        JSONObject data = stateRoot.getJSONObject("data");
        int streak = data.optInt("current_streak", 0);
        JSONObject progress = data.optJSONObject("progress");
        int currentLevel = progress != null ? progress.optInt("current_level", 0) : 0;

        // Check if we already claimed this level
        int lastClaimedLevel = prefs.getInt("milestone_level", 0);
        if (currentLevel <= lastClaimedLevel) {
            log("MILESTONE_ALREADY level=" + currentLevel + " lastClaimed=" + lastClaimedLevel);
            sResult = "already L" + currentLevel;
            return;
        }

        // A new level was reached! Get its reward details from /levels
        log("MILESTONE_NEW level=" + currentLevel + " streak=" + streak);

        HttpResult levelsRes = httpGet(LEVELS_URL, bearer);
        if (levelsRes.code < 200 || levelsRes.code >= 300) {
            log("MILESTONE_LEVELS_FAIL " + levelsRes.code);
            sResult = "levels fail " + levelsRes.code;
            return;
        }

        JSONObject levelsRoot = new JSONObject(levelsRes.body);
        JSONArray levels = levelsRoot.optJSONArray("data");
        if (levels == null) {
            log("MILESTONE_LEVELS_EMPTY");
            sResult = "no levels data";
            return;
        }

        // Find the level that matches currentLevel
        JSONObject targetLevel = null;
        for (int i = 0; i < levels.length(); i++) {
            JSONObject l = levels.getJSONObject(i);
            if (l.optInt("level", -1) == currentLevel) {
                targetLevel = l;
                break;
            }
        }

        if (targetLevel == null) {
            log("MILESTONE_LEVEL_NOT_FOUND level=" + currentLevel);
            sResult = "level " + currentLevel + " not found";
            return;
        }

        // Extract rewards
        JSONArray rewards = targetLevel.optJSONArray("rewards");
        if (rewards == null || rewards.length() == 0) {
            log("MILESTONE_NO_REWARDS level=" + currentLevel);
            sResult = "no rewards for L" + currentLevel;
            return;
        }

        // Parse rewards into claimData/claimMints
        String claimData = "";
        String claimMints = "";
        String type = "";
        String dataExpiry = "";
        String mintExpiry = "";

        for (int i = 0; i < rewards.length(); i++) {
            JSONObject reward = rewards.getJSONObject(i);
            String rType = reward.optString("type", "");
            double rValue = reward.optDouble("value", 0);
            int rValidity = reward.optInt("validity_days", 1);

            if (rType.contains("mins") || rType.contains("on_net_minutes")) {
                claimMints = String.valueOf(rValue);
                mintExpiry = String.valueOf(rValidity);
            } else if (rType.contains("mb") || rType.contains("gb")) {
                claimData = String.valueOf(rValue);
                type = rType;
                dataExpiry = String.valueOf(rValidity);
            }
        }

        log("MILESTONE_CLAIM level=" + currentLevel + " data=" + claimData
            + " type=" + type + " mints=" + claimMints);

        // Build the claim request body
        JSONObject claimBody = new JSONObject();
        claimBody.put("claimMints", claimMints);
        claimBody.put("claimData", claimData);
        claimBody.put("type", type);
        claimBody.put("dataExpiry", dataExpiry);
        claimBody.put("mintExpiry", mintExpiry);

        // POST to Jazz MSA gateway
        HttpResult claimRes = httpPostMsa(CLAIM_URL, claimBody.toString());

        if (claimRes.code >= 200 && claimRes.code < 300) {
            log("MILESTONE_CLAIMED level=" + currentLevel + " " + truncate(claimRes.body));
            prefs.edit().putInt("milestone_level", currentLevel).apply();
            sResult = "claimed L" + currentLevel;
        } else {
            log("MILESTONE_CLAIM_FAIL " + claimRes.code + " " + truncate(claimRes.body));
            sResult = "claim fail " + claimRes.code;
        }
    }

    // --- HTTP helpers ---

    private static class HttpResult {
        final int code;
        final String body;
        HttpResult(int c, String b) { code = c; body = b; }
    }

    private static HttpResult httpGet(String urlStr, String bearer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("x-app-build", "163");
            if (bearer != null) conn.setRequestProperty("Authorization", bearer);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            int code = conn.getResponseCode();
            java.io.InputStream is = (code >= 200 && code < 300)
                ? conn.getInputStream() : conn.getErrorStream();
            return new HttpResult(code, readStream(is));
        } finally {
            conn.disconnect();
        }
    }

    private static HttpResult httpPostMsa(String urlStr, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Android");
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
            return new HttpResult(code, readStream(is));
        } finally {
            conn.disconnect();
        }
    }

    private static String readStream(java.io.InputStream is) {
        if (is == null) return "";
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
    }
}
