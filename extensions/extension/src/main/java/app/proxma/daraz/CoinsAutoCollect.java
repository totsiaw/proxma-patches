package app.proxma.daraz;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.webkit.ValueCallback;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Daraz Coins is a web page the server hands the app (it is not built into the APK), and its
 * check-in request is made by the page's own script through the app's signed network bridge. So
 * instead of rebuilding that request, this waits for the Coins page to finish loading in the app's
 * web container and presses the page's own Check-in / Collect button. The page sends its own
 * request and shows its own result.
 *
 * The first time the user opens the Coins page (and it shows its Check-in button or its
 * already-collected state) the patch remembers its address. After that, once
 * a day when Daraz reaches its home screen, the patch opens that page itself, collects, and goes
 * back to home.
 */
public final class CoinsAutoCollect {

    private static final String TAG = "PROXMA_DARAZ";
    private static final String PREFS = "proxma_daraz";
    private static final String KEY_URL = "coins_url";
    private static final String KEY_DONE_DAY = "done_day";
    private static final String KEY_OPEN_DAY = "open_day";
    private static final String KEY_OPENS = "opens";
    private static final String KEY_HINT_SHOWN = "hint_shown";

    private static final String HOME_ACTIVITY = "com.lazada.android.maintab.MainTabActivity";
    private static final String DRAGON = "com.lazada.nav.Dragon";

    private static final int MAX_AUTO_OPENS_PER_DAY = 2;
    private static final long HOME_SETTLE_MS = 5_000L;
    private static final long AUTO_OPEN_WINDOW_MS = 60_000L;
    private static final long TICK_MS = 1_500L;
    private static final long FIND_TIMEOUT_MS = 30_000L;
    private static final long FIND_SETTLE_MS = 6_000L;
    private static final long RESULT_SETTLE_MS = 5_000L;
    private static final long RESULT_TIMEOUT_MS = 12_000L;
    private static final long CLOSE_DELAY_MS = 2_500L;

    private static final String KEY_MISSES = "misses";
    private static final String KEY_MISS_DAY = "miss_day";
    private static final int MAX_MISSES = 3;

    private static final Pattern COINS_URL = Pattern.compile(
        "(^|[/_\\-=.])(my-)?(daraz|laz)?coins?([/_\\-?&.#]|$)");
    private static final Pattern URL_PARTS = Pattern.compile(
        "^https?://([^/?#:]+)(:\\d+)?([^?#]*)(\\?[^#]*)?(#.*)?$");
    // Product, search, shop and cart pages can carry "coin" in their name or search words
    // (a coin purse, a search for "coins"); none of them is the Coins page.
    private static final Pattern NOT_COINS_PAGE = Pattern.compile(
        "^/(products?|catalog|shop|search|cart|checkout|orders?|wishlist|review|qa)(/|$)|-i\\d+(-s\\d+)?\\.html");
    private static final Pattern SEARCH_PARAM = Pattern.compile(
        "^(q|query|keyword|keywords|search|searchtext|k|from_search|spm|scm|clickTrackInfo)$",
        Pattern.CASE_INSENSITIVE);

    private static volatile boolean sRegistered = false;
    private static long sAutoOpenAt = 0L;
    private static boolean sAutoOpenPending = false;
    private static boolean sHomeResumed = false;
    private static final Map<Object, Session> SESSIONS = new WeakHashMap<>();

    private CoinsAutoCollect() {}

    // ---------------------------------------------------------------- entry points

    /**
     * Called at the very start of the app's Application.attachBaseContext, before super runs. This
     * is the earliest Java hook in the process, so it installs the signature spoof before Daraz's
     * SecurityGuard / mtop stack can initialise and cache the app's (patched) certificate — which
     * attachBaseContext is a common place for it to happen. Best-effort; never fatal.
     */
    public static void onAttachBaseContext(Context base) {
        try {
            if (base == null) return;
            SignatureSpoof.install(base);
            Log.i(TAG, "ATTACH spoof installed early");
        } catch (Throwable t) {
            Log.w(TAG, "ATTACH_ERR " + t);
        }
    }

    /** Called at the end of the app's Application.onCreate. */
    public static void onAppCreate(Application app) {
        try {
            if (app == null || sRegistered) return;
            sRegistered = true;
            // Make the app report its original signing certificate so Daraz's mtop request signing
            // keeps working and the session survives patching. Best-effort; never fatal.
            SignatureSpoof.install(app);
            app.registerActivityLifecycleCallbacks(new Callbacks());
            Log.i(TAG, "ENTRY registered");
        } catch (Throwable t) {
            Log.w(TAG, "ENTRY_ERR " + t);
        }
    }

    /** Called at the start of both WindVane web clients' onPageFinished(WebView, String). */
    public static void onPageFinished(Object webView, String url) {
        try {
            if (!(webView instanceof View) || url == null) return;
            if (!isCoinsUrl(url)) return;
            View view = (View) webView;
            Context context = view.getContext();
            if (context == null) return;
            Session running = SESSIONS.get(webView);
            if (running != null && !running.finished) {
                Log.i(TAG, "PAGE session already running");
                return;
            }
            boolean auto = sAutoOpenAt != 0L && SystemClock.uptimeMillis() - sAutoOpenAt < AUTO_OPEN_WINDOW_MS;
            if (auto) sAutoOpenAt = 0L;
            Log.i(TAG, "PAGE coins page candidate loaded, auto " + auto + " url " + url);
            Session session = new Session(view, url, auto);
            SESSIONS.put(webView, session);
            session.handler.postDelayed(session, TICK_MS);
        } catch (Throwable t) {
            Log.w(TAG, "PAGE_ERR " + t);
        }
    }

    // ---------------------------------------------------------------- daily auto-open

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity activity) {
            try {
                if (HOME_ACTIVITY.equals(activity.getClass().getName())) {
                    sHomeResumed = true;
                    onHomeResumed(activity);
                }
            } catch (Throwable t) {
                Log.w(TAG, "RESUME_ERR " + t);
            }
        }

        @Override public void onActivityPaused(Activity activity) {
            if (HOME_ACTIVITY.equals(activity.getClass().getName())) sHomeResumed = false;
        }

        @Override public void onActivityCreated(Activity activity, Bundle state) {}
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        @Override public void onActivityDestroyed(Activity activity) {}
    }

    private static void onHomeResumed(Activity activity) {
        SharedPreferences prefs = prefs(activity);
        String today = today();
        if (today.equals(prefs.getString(KEY_DONE_DAY, ""))) {
            Log.i(TAG, "HOME skipped, coins already collected " + today);
            return;
        }
        String url = prefs.getString(KEY_URL, "");
        if (url.isEmpty()) {
            Log.i(TAG, "HOME no coins url learned yet");
            if (!prefs.getBoolean(KEY_HINT_SHOWN, false)) {
                prefs.edit().putBoolean(KEY_HINT_SHOWN, true).apply();
                toast(activity, "Open the Daraz Coins page once. After that, coins are collected "
                    + "for you each day when you open Daraz.");
            }
            return;
        }
        int opens = today.equals(prefs.getString(KEY_OPEN_DAY, "")) ? prefs.getInt(KEY_OPENS, 0) : 0;
        if (opens >= MAX_AUTO_OPENS_PER_DAY) {
            Log.i(TAG, "HOME skipped, auto opens used up " + opens);
            return;
        }
        if (sAutoOpenPending) {
            Log.i(TAG, "HOME skipped, auto open already scheduled");
            return;
        }
        sAutoOpenPending = true;
        final WeakReference<Activity> ref = new WeakReference<>(activity);
        final String target = url;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                sAutoOpenPending = false;
                Activity home = ref.get();
                if (home == null || home.isFinishing() || !sHomeResumed) {
                    Log.i(TAG, "AUTO skipped, home no longer in front");
                    return;
                }
                autoOpen(home, target);
            }
        }, HOME_SETTLE_MS);
        Log.i(TAG, "HOME scheduled auto open, earlier opens today " + opens);
    }

    private static void autoOpen(Activity home, String url) {
        SharedPreferences prefs = prefs(home);
        String today = today();
        if (today.equals(prefs.getString(KEY_DONE_DAY, ""))) {
            Log.i(TAG, "AUTO skipped, collected meanwhile");
            return;
        }
        int opens = today.equals(prefs.getString(KEY_OPEN_DAY, "")) ? prefs.getInt(KEY_OPENS, 0) : 0;
        prefs.edit().putString(KEY_OPEN_DAY, today).putInt(KEY_OPENS, opens + 1).apply();
        sAutoOpenAt = SystemClock.uptimeMillis();
        if (openWithRouter(home, url)) {
            Log.i(TAG, "AUTO opened with app router " + url);
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setPackage(home.getPackageName());
            home.startActivity(intent);
            Log.i(TAG, "AUTO opened with view intent " + url);
        } catch (Throwable t) {
            sAutoOpenAt = 0L;
            Log.w(TAG, "AUTO_OPEN_ERR " + t);
        }
    }

    /** Daraz's own router: a public static (Context, String) factory on Dragon, then start(). */
    private static boolean openWithRouter(Activity home, String url) {
        try {
            Class<?> dragon = Class.forName(DRAGON, true, home.getClassLoader());
            for (Method m : dragon.getMethods()) {
                Class<?>[] types = m.getParameterTypes();
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != dragon) continue;
                if (types.length != 2 || types[0] != Context.class || types[1] != String.class) continue;
                Object nav = m.invoke(null, home, url);
                if (nav == null) continue;
                nav.getClass().getMethod("start").invoke(nav);
                return true;
            }
            Log.w(TAG, "ROUTER no (Context, String) factory on Dragon");
        } catch (Throwable t) {
            Log.w(TAG, "ROUTER_ERR " + t);
        }
        return false;
    }

    // ---------------------------------------------------------------- page session

    private enum Phase { FIND, RESULT }

    private static final class Session implements Runnable {
        final WeakReference<View> viewRef;
        final Context appContext;
        final String url;
        final boolean auto;
        final Handler handler = new Handler(Looper.getMainLooper());
        final long startedAt = SystemClock.uptimeMillis();
        Phase phase = Phase.FIND;
        long phaseAt = startedAt;
        boolean retapped = false;
        boolean waiting = false;
        boolean finished = false;
        String lastStatus = "";

        Session(View view, String url, boolean auto) {
            this.viewRef = new WeakReference<>(view);
            this.appContext = view.getContext().getApplicationContext();
            this.url = url;
            this.auto = auto;
        }

        /** Remember this page as the Coins page only once its content proved it is one. */
        void learn() {
            SharedPreferences prefs = prefs(appContext);
            SharedPreferences.Editor edit = prefs.edit().putInt(KEY_MISSES, 0);
            if (!url.equals(prefs.getString(KEY_URL, ""))) {
                edit.putString(KEY_URL, url);
                Log.i(TAG, "LEARN coins url " + url);
            }
            edit.apply();
        }

        /** An auto-opened page showed no Check-in button; forget the address after a few days of that. */
        void missed() {
            SharedPreferences prefs = prefs(appContext);
            String today = today();
            if (today.equals(prefs.getString(KEY_MISS_DAY, ""))) {
                Log.w(TAG, "MISS again today, not counted");
                return;
            }
            prefs.edit().putString(KEY_MISS_DAY, today).apply();
            int misses = prefs.getInt(KEY_MISSES, 0) + 1;
            if (misses >= MAX_MISSES) {
                prefs.edit().remove(KEY_URL).remove(KEY_HINT_SHOWN).putInt(KEY_MISSES, 0).apply();
                Log.w(TAG, "MISS forgot coins url after " + misses + " misses");
            } else {
                prefs.edit().putInt(KEY_MISSES, misses).apply();
                Log.w(TAG, "MISS auto open found no button, misses " + misses);
            }
        }

        boolean isLearnedPage() {
            return url.equals(prefs(appContext).getString(KEY_URL, ""));
        }

        @Override public void run() {
            try {
                tick();
            } catch (Throwable t) {
                Log.w(TAG, "TICK_ERR " + t);
                end("Couldn't finish collecting coins", true, false);
            }
        }

        void tick() {
            if (finished) return;
            View view = viewRef.get();
            if (view == null || !view.isAttachedToWindow()) {
                Log.i(TAG, "TICK page closed");
                end(null, false, false);
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (phase == Phase.FIND && now - phaseAt > FIND_TIMEOUT_MS) {
                Log.w(TAG, "FIND timed out, last status " + lastStatus);
                if (auto) missed();
                end(auto ? "Couldn't find the coins Check-in button" : null, auto, auto);
                return;
            }
            handler.postDelayed(this, TICK_MS);
            if (waiting) return;
            waiting = true;
            evaluate(view, script(phase == Phase.FIND ? 0 : 1), new ValueCallback<String>() {
                @Override public void onReceiveValue(String value) {
                    waiting = false;
                    try {
                        onResult(unquote(value));
                    } catch (Throwable t) {
                        Log.w(TAG, "RESULT_ERR " + t);
                    }
                }
            });
        }

        void onResult(String status) {
            if (finished) return;
            long now = SystemClock.uptimeMillis();
            if (!status.equals(lastStatus)) Log.i(TAG, "JS " + phase + " -> " + status);
            lastStatus = status;
            String kind = status.contains("|") ? status.substring(0, status.indexOf('|')) : status;
            String label = status.contains("|") ? status.substring(status.indexOf('|') + 1) : "";

            if (phase == Phase.FIND) {
                // A page that is still rendering can show last days' "checked in" marks or a header
                // sign-in link before today's button appears, so only trust those after a while.
                boolean settled = now - phaseAt >= FIND_SETTLE_MS;
                if (!settled && (kind.equals("done") || kind.equals("disabled") || kind.equals("login"))) {
                    return;
                }
                switch (kind) {
                    case "clicked":
                        Log.i(TAG, "FIND pressed button, label " + label);
                        learn();
                        phase = Phase.RESULT;
                        phaseAt = now;
                        return;
                    case "done":
                        Log.i(TAG, "FIND page says already collected, text " + label);
                        learn();
                        markDone();
                        end("Coins already collected today", true, true);
                        return;
                    case "disabled":
                        Log.i(TAG, "FIND button disabled, treating as collected, label " + label);
                        learn();
                        markDone();
                        end("Coins already collected today", true, true);
                        return;
                    case "login":
                        boolean known = auto || isLearnedPage();
                        Log.i(TAG, "FIND page asks to sign in, known coins page " + known + ", label " + label);
                        end(known ? "Sign in to Daraz first to collect coins" : null, known, known);
                        return;
                    default:
                        return; // "none": page still rendering, keep looking
                }
            }

            // Phase.RESULT
            if (kind.equals("done") || kind.equals("dismissed") || kind.equals("disabled")) {
                Log.i(TAG, "RESULT success, " + kind + " " + label);
                markDone();
                end("Daraz Coins collected", true, true);
                return;
            }
            if (kind.equals("gone") && now - phaseAt >= RESULT_SETTLE_MS) {
                Log.i(TAG, "RESULT button gone after press");
                markDone();
                end("Daraz Coins collected", true, true);
                return;
            }
            if (now - phaseAt < RESULT_TIMEOUT_MS) return;
            if (kind.equals("button") && !retapped) {
                retapped = true;
                Log.w(TAG, "RESULT button still there, pressing once more");
                phase = Phase.FIND;
                phaseAt = now;
                return;
            }
            Log.w(TAG, "RESULT no confirmation, last " + status);
            end("Coins check-in didn't confirm, will retry next time", true, true);
        }

        void markDone() {
            prefs(appContext).edit().putString(KEY_DONE_DAY, today()).apply();
        }

        void end(String message, boolean toast, boolean leave) {
            if (finished) return;
            finished = true;
            handler.removeCallbacks(this);
            Log.i(TAG, "END " + (message == null ? "quiet" : message) + ", auto " + auto);
            if (toast && message != null) toast(appContext, message);
            if (auto && leave) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        closePage();
                    }
                }, CLOSE_DELAY_MS);
            }
        }

        void closePage() {
            try {
                View view = viewRef.get();
                Activity activity = view == null ? null : activityOf(view.getContext());
                if (activity == null || activity.isFinishing()) {
                    Log.i(TAG, "LEAVE no page activity to close");
                    return;
                }
                if (HOME_ACTIVITY.equals(activity.getClass().getName())) {
                    Log.i(TAG, "LEAVE page lives in home, staying");
                    return;
                }
                activity.finish();
                Log.i(TAG, "LEAVE closed " + activity.getClass().getName());
            } catch (Throwable t) {
                Log.w(TAG, "LEAVE_ERR " + t);
            }
        }
    }

    // ---------------------------------------------------------------- page script

    /**
     * mode 0: find the Check-in / Collect button and press it.
     * mode 1: after pressing, look for the result.
     * Returns "kind|label" where kind is clicked, done, disabled, login, none, button, gone, dismissed.
     * Buttons that spend coins (redeem, vouchers, exchange) are never pressed.
     */
    private static String script(int mode) {
        return "(function(mode){try{"
            + "var STRONG=[/^(daily\\s*)?check[\\s-]?in(\\s*now)?(\\s*(to|&|and)\\s*(collect|earn|get)\\b.*)?$/i,"
            + "/^(collect|claim)\\s*(now\\s*)?\\+?\\s*[\\d,]*\\s*(daraz\\s*)?coins?(\\s*now|\\s*today)?$/i,"
            + "/^(tap|click)\\s*to\\s*(collect|claim|check[\\s-]?in)\\b.{0,20}$/i,"
            + "/^get\\s*\\+?\\s*[\\d,]*\\s*(daraz\\s*)?coins?(\\s*now|\\s*today)?$/i,"
            + "/^collect\\s*\\+?\\s*[\\d,]+$/i];"
            + "var WEAK=/^(collect|claim)(\\s*now)?$/i;"
            + "var SPEND=/redeem|spend|exchange|voucher|\\buse\\b|buy|follow|share|invite|watch|play|game|order|shop|store|off\\b|discount|%/i;"
            + "var DONE=/checked[\\s-]?in|already\\s*(checked|collected|claimed)|come\\s*back\\s*tomorrow|collected\\s*today|(check[\\s-]?in|collect|claim)\\s*(again\\s*)?tomorrow|^collected$|^claimed$/i;"
            + "var REWARD=/(\\+\\s*[\\d,]+\\s*coins?)|you('ve|\\s*have)?\\s*(got|earned|collected|received|claimed)|congrat|success/i;"
            + "var LOGIN=/^(log\\s*in|login|sign\\s*in|sign\\s*up|register)(\\s*now)?(\\s*(to|&|and)\\b.{0,30})?$/i;"
            + "var DISMISS=/^(ok|okay|got\\s*it|great|awesome|done|close|continue|nice)!?$/i;"
            + "function docs(d,out,depth){out.push(d);if(depth>3)return out;var fr=d.querySelectorAll('iframe');"
            + "for(var i=0;i<fr.length;i++){try{var cd=fr[i].contentDocument;if(cd&&cd.body)docs(cd,out,depth+1);}catch(e){}}return out;}"
            + "function vis(el){try{var r=el.getBoundingClientRect();if(r.width<2||r.height<2)return false;"
            + "var w=el.ownerDocument.defaultView;var s=w.getComputedStyle(el);"
            + "return s.visibility!=='hidden'&&s.display!=='none'&&parseFloat(s.opacity||'1')>0.05;}catch(e){return false;}}"
            + "function lab(el){return ((el.innerText||el.textContent||'')+'').replace(/\\s+/g,' ').trim();}"
            + "function off(el){var n=el;for(var i=0;i<4&&n;i++){if(n.disabled||n.getAttribute&&(n.getAttribute('aria-disabled')==='true'"
            + "||/(^|[\\s_-])disabled?([\\s_-]|$)/i.test(n.getAttribute('class')||'')))return true;n=n.parentElement;}return false;}"
            + "function ctxSafe(el){var n=el.parentElement;for(var i=0;i<4&&n;i++){var t=lab(n);if(t.length<160&&SPEND.test(t))return false;n=n.parentElement;}return true;}"
            + "function deepest(list){var best=null;for(var i=0;i<list.length;i++){var el=list[i],inner=false;"
            + "for(var j=0;j<list.length;j++){if(i!==j&&el.contains(list[j])){inner=true;break;}}if(!inner){best=el;break;}}return best||list[0];}"
            + "var strong=[],weak=[],done=null,login=null,reward=null,dismiss=null,ds=docs(document,[],0);"
            + "for(var d=0;d<ds.length;d++){var all=ds[d].body?ds[d].body.getElementsByTagName('*'):[];"
            + "for(var k=0;k<all.length;k++){var el=all[k];var tn=el.tagName;if(tn==='SCRIPT'||tn==='STYLE'||tn==='HTML'||tn==='BODY')continue;"
            + "var tc=el.textContent;if(!tc||tc.length>400)continue;"
            + "var t=lab(el);if(!t||t.length>60)continue;if(!vis(el))continue;"
            + "if(DONE.test(t)&&!done)done=t;"
            + "if(REWARD.test(t)&&!reward)reward=t;"
            + "if(LOGIN.test(t)&&!login)login=t;"
            + "if(DISMISS.test(t)&&!dismiss)dismiss=el;"
            + "if(t.length>40||SPEND.test(t))continue;"
            + "var hit=false;for(var s=0;s<STRONG.length;s++){if(STRONG[s].test(t)){hit=true;break;}}"
            + "if(hit)strong.push(el);else if(WEAK.test(t)&&ctxSafe(el))weak.push(el);}}"
            + "var pick=strong.length?deepest(strong):(weak.length?deepest(weak):null);"
            + "function press(el){var w=el.ownerDocument.defaultView;try{var r=el.getBoundingClientRect();var x=r.left+r.width/2,y=r.top+r.height/2;"
            + "['pointerdown','mousedown','pointerup','mouseup'].forEach(function(n){try{el.dispatchEvent(new w.MouseEvent(n,{bubbles:true,cancelable:true,clientX:x,clientY:y}));}catch(e){}});}catch(e){}"
            + "el.click();}"
            + "if(mode===0){"
            + "if(pick){var pl=lab(pick);if(off(pick))return 'disabled|'+pl;press(pick);return 'clicked|'+pl;}"
            + "if(done)return 'done|'+done;if(login)return 'login|'+login;"
            + "return 'none|'+strong.length+'/'+weak.length;}"
            + "if(reward&&dismiss){var dl=lab(dismiss);press(dismiss);return 'dismissed|'+reward+' / '+dl;}"
            + "if(pick)return off(pick)?'disabled|'+lab(pick):'button|'+lab(pick);"
            + "if(done)return 'done|'+done;"
            + "if(reward)return 'done|'+reward;"
            + "return 'gone|';"
            + "}catch(e){return 'error|'+e;}})(" + mode + ")";
    }

    // ---------------------------------------------------------------- helpers

    private static void evaluate(View view, String js, ValueCallback<String> callback) {
        try {
            Method m = view.getClass().getMethod("evaluateJavascript", String.class, ValueCallback.class);
            m.invoke(view, js, callback);
        } catch (Throwable t) {
            Log.w(TAG, "EVAL_ERR " + t);
            callback.onReceiveValue("\"error|eval\"");
        }
    }

    /**
     * A Daraz page whose path, fragment or a non-search query value names coins. Product, search,
     * shop and cart pages are never candidates, and search words are ignored, so a search for
     * "coins" or a "coin purse" product can't pass. A candidate only becomes the remembered Coins
     * page once the page itself shows the Check-in button or the already-collected state.
     */
    static boolean isCoinsUrl(String url) {
        String lower = url.toLowerCase(Locale.US);
        Matcher parts = URL_PARTS.matcher(lower);
        if (!parts.matches()) return false;
        String host = parts.group(1);
        if (!(host.contains("daraz") || host.contains("lazada"))) return false;
        if (decode(lower).contains("login") || decode(lower).contains("signin")) return false;
        String path = decode(parts.group(3) == null ? "" : parts.group(3));
        if (NOT_COINS_PAGE.matcher(path).find()) return false;
        StringBuilder text = new StringBuilder(path);
        String query = parts.group(4);
        if (query != null && query.length() > 1) {
            for (String pair : query.substring(1).split("&")) {
                int eq = pair.indexOf('=');
                String name = decode(eq < 0 ? pair : pair.substring(0, eq));
                if (SEARCH_PARAM.matcher(name).matches()) continue;
                text.append('&').append(name);
                if (eq >= 0) text.append('=').append(decode(pair.substring(eq + 1)));
            }
        }
        if (parts.group(5) != null) text.append(decode(parts.group(5)));
        return COINS_URL.matcher(text).find();
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    private static String unquote(String value) {
        if (value == null || value.equals("null")) return "none|null";
        String v = value;
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return v;
    }

    private static Activity activityOf(Context context) {
        Context c = context;
        for (int i = 0; i < 8 && c != null; i++) {
            if (c instanceof Activity) return (Activity) c;
            if (!(c instanceof ContextWrapper)) return null;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static void toast(Context context, String message) {
        try {
            Toast.makeText(context.getApplicationContext(), "Daraz patch: " + message, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.w(TAG, "TOAST_ERR " + t);
        }
    }
}
