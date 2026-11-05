package patches.daraz

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags

private const val COINS = "Lapp/proxma/daraz/CoinsAutoCollect;"
private const val APPLICATION = "Lpt/rocket/app/LazadaApplication;"

// Daraz's opening full-screen ad ("splash") is driven by Lazada's splash SDK. Both the cold-boot
// and the async paths ask this inspector for an ad "material" to show; getAvailableMaterialStartup
// is the shared entry and it in turn calls getAvailableMaterial(Z). Both are declared @Nullable and
// every caller already handles null (no material = no ad), so returning null here makes the app open
// straight to its home screen with no opening ad, and sidesteps the ad freeze some devices hit.
private const val SPLASH_INSPECTOR = "Lcom/lazada/android/splash/manager/AvailableMaterialInspectorImpl;"
private const val MATERIAL_VO = "Lcom/lazada/android/splash/db/MaterialVO;"

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

internal val splashAvailableMaterialFingerprint = Fingerprint(
    returnType = MATERIAL_VO,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Z"),
    custom = { method, classDef -> classDef.type == SPLASH_INSPECTOR && method.name == "getAvailableMaterial" },
)

// getAvailableMaterialStartup is declared-synchronized; Morphe matches accessFlags exactly, so the
// synchronized flag must be listed or the fingerprint resolves to nothing. This hook is defence in
// depth: getAvailableMaterial(Z) above already nulls every path, but this stops the coldboot cache
// from ever holding a material too.
internal val splashStartupMaterialFingerprint = Fingerprint(
    returnType = MATERIAL_VO,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.DECLARED_SYNCHRONIZED),
    parameters = listOf("Z"),
    custom = { method, classDef -> classDef.type == SPLASH_INSPECTOR && method.name == "getAvailableMaterialStartup" },
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

        // Skip Daraz's opening full-screen ad: make the splash inspector report no ad to show.
        // Non-fatal, so the Coins feature still applies if the splash SDK ever moves.
        val noMaterial = """
            const/4 v0, 0x0
            return-object v0
        """
        runCatching { splashStartupMaterialFingerprint.method }.getOrNull()
            ?.addInstructions(0, noMaterial)
        runCatching { splashAvailableMaterialFingerprint.method }.getOrNull()
            ?.addInstructions(0, noMaterial)
    }
}
