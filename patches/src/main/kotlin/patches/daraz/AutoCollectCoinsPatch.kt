package patches.daraz

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags

private const val COINS = "Lapp/proxma/daraz/CoinsAutoCollect;"
private const val APPLICATION = "Lpt/rocket/app/LazadaApplication;"

// Daraz's opening full-screen ad ("splash") is driven by Lazada's splash SDK. SplashPresenterImpl
// renders it and runs the 3-2-1 countdown; on some devices that countdown stalls at 1s and never
// dismisses, trapping the user on the ad. The presenter already has its own "skip the splash" path
// (when onCreateSplashUI decides to skip, it calls the host listener's onSplashStop() and returns
// immediately, before ever drawing the ad). We take that same path unconditionally at the top of
// onCreateSplashUI, so the splash is dismissed through the app's own callback and the app goes
// straight to home. This uses the real dismiss path — unlike nulling the ad "material", which left a
// caller dereferencing a null and crash-looped the app on launch.
private const val SPLASH_PRESENTER = "Lcom/lazada/android/splash/ui/SplashPresenterImpl;"
private const val MATERIAL_VO = "Lcom/lazada/android/splash/db/MaterialVO;"
private const val ON_SPLASH_LISTENER = "Lcom/lazada/android/splash/ui/ISplashPresenter\$OnSplashListener;"

// WindVane is Alibaba's web container. Daraz loads every server page (Daraz Coins included) in it,
// through the UC core when available and the system WebView otherwise. Both client classes keep
// their real names in 9.41.1, and Daraz's own client calls super.onPageFinished first.
private const val UC_WEB_CLIENT = "Landroid/taobao/windvane/extra/uc/WVUCWebViewClient;"
private const val UC_WEB_VIEW = "Lcom/uc/webview/export/WebView;"
private const val SYSTEM_WEB_CLIENT = "Landroid/taobao/windvane/webview/WVWebViewClient;"
private const val SYSTEM_WEB_VIEW = "Landroid/webkit/WebView;"

internal val darazApplicationOnCreateFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    custom = { method, classDef -> classDef.type == APPLICATION && method.name == "onCreate" },
)

internal val ucWebClientPageFinishedFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(UC_WEB_VIEW, "Ljava/lang/String;"),
    custom = { method, classDef -> classDef.type == UC_WEB_CLIENT && method.name == "onPageFinished" },
)

internal val systemWebClientPageFinishedFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(SYSTEM_WEB_VIEW, "Ljava/lang/String;"),
    custom = { method, classDef -> classDef.type == SYSTEM_WEB_CLIENT && method.name == "onPageFinished" },
)

// onCreateSplashUI(isColdBoot, activity, material, listener) is the single entry that builds and
// shows the opening ad. Its 4th parameter is the host's OnSplashListener; calling onSplashStop() on
// it is exactly how the presenter's own skip branch tells the host "splash done, carry on to home".
internal val splashCreateUiFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Z", "Landroid/app/Activity;", MATERIAL_VO, ON_SPLASH_LISTENER),
    custom = { method, classDef -> classDef.type == SPLASH_PRESENTER && method.name == "onCreateSplashUI" },
)

@Suppress("unused")
val autoCollectCoinsPatch = bytecodePatch(
    name = "Auto collect Daraz Coins",
    description = "Presses the daily Check-in / Collect button on the Daraz Coins page for you. " +
        "Open the Coins page once so the patch learns it; after that it opens the page by itself " +
        "once a day when you open Daraz, collects, and goes back. Needs you to be signed in. " +
        "It also skips Daraz's opening full-screen ad so the app goes straight to the home screen.",
) {
    compatibleWith(COMPATIBILITY_DARAZ)
    extendWith("extensions/extension.mpe")

    execute {
        darazApplicationOnCreateFingerprint.method.addInstructions(
            0,
            "invoke-static {p0}, $COINS->onAppCreate(Landroid/app/Application;)V",
        )

        var hooked = 0
        val ucPage = runCatching { ucWebClientPageFinishedFingerprint.method }.getOrNull()
        if (ucPage != null) {
            ucPage.addInstructions(
                0,
                "invoke-static {p1, p2}, $COINS->onPageFinished(Ljava/lang/Object;Ljava/lang/String;)V",
            )
            hooked++
        }
        val systemPage = runCatching { systemWebClientPageFinishedFingerprint.method }.getOrNull()
        if (systemPage != null) {
            systemPage.addInstructions(
                0,
                "invoke-static {p1, p2}, $COINS->onPageFinished(Ljava/lang/Object;Ljava/lang/String;)V",
            )
            hooked++
        }
        if (hooked == 0) {
            throw PatchException("Neither WindVane web client onPageFinished was found to hook")
        }

        // Skip Daraz's opening full-screen ad by dismissing the splash through the host's own
        // OnSplashListener before the ad is ever drawn — the app's legitimate skip path. Guard the
        // listener for null like the app does. Non-fatal: if the splash SDK ever moves, the Coins
        // feature still applies.
        runCatching { splashCreateUiFingerprint.method }.getOrNull()?.addInstructions(
            0,
            """
                if-eqz p4, :proxma_continue
                invoke-interface {p4}, $ON_SPLASH_LISTENER->onSplashStop()V
                return-void
                :proxma_continue
                nop
            """,
        )
    }
}
