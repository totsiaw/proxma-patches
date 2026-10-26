package app.proxma.mytelenor;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;

public final class StreakClaim {

    private static final String TAG = "PROXMA_MT";
    private static final String PREFS = "proxma_mytelenor";
    private static final int TIMEOUT_MS = 15000;

    static String sResult = "n/a";

    private StreakClaim() {}

    public static String getResult() { return sResult; }

    public static boolean tryClaimStreak(Context context) {
        sResult = "n/a";
        try {
            return claimInner(context);
        } catch (Throwable t) {
            Log.i(TAG, "STREAK_ERR " + t);
            sResult = "error: " + t.getMessage();
            return false;
        }
    }

    private static boolean claimInner(Context context) throws Exception {
        String skip = AutoClaim.check(context, "streak");
        if (skip != null) {
            Log.i(TAG, "STREAK_SKIP " + skip);
            sResult = skip;
            return true;
        }

        String msisdn = resolveMsisdn(context);
        if (msisdn == null || msisdn.isEmpty()) {
            Log.i(TAG, "STREAK_SKIP no-msisdn");
            sResult = "no msisdn";
            return true;
        }

        // Step 1: Get streak info
        String streakInfoUrl = resolveUrl("getDailyRewardList", "api/streak/info/" + msisdn + "/v2", msisdn);
        Log.i(TAG, "STREAK_INFO_START url=" + truncate(streakInfoUrl));
        HttpResult infoResult = httpGet(context, streakInfoUrl, msisdn);
        if (infoResult.code < 200 || infoResult.code >= 300) {
            Log.i(TAG, "STREAK_INFO_FAIL " + infoResult.code + " " + truncate(infoResult.body));
            sResult = "info fail " + infoResult.code;
            return false;
        }
        Log.i(TAG, "STREAK_INFO " + truncate(infoResult.body));

        // Step 2: Claim streak — body mirrors PostDailyRewardDTO: {"msisdn":"03xxx"}
        String claimUrl = resolveUrl("getClaimStreak", "api/streak/claim");
        String body = "{\"msisdn\":\"" + msisdn + "\"}";
        Log.i(TAG, "STREAK_CLAIM_START");
        HttpResult claimResult = httpPost(context, claimUrl, body, msisdn);

        if (claimResult.code >= 200 && claimResult.code < 300) {
            AutoClaim.latch(context, "streak");
            Log.i(TAG, "STREAK_CLAIMED " + claimResult.code + " " + truncate(claimResult.body));
            sResult = "claimed";
            return true;
        }

        if (claimResult.code == 409 || claimResult.code == 400) {
            String bodyLower = claimResult.body != null ? claimResult.body.toLowerCase() : "";
            if (bodyLower.contains("already") || bodyLower.contains("claimed")
                || bodyLower.contains("not eligible") || bodyLower.contains("not available")) {
                AutoClaim.latch(context, "streak");
                Log.i(TAG, "STREAK_ALREADY " + claimResult.code + " " + truncate(claimResult.body));
                sResult = "already claimed";
                return true;
            }
        }

        Log.i(TAG, "STREAK_FAIL " + claimResult.code + " " + truncate(claimResult.body));
        sResult = "fail " + claimResult.code;
        return false;
    }

    // --- MSISDN resolution: mirrors ConnectUserInfo.d().e() with persistence ---

    static String resolveMsisdn(Context context) {
        // Primary: ConnectUserInfo singleton (warm start)
        try {
            Class<?> cuiClass = Class.forName("com.telenor.pakistan.mytelenor.models.ConnectSDKData.ConnectUserInfo");
            // ConnectUserInfo.d() returns the singleton (field 'l' in smali, f28409l in JADX)
            Object singleton = cuiClass.getMethod("d").invoke(null);
            if (singleton != null) {
                // ConnectUserInfo.e() returns the Msisdn field
                String ms = (String) cuiClass.getMethod("e").invoke(singleton);
                if (ms != null && !ms.isEmpty()) {
                    persistMsisdn(context, ms);
                    return ms;
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "MSISDN_CUI_ERR " + t.getClass().getSimpleName());
        }

        // Fallback: app's SharedPreferences
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                context.getPackageName(), 0);
            String ms = prefs.getString("msisdn", null);
            if (ms != null && !ms.isEmpty()) {
                persistMsisdn(context, ms);
                return ms;
            }
            ms = prefs.getString("login_msisdn", null);
            if (ms != null && !ms.isEmpty()) {
                persistMsisdn(context, ms);
                return ms;
            }
        } catch (Throwable t) {
            Log.i(TAG, "MSISDN_PREFS_ERR " + t.getClass().getSimpleName());
        }

        // Last resort: our own persisted value
        return storedMsisdn(context);
    }

    private static void persistMsisdn(Context context, String msisdn) {
        // Persist to both our key AND AccountScope's keyed store so latch checks work
        AccountScope.saveMsisdn(context, msisdn);
        try {
            context.getSharedPreferences(PREFS, 0).edit()
                .putString("proxma_msisdn", msisdn).apply();
        } catch (Throwable ignored) {}
    }

    private static String storedMsisdn(Context context) {
        // Check AccountScope first (keyed by hash), then our flat key
        String fromAS = AccountScope.msisdn(context);
        if (fromAS != null && !fromAS.isEmpty()) return fromAS;
        try {
            String ms = context.getSharedPreferences(PREFS, 0)
                .getString("proxma_msisdn", null);
            if (ms != null && !ms.isEmpty()) {
                Log.i(TAG, "MSISDN_COLD_START using stored");
                return ms;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // --- HTTP with full header pipeline mirroring x40.C23362b interceptor ---

    static final class HttpResult {
        final int code;
        final String body;
        HttpResult(int code, String body) { this.code = code; this.body = body; }
    }

    static HttpResult httpGet(Context context, String urlStr, String msisdn) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            addHeaders(conn, context, null, msisdn);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            return readResponse(conn);
        } finally {
            conn.disconnect();
        }
    }

    static HttpResult httpPost(Context context, String urlStr, String body, String msisdn) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            addHeaders(conn, context, body, msisdn);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            OutputStream out = conn.getOutputStream();
            out.write(body.getBytes("UTF-8"));
            out.flush();
            out.close();
            return readResponse(conn);
        } finally {
            conn.disconnect();
        }
    }

    private static HttpResult readResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        java.io.InputStream stream = (code >= 200 && code < 300)
            ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder text = new StringBuilder();
        if (stream != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
            reader.close();
        }
        return new HttpResult(code, text.toString());
    }

    private static void addHeaders(HttpURLConnection conn, Context context, String body, String msisdn) {
        // 1. Static headers — mirrors m79993f()
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-request-os", "Android");
        conn.setRequestProperty("X-app-version", "4.2.63");
        conn.setRequestProperty("X-app-build-version", "4.2.63");
        conn.setRequestProperty("X-os-version", Build.VERSION.RELEASE);
        conn.setRequestProperty("X-request-channel", "DCE-APP-ANDROID");
        conn.setRequestProperty("X-app-language", "EN");

        // 2. Dynamic headers from NetworkHeader singleton — mirrors m79994b()
        SharedPreferences sp = context.getSharedPreferences(PREFS, 0);
        String pricePlan = "";
        String segment = "";
        String segType = "";
        String pkgPlan = "";
        String planType = "";
        String customerCode = "";

        try {
            // NetworkHeader.c() returns singleton (never null — creates new if absent)
            Class<?> nhClass = Class.forName("com.telenor.pakistan.mytelenor.BaseApp.NetworkHeader");
            Object nh = nhClass.getMethod("c").invoke(null);
            if (nh != null) {
                // Verified mapping (smali field → getter → interceptor usage):
                // g() → field a → pricePlan (X-user-price-plan)
                // h() → field b → segment (X-user-segment)
                // j() → field l → segmentType (x-user-segment-type)
                // e() → field m → packagePlan (x-user-package-plan + signature 3rd arg)
                // f() → field n → planType (x-user-plan-type)
                // b() → field o → customerCode (signature 2nd arg)
                String pp = invoke(nh, "g");
                String seg = invoke(nh, "h");
                String st = invoke(nh, "j");
                String pkp = invoke(nh, "e");
                String pt = invoke(nh, "f");
                String cc = invoke(nh, "b");

                SharedPreferences.Editor ed = sp.edit();
                if (pp != null && !pp.isEmpty()) { ed.putString("nh_pp", pp); pricePlan = pp; }
                if (seg != null && !seg.isEmpty()) { ed.putString("nh_seg", seg); segment = seg; }
                if (st != null && !st.isEmpty()) { ed.putString("nh_segt", st); segType = st; }
                if (pkp != null && !pkp.isEmpty()) { ed.putString("nh_pkgp", pkp); pkgPlan = pkp; }
                if (pt != null && !pt.isEmpty()) { ed.putString("nh_plt", pt); planType = pt; }
                if (cc != null && !cc.isEmpty()) { ed.putString("nh_cc", cc); customerCode = cc; }
                ed.apply();

                // Fill blanks from persisted
                if (pricePlan.isEmpty()) pricePlan = sp.getString("nh_pp", "");
                if (segment.isEmpty()) segment = sp.getString("nh_seg", "");
                if (segType.isEmpty()) segType = sp.getString("nh_segt", "");
                if (planType.isEmpty()) planType = sp.getString("nh_plt", "");
                if (pkgPlan.isEmpty()) pkgPlan = sp.getString("nh_pkgp", "");
                if (customerCode.isEmpty()) customerCode = sp.getString("nh_cc", "");
            }
        } catch (Throwable t) {
            Log.i(TAG, "HDR_NH_ERR " + t.getClass().getSimpleName());
            // Cold-start fallback
            pricePlan = sp.getString("nh_pp", "");
            segment = sp.getString("nh_seg", "");
            segType = sp.getString("nh_segt", "");
            planType = sp.getString("nh_plt", "");
            pkgPlan = sp.getString("nh_pkgp", "");
            customerCode = sp.getString("nh_cc", "");
        }

        setIfNonEmpty(conn, "X-user-price-plan", pricePlan);
        setIfNonEmpty(conn, "X-user-segment", segment);
        setIfNonEmpty(conn, "x-user-segment-type", segType);
        setIfNonEmpty(conn, "x-user-package-plan", pkgPlan);
        setIfNonEmpty(conn, "x-user-plan-type", planType);

        // 3. Device info
        conn.setRequestProperty("X-device-make", Build.MANUFACTURER);
        conn.setRequestProperty("X-device-model", Build.MODEL);

        try {
            Class<?> p0Class = Class.forName("com.telenor.pakistan.mytelenor.Utils.p0");
            String resolution = (String) p0Class.getMethod("P", android.content.Context.class).invoke(null, context);
            if (resolution != null && !resolution.isEmpty()) {
                conn.setRequestProperty("X-screen-resolution", resolution);
            }
        } catch (Throwable ignored) {}

        // Device ID — Utils.v.f() with persistence
        String deviceId = "";
        try {
            Class<?> vClass = Class.forName("com.telenor.pakistan.mytelenor.Utils.v");
            deviceId = (String) vClass.getMethod("f").invoke(null);
        } catch (Throwable ignored) {}
        if (deviceId != null && !deviceId.isEmpty()) {
            sp.edit().putString("device_id", deviceId).apply();
        } else {
            deviceId = sp.getString("device_id", "");
        }
        setIfNonEmpty(conn, "X-device-id", deviceId);

        // 4. Xenon tokens — Utils.v.h()/i() with persistence; skip v1 when equal to v2
        String xenon = null;
        String xenonV2 = null;
        try {
            Class<?> vClass = Class.forName("com.telenor.pakistan.mytelenor.Utils.v");
            xenon = (String) vClass.getMethod("h").invoke(null);
            xenonV2 = (String) vClass.getMethod("i").invoke(null);
        } catch (Throwable ignored) {}
        if (xenon != null && !xenon.isEmpty()) {
            sp.edit().putString("xenon", xenon)
                .putString("xenon_v2", xenonV2 != null ? xenonV2 : "").apply();
        } else {
            xenon = sp.getString("xenon", null);
            xenonV2 = sp.getString("xenon_v2", null);
        }
        boolean same = (xenon != null && xenon.equals(xenonV2));
        if (!same && xenon != null && !xenon.isEmpty()) {
            conn.setRequestProperty("X-XENON-TOKEN", xenon);
        }
        if (xenonV2 != null && !xenonV2.isEmpty()) {
            conn.setRequestProperty("X-XENON-TOKEN-V2", xenonV2);
        }

        // 5. Signature — NdkHeader.d(body, customerCode, packagePlan)
        // Real smali: field 'a' on NdkHeader (static final singleton)
        // d(String, String, String) -> SHA256(payload + customerCode + packagePlan + headerKey)
        // The interceptor always sends signature (f81246d = true); body is null for GET
        try {
            Object ndkInstance = getNdkSingleton();
            if (ndkInstance != null) {
                // For GET requests, interceptor passes null body. NdkHeader.d() concatenates
                // null as the literal string "null" (Kotlin null.toString())
                String sigBody = body;
                if (sigBody == null) sigBody = "null";
                String sig = (String) ndkInstance.getClass()
                    .getMethod("d", String.class, String.class, String.class)
                    .invoke(ndkInstance, sigBody, customerCode, pkgPlan);
                if (sig != null && !sig.isEmpty()) {
                    conn.setRequestProperty("signature", sig);
                    Log.i(TAG, "SIG_OK len=" + sig.length());
                } else {
                    Log.i(TAG, "SIG_SKIP empty");
                }
            } else {
                Log.i(TAG, "SIG_SKIP no-instance");
            }
        } catch (Throwable t) {
            Log.i(TAG, "SIG_ERR " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static void setIfNonEmpty(HttpURLConnection conn, String header, String value) {
        if (value != null && !value.isEmpty()) {
            conn.setRequestProperty(header, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Object target, String method) {
        try {
            return (T) target.getClass().getMethod(method).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    // --- NdkHeader singleton resolution ---
    // Real smali field: public static final a:Lcom/.../NdkHeader;
    // It's set in <clinit>, always present once the class is loaded.
    // libmytelenor.so is loaded in DaggerApplication.onCreate() so it's available
    // even in WorkManager cold starts (same process).

    private static Object sNdk;
    private static boolean sNdkResolved;

    private static Object getNdkSingleton() {
        if (sNdkResolved) return sNdk;
        sNdkResolved = true;
        try {
            Class<?> ndkClass = Class.forName("com.telenor.pakistan.mytelenor.newstructure.utils.NdkHeader");
            // Real field name is "a" (confirmed in smali)
            sNdk = ndkClass.getField("a").get(null);
        } catch (Throwable t) {
            Log.i(TAG, "NDK_FIELD_ERR " + t.getClass().getSimpleName());
            // Fallback: scan static fields
            try {
                Class<?> ndkClass = Class.forName("com.telenor.pakistan.mytelenor.newstructure.utils.NdkHeader");
                for (Field f : ndkClass.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        Object obj = f.get(null);
                        if (obj != null && ndkClass.isInstance(obj)) {
                            sNdk = obj;
                            break;
                        }
                    }
                }
            } catch (Throwable t2) {
                Log.i(TAG, "NDK_SCAN_ERR " + t2.getClass().getSimpleName());
            }
        }
        return sNdk;
    }

    // --- URL resolution via native APIEndPoints singleton ---

    static String resolveUrl(String nativeMethodName, String fallbackPath) {
        Object endpoints = getEndpointsSingleton();
        if (endpoints != null) {
            try {
                String url = (String) endpoints.getClass()
                    .getMethod(nativeMethodName).invoke(endpoints);
                if (url != null && !url.isEmpty()) return url;
            } catch (Throwable t) {
                Log.i(TAG, "URL_" + nativeMethodName + "_ERR " + t.getClass().getSimpleName());
            }
        }
        String base = getBaseUrl();
        if (base == null) base = "https://galvorn.telenor.com.pk/";
        return base + fallbackPath;
    }

    static String resolveUrl(String nativeMethodName, String fallbackPath, String arg) {
        Object endpoints = getEndpointsSingleton();
        if (endpoints != null) {
            try {
                String url = (String) endpoints.getClass()
                    .getMethod(nativeMethodName, String.class).invoke(endpoints, arg);
                if (url != null && !url.isEmpty()) return url;
            } catch (Throwable t) {
                Log.i(TAG, "URL_" + nativeMethodName + "_ERR " + t.getClass().getSimpleName());
            }
        }
        String base = getBaseUrl();
        if (base == null) base = "https://galvorn.telenor.com.pk/";
        return base + fallbackPath;
    }

    static String getBaseUrl() {
        Object endpoints = getEndpointsSingleton();
        if (endpoints != null) {
            try {
                return (String) endpoints.getClass().getMethod("getBaseUrl").invoke(endpoints);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object sEndpoints;
    private static boolean sEndpointsResolved;

    private static Object getEndpointsSingleton() {
        if (sEndpointsResolved) return sEndpoints;
        sEndpointsResolved = true;
        try {
            Class<?> cls = Class.forName(
                "com.telenor.pakistan.mytelenor.newstructure.utils.Constants$APIEndPoints");
            // Real field name is "a" (confirmed in smali)
            sEndpoints = cls.getField("a").get(null);
        } catch (Throwable t) {
            Log.i(TAG, "ENDPOINTS_FIELD_ERR " + t.getClass().getSimpleName());
            // Fallback: scan static fields
            try {
                Class<?> cls = Class.forName(
                    "com.telenor.pakistan.mytelenor.newstructure.utils.Constants$APIEndPoints");
                for (Field f : cls.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        Object obj = f.get(null);
                        if (obj != null && cls.isInstance(obj)) {
                            sEndpoints = obj;
                            break;
                        }
                    }
                }
            } catch (Throwable t2) {
                Log.i(TAG, "ENDPOINTS_SCAN_ERR " + t2.getClass().getSimpleName());
            }
        }
        return sEndpoints;
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() > 200 ? value.substring(0, 200) : value;
    }
}
