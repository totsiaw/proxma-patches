package patches.netmonster

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/*
 * NetMonster 5.0.x is shipped wrapped in Google Play's PairIP automatic integrity/licensing protection
 * (`com.pairip.licensecheck.*`, `com.pairip.application.Application`). On a re-signed (sideloaded) build
 * Play's licensing service reports the app as not legitimately acquired, so PairIP throws the
 * "Get this app from Play — To continue using NetMonster, get it on Google Play" paywall and then kills
 * the process. 5.0.x declares it as the real Application class, and the check fires from
 * `attachBaseContext` via `LicenseClient.checkLicense(Context)` plus `LicenseContentProvider`, i.e. on
 * EVERY launch — so a patched 5.0.0/5.0.1 build is blocked before the premium unlock matters.
 * (4.0.4 has no PairIP at all, which is why patched 4.0.4 keeps working.)
 *
 * In LicenseClient every block funnels through exactly two methods, and nothing else reaches the
 * blocking UI or the app-kill:
 *   - startPaywallActivity(PendingIntent): launches LicenseActivity as the PAYWALL ("get it from Play").
 *   - startErrorDialogActivity(): launches LicenseActivity as the ERROR_DIALOG ("something went wrong").
 * Both, and only these two, first call scheduleAppShutdown() (the sole caller of the process-kill) and
 * then startActivity(...). Stubbing both removes the paywall, the error dialog and the scheduled
 * shutdown at once, so the app proceeds normally whatever the licensing verdict is. Same fix that ships
 * for Safar (patches/safar/BypassLicensePatch.kt).
 *
 * Anchoring: PairIP's own class/method names are un-obfuscated (it is a Play-injected library, not app
 * code). The class is looked up optionally so this patch is a no-op on 4.0.4, where PairIP is absent and
 * a mandatory fingerprint would abort the whole patch.
 */
private const val PAIRIP_LICENSE_CLIENT = "Lcom/pairip/licensecheck/LicenseClient;"

@Suppress("unused")
val bypassPlayLicenseCheckPatch = bytecodePatch(
    name = "Bypass Play license check (NetMonster)",
    description = "Removes Google Play's PairIP license check so a re-signed NetMonster 5.0.x build runs " +
        "instead of being blocked by the \"Get this app from Play\" screen. Without it, patched 5.0.0 / " +
        "5.0.1 open the Play Store on launch and close. No effect on 4.0.4, which has no such check.",
) {
    compatibleWith(COMPATIBILITY_NETMONSTER)

    execute {
        // 4.0.4 ships without PairIP: nothing to bypass, and nothing to fail on.
        val client = mutableClassDefByOrNull(PAIRIP_LICENSE_CLIENT) ?: return@execute

        val paywall = client.methods.firstOrNull {
            it.name == "startPaywallActivity" && it.returnType == "V" &&
                it.parameterTypes == listOf("Landroid/app/PendingIntent;")
        }
        val errorDialog = client.methods.firstOrNull {
            it.name == "startErrorDialogActivity" && it.returnType == "V" && it.parameterTypes.isEmpty()
        }
        check(paywall != null && errorDialog != null) {
            "NetMonster: PairIP license UI methods not found in $PAIRIP_LICENSE_CLIENT"
        }

        paywall.addInstructions(0, "return-void")
        errorDialog.addInstructions(0, "return-void")
    }
}
