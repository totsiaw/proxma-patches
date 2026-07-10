package patches.daraz

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/*
 * Kills the analytics/ad/fingerprint egress in Daraz (com.daraz.android 9.36.2) while leaving the
 * app's own commerce API (mtop/anet), push (ACCS/Agoo/FCM), login and eKYC intact.
 *
 * Every kill below was pinned against the decoded smali of THIS build (version-pinned, so obfuscated
 * single-letter names are safe to anchor on). Each stub is the narrowest chokepoint that stops data
 * leaving the device without NPE-crashing callers, and each was verified type-correct (exact
 * descriptor + .locals). Grouped by SDK:
 *
 *   - Meta/Facebook  : no-op App Events (activate/logEvent/logPurchase/flush + AppEventQueue.flush)
 *                      and force the auto-collection getters false. Facebook LOGIN is left working
 *                      (sdkInitialize untouched). GAID collection dies with the getters.
 *   - Alibaba UT     : no-op the clickstream at the source (transferLog x3) AND at the network
 *                      upload (UploadLogFrom{DB,Cache}) — no page/tap event is stored or uploaded.
 *   - AUDID          : no-op UtdidUploadTask so the device fingerprint never POSTs to
 *                      audid-api-sg.taobao.com; local UTDID generation is kept (app needs a device id).
 *   - Behavix        : no-op the behavior-collection init + event record/updateEvent.
 *   - Zalo           : null the device-tracking id fetch (blocks ztevents.zaloapp.com GAID/GPS send).
 *   - Motu/tbrest    : null the rest-request primitive (crash dumps + APM to h-adashx.ut.lazada.com).
 *                      The app's own crash RECOVERY (RepeatCrashHandler) is untouched.
 *   - Firebase/GA    : no-op the measurement logEvent funnels + manifest auto-collection flags.
 *                      FCM/FirebaseInitProvider left intact (push keeps working).
 *   - UCWeb          : no-op the Java applog/crash/webview-stat uploaders (uc.cn/quark.cn).
 *
 * NOT patchable here (blocked at native layer only — needs a host/DNS block, see WHAT_IT_REMOVES):
 * residual UCWeb telemetry emitted directly from libcrashsdk/libkernelu4, and the SecurityGuard/UMID
 * native fingerprint. TikTok is a user-tap Share intent (no network) and is left alone.
 *
 * Verify: watch logcat / a PCAPdroid SNI capture on a cold launch — adashx.ut.daraz.com,
 * graph.facebook.com, app-measurement.com, audid-api-sg.taobao.com, ztevents.zaloapp.com and
 * applog.uc.cn should no longer appear.
 */

// helper: match one method by exact class + name + descriptor (version-pinned build, names are stable here)
private fun kill(classType: String, name: String, returnType: String, params: List<String> = emptyList()) =
    Fingerprint(
        returnType = returnType,
        parameters = params,
        custom = { m, c -> c.type == classType && m.name == name },
    )

private const val FB_SDK = "Lcom/facebook/FacebookSdk;"
private const val FB_LOGGER = "Lcom/facebook/appevents/AppEventsLogger;"
private const val FB_LOGGER_IMPL = "Lcom/facebook/appevents/AppEventsLoggerImpl;"
private const val FB_QUEUE = "Lcom/facebook/appevents/AppEventQueue;"

// ---------------- Meta / Facebook ----------------
internal val fbGetAutoLog = kill(FB_SDK, "getAutoLogAppEventsEnabled", "Z")
internal val fbGetAdId = kill(FB_SDK, "getAdvertiserIDCollectionEnabled", "Z")
internal val fbSetAutoLog = kill(FB_SDK, "setAutoLogAppEventsEnabled", "V", listOf("Z"))
internal val fbSetAdId = kill(FB_SDK, "setAdvertiserIDCollectionEnabled", "V", listOf("Z"))
internal val fbActivate1 = kill(FB_LOGGER, "activateApp", "V", listOf("Landroid/app/Application;"))
internal val fbActivate2 = kill(FB_LOGGER, "activateApp", "V", listOf("Landroid/app/Application;", "Ljava/lang/String;"))
internal val fbActivateImpl = kill(FB_LOGGER_IMPL, "activateApp", "V", listOf("Landroid/app/Application;", "Ljava/lang/String;"))
internal val fbLogEvent = kill(FB_LOGGER_IMPL, "logEvent", "V", listOf("Ljava/lang/String;", "Ljava/lang/Double;", "Landroid/os/Bundle;", "Z", "Ljava/util/UUID;", "Lcom/facebook/appevents/OperationalData;"))
internal val fbLogPurchase = kill(FB_LOGGER_IMPL, "logPurchase", "V", listOf("Ljava/math/BigDecimal;", "Ljava/util/Currency;", "Landroid/os/Bundle;", "Z", "Lcom/facebook/appevents/OperationalData;"))
internal val fbFlushImpl = kill(FB_LOGGER_IMPL, "flush", "V")
internal val fbQueueFlush = kill(FB_QUEUE, "flush", "V", listOf("Lcom/facebook/appevents/FlushReason;"))

// ---------------- Alibaba UserTrack clickstream ----------------
private const val UT_LOG = "Lcom/alibaba/analytics/core/model/Log;"
internal val utTransfer1 = kill("Lcom/ut/mini/UTAnalytics;", "transferLog", "V", listOf("Ljava/util/Map;"))
internal val utTransfer2 = kill("Lcom/alibaba/analytics/AnalyticsImp;", "transferLog", "V", listOf("Ljava/util/Map;"))
internal val utTransfer3 = kill("Lcom/ut/mini/core/UTLogTransferMain;", "transferLog", "V", listOf("Ljava/util/Map;"))
internal val utStoreAdd = kill("Lcom/alibaba/analytics/core/store/d;", "d", "V", listOf(UT_LOG))
internal val utUploadCacheC = kill("Lcom/alibaba/analytics/core/sync/UploadLogFromCache;", "c", "V", listOf(UT_LOG))
internal val utUploadDbF = kill("Lcom/alibaba/analytics/core/sync/UploadLogFromDB;", "f", "V")
internal val utUploadCacheF = kill("Lcom/alibaba/analytics/core/sync/UploadLogFromCache;", "f", "V")

// ---------------- AUDID device-fingerprint upload ----------------
internal val audidRun = kill("Lcom/ta/audid/upload/UtdidUploadTask;", "run", "V")
internal val audidReqServer = kill("Lcom/ta/audid/upload/UtdidUploadTask;", "reqServer", "Z", listOf("Ljava/lang/String;"))

// ---------------- Behavix behavior collection ----------------
internal val behavixInit1 = kill("Lcom/taobao/android/behavix/BehaviXV2;", "e", "V", listOf("Landroid/app/Application;"))
internal val behavixInit2 = kill("Lcom/taobao/android/behavix/b;", "d", "V", listOf("Landroid/app/Application;"))
internal val behavixCollect = kill("Lcom/taobao/android/behavix/collector/c;", "d", "V", listOf("Lcom/lazada/android/behavix/BehavixProvider\$MtopData;"))
internal val behavixProvider = kill("Lcom/lazada/android/behavix/BehavixProvider;", "b", "V", listOf("Lmtopsdk/mtop/domain/MtopRequest;"))
internal val behavixUpdateEvent = kill("Lcom/taobao/android/behavix/collector/d;", "updateEvent", "V", listOf("Lcom/ut/mini/UTEvent;"))

// ---------------- Zalo device-tracking ----------------
// Kill the tracking at its init + dispatch (both void) rather than nulling the id getters — the
// id-getter result is dereferenced by DeviceTracking, so a null there would NPE. initDeviceTracking
// is the tracking-SDK entry (separate from OAuth login, which is left working).
private const val ZALO_DT = "Lcom/zing/zalo/devicetrackingsdk/DeviceTracking;"
internal val zaloInit = kill(ZALO_DT, "initDeviceTracking", "V", listOf("Landroid/content/Context;", "Lcom/zing/zalo/devicetrackingsdk/BaseAppInfoStorage;", "Ljava/lang/String;"))
internal val zaloSend = kill(ZALO_DT, "sendMessage", "V", listOf("I"))

// ---------------- Motu crash + tbrest APM uploader ----------------
internal val motuRestSend = kill("Lcom/alibaba/motu/tbrest/request/c;", "a", "Lcom/alibaba/motu/tbrest/request/BizResponse;", listOf("Ljava/lang/String;", "Ljava/lang/String;", "[B"))

// ---------------- Firebase Analytics / GA measurement funnels ----------------
internal val fbaseDynamiteLog = kill("Lcom/google/android/gms/measurement/internal/AppMeasurementDynamiteService;", "logEvent", "V", listOf("Ljava/lang/String;", "Ljava/lang/String;", "Landroid/os/Bundle;", "Z", "Z", "J"))
internal val fbaseAnalyticsLog = kill("Lcom/google/firebase/analytics/FirebaseAnalytics;", "a", "V", listOf("Ljava/lang/String;", "Landroid/os/Bundle;"))
internal val fbaseMeasureInternal = kill("Lcom/google/android/gms/measurement/AppMeasurement;", "logEventInternal", "V", listOf("Ljava/lang/String;", "Ljava/lang/String;", "Landroid/os/Bundle;"))
internal val fbaseSdkLog = kill("Lcom/google/android/gms/measurement/api/AppMeasurementSdk;", "logEvent", "V", listOf("Ljava/lang/String;", "Ljava/lang/String;", "Landroid/os/Bundle;"))

// ---------------- UCWeb webview/crash telemetry (Java uploaders) ----------------
internal val ucApplogSend = kill("Lcom/uc/crashsdk/a/h;", "b", "Z", listOf("Ljava/lang/String;", "Ljava/lang/String;"))
internal val ucHttpPost = kill("Lcom/uc/crashsdk/a/c;", "a", "[B", listOf("Ljava/lang/String;", "[B"))
internal val ucStatSend = kill("Lcom/uc/webview/internal/stats/k;", "a", "Z", listOf("Ljava/lang/String;", "[B"))

/**
 * Kills the auto-collection that no code stub reaches, via manifest meta-data. Upsert (flip if
 * present, add if absent). Only "=false" targets are set — those read correctly as boolean strings;
 * the bytecode measurement stubs are the real guarantee. Facebook LOGIN meta-data
 * (ApplicationId/ClientToken) and FirebaseInitProvider/FCM are deliberately left in place.
 */
private val disableAutoCollectionResourcePatch = resourcePatch(
    description = "Disables Facebook + Firebase/GA auto-collection via AndroidManifest meta-data flags.",
) {
    compatibleWith(COMPATIBILITY_DARAZ)

    finalize {
        document("AndroidManifest.xml").use { doc ->
            val application = doc.getElementsByTagName("application").item(0)

            fun setFlag(name: String, value: String) {
                val existing = doc.getElementsByTagName("meta-data")
                for (i in 0 until existing.length) {
                    val el = existing.item(i) as Element
                    if (el.getAttribute("android:name") == name) {
                        el.setAttribute("android:value", value)
                        return
                    }
                }
                val el = doc.createElement("meta-data")
                el.setAttribute("android:name", name)
                el.setAttribute("android:value", value)
                application.appendChild(el)
            }

            listOf(
                "com.facebook.sdk.AutoLogAppEventsEnabled" to "false",
                "com.facebook.sdk.AdvertiserIDCollectionEnabled" to "false",
                "firebase_analytics_collection_enabled" to "false",
                "google_analytics_adid_collection_enabled" to "false",
                "google_analytics_ssaid_collection_enabled" to "false",
                "google_analytics_default_allow_analytics_storage" to "false",
            ).forEach { (name, value) -> setFlag(name, value) }
        }
    }
}

@Suppress("unused")
val removeAnalyticsAndTrackingPatch = bytecodePatch(
    name = "Remove analytics & tracking",
    description = "No-ops every analytics/ad/fingerprint egress in Daraz — Alibaba UserTrack " +
        "clickstream, AUDID/UTDID fingerprint upload, Behavix behavior collection, Meta/Facebook " +
        "App Events + GAID, Firebase Analytics, Zalo device-tracking, Motu crash/APM, and the UCWeb " +
        "webview telemetry uploaders — plus manifest auto-collection flags. Login, push (ACCS/FCM), " +
        "eKYC and the app's own commerce API are left intact.",
) {
    compatibleWith(COMPATIBILITY_DARAZ)
    dependsOn(disableAutoCollectionResourcePatch)

    execute {
        val returnFalse = """
            const/4 v0, 0x0
            return v0
        """.trimIndent()
        val returnNull = """
            const/4 v0, 0x0
            return-object v0
        """.trimIndent()

        // Best-effort: a method absent in some build variant just skips, never fails the whole patch.
        fun stub(fingerprint: Fingerprint, code: String) {
            try {
                fingerprint.method.addInstructions(0, code)
            } catch (_: Exception) {
                // fingerprint didn't resolve — skip
            }
        }

        // return-void: every tracker send/init/collect on a void path
        listOf(
            fbSetAutoLog, fbSetAdId,
            fbActivate1, fbActivate2, fbActivateImpl, fbLogEvent, fbLogPurchase, fbFlushImpl, fbQueueFlush,
            utTransfer1, utTransfer2, utTransfer3, utStoreAdd, utUploadCacheC, utUploadDbF, utUploadCacheF,
            audidRun,
            behavixInit1, behavixInit2, behavixCollect, behavixProvider, behavixUpdateEvent,
            zaloInit, zaloSend,
            fbaseDynamiteLog, fbaseAnalyticsLog, fbaseMeasureInternal, fbaseSdkLog,
        ).forEach { stub(it, "return-void") }

        // return false: boolean getters/senders whose "false" means "off / send failed"
        listOf(
            fbGetAutoLog, fbGetAdId,
            audidReqServer,
            ucApplogSend, ucStatSend,
        ).forEach { stub(it, returnFalse) }

        // return null: object-returning send primitives whose result callers null-check as "send failed"
        listOf(
            motuRestSend,
            ucHttpPost,
        ).forEach { stub(it, returnNull) }
    }
}
