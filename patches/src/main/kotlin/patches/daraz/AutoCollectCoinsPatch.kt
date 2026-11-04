package patches.daraz

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags

private const val COINS = "Lapp/proxma/daraz/CoinsAutoCollect;"
private const val APPLICATION = "Lpt/rocket/app/LazadaApplication;"

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

@Suppress("unused")
val autoCollectCoinsPatch = bytecodePatch(
    name = "Auto collect Daraz Coins",
    description = "Presses the daily Check-in / Collect button on the Daraz Coins page for you. " +
        "Open the Coins page once so the patch learns it; after that it opens the page by itself " +
        "once a day when you open Daraz, collects, and goes back. Needs you to be signed in.",
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
    }
}
