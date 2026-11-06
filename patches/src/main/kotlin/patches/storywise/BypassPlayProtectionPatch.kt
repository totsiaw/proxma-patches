package patches.storywise

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val bypassPlayProtectionPatch = bytecodePatch(
    name = "Bypass Play protection",
    description = "Neutralises the PairIP licence check that ships with Storywise 1.0.5. " +
        "On a sideloaded (re-signed) build the check fails and PairIP launches its " +
        "\"Get this app from Play\" screen, then shuts the app down. This forces the " +
        "single entry point com.pairip.licensecheck.LicenseClient.checkLicense(Context) " +
        "to return immediately, so no licensing service is contacted, the LicenseActivity " +
        "paywall is never launched, and the app opens normally. Both callers (the PairIP " +
        "Application.attachBaseContext and LicenseContentProvider.onCreate) funnel through " +
        "this method, so disabling it covers every path.",
) {
    compatibleWith(COMPATIBILITY_STORYWISE)

    execute {
        val licenseClient = mutableClassDefBy("Lcom/pairip/licensecheck/LicenseClient;")
        val checkLicense = licenseClient.methods.first {
            it.name == "checkLicense" &&
                it.parameters.size == 1 &&
                it.returnType == "V"
        }

        // Insert `return-void` as the first instruction so the entire licence-check
        // flow (service bind -> processResponse -> LicenseActivity -> app shutdown)
        // becomes unreachable dead code.
        checkLicense.addInstructions(0, "return-void")
    }
}
