package patches.storywise

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val gmsSignInSupportPatch = bytecodePatch(
    name = "Google sign-in via microG",
    description = "Lets the Google sign-in button work on a de-Googled phone that runs " +
        "microG (GmsCore) instead of the real Google Play services. Storywise signs in " +
        "with Google through the AndroidX Credential Manager, whose Play-services " +
        "provider (androidx.credentials.playservices.CredentialProviderPlayServicesImpl) " +
        "first calls isAvailableOnDevice(): it asks GoogleApiAvailability for a minimum " +
        "Play-services APK version (0x0dc1f545) and, when microG reports a lower/spoofed " +
        "version, returns false, so Credential Manager refuses to use the provider and " +
        "the Google sign-in attempt fails with \"no credential\" before it ever reaches " +
        "microG. Forcing isAvailableOnDevice(int) to return true makes Credential Manager " +
        "route the sign-in through the Play-services provider, which microG then handles, " +
        "so signInWithCredential receives a real Google ID token. On a device with no " +
        "Play-services at all the follow-up HiddenActivity launch is already wrapped in a " +
        "try/catch and degrades to the normal failure callback, so this does not crash. " +
        "Enable this instead of \"Bypass login\" when you want to sign in with a Google " +
        "account; leave it off and use \"Skip for now\" / \"Bypass login\" when you use the " +
        "app without an account.",
) {
    compatibleWith(COMPATIBILITY_STORYWISE)

    execute {
        val impl = mutableClassDefBy(
            "Landroidx/credentials/playservices/CredentialProviderPlayServicesImpl;",
        )

        // isAvailableOnDevice(I)Z is the real gate; the no-arg isAvailableOnDevice()Z
        // delegates to it (passing MIN_GMS_APK_VERSION), so returning true here forces
        // both overloads to report the provider as available. .registers 5 for this
        // method (p0=this -> v3, p1=int -> v4), so v0 is a free local.
        val gate = impl.methods.first {
            it.name == "isAvailableOnDevice" &&
                it.parameters.size == 1 &&
                it.returnType == "Z"
        }

        gate.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """.trimIndent(),
        )
    }
}
