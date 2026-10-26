package app.proxma.mytelenor;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

public final class QuizClaim {

    private static final String TAG = "PROXMA_MT";

    static String sResult = "n/a";

    private QuizClaim() {}

    public static String getResult() { return sResult; }

    public static boolean tryClaimQuiz(Context context) {
        sResult = "n/a";
        try {
            return quizInner(context);
        } catch (Throwable t) {
            Log.i(TAG, "QUIZ_ERR " + t);
            sResult = "error: " + t.getMessage();
            return false;
        }
    }

    private static boolean quizInner(Context context) throws Exception {
        String skip = AutoClaim.check(context, "quiz");
        if (skip != null) {
            Log.i(TAG, "QUIZ_SKIP " + skip);
            sResult = skip;
            return true;
        }

        String msisdn = StreakClaim.resolveMsisdn(context);
        if (msisdn == null || msisdn.isEmpty()) {
            sResult = "no msisdn";
            return true;
        }

        // SubmitQuestionBO has a (String msisdn, boolean markPlayed) ctor.
        // We can only claim a PENDING reward (user answered questions in-app).
        // We cannot answer quiz questions — we don't have the question/answer state.
        String claimUrl = StreakClaim.resolveUrl("getClaimAnswerAndWin",
            "api/qplayandwin/questions/answer/v3/claim/");
        String claimBody = "{\"msisdn\":\"" + msisdn + "\",\"markPlayed\":true}";
        Log.i(TAG, "QUIZ_CLAIM_ATTEMPT");
        StreakClaim.HttpResult claimResult = StreakClaim.httpPost(context, claimUrl, claimBody, msisdn);
        Log.i(TAG, "QUIZ_CLAIM " + claimResult.code + " " + truncate(claimResult.body));

        if (StreakClaim.succeeded(claimResult)) {
            AutoClaim.latch(context, "quiz");
            sResult = "claimed";
            try {
                JSONObject cr = new JSONObject(claimResult.body);
                JSONObject cData = cr.optJSONObject("data");
                if (cData != null) {
                    String allowance = cData.optString("allowance", "");
                    String rewardType = cData.optString("rewardType", "");
                    if (!allowance.isEmpty()) {
                        sResult += " " + allowance + " " + rewardType;
                    }
                }
            } catch (Throwable ignored) {}
            return true;
        }

        int status = StreakClaim.bodyStatus(claimResult);
        if (status == 400 || status == 409 || status == 404) {
            String bodyLower = StreakClaim.bodyLower(claimResult);
            if (bodyLower.contains("already") || bodyLower.contains("claimed")
                || bodyLower.contains("no pending") || bodyLower.contains("not found")) {
                AutoClaim.latch(context, "quiz");
                sResult = "no pending reward";
                return true;
            }
            sResult = "not answered yet";
            return true;
        }

        Log.i(TAG, "QUIZ_FAIL " + claimResult.code + " " + truncate(claimResult.body));
        sResult = "fail " + claimResult.code;
        return false;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
