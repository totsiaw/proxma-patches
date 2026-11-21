package patches.storywise

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

// ---------------------------------------------------------------------------
// Signature-spoof permission for microG Google sign-in.
//
//   Storywise's Google Sign-In OAuth client is registered in the Google Cloud
//   project against the ORIGINAL Play app-signing certificate
//   (SHA-1 378875D7D060897810393424FF25AAB5F93E264B) + package name. microG
//   (GmsCore) obtains the ID token by asking the SYSTEM PackageManager for the
//   caller's signing certificate; after Morphe re-signs the APK that is the
//   throwaway key, so Google answers DEVELOPER_ERROR and no token is returned.
//
//   Declaring android.permission.FAKE_PACKAGE_SIGNATURE opts the app into the
//   signature-spoofing hook that microG-capable ROMs (and GmsCore builds with
//   signature spoofing) honour, so PackageManager reports the ORIGINAL cert to
//   microG and the OAuth token request matches the registered client. On a
//   device with no spoofing support the permission is simply ignored, so adding
//   it never hurts. (The in-process Firebase X-Android-Cert header is handled
//   separately by spoofSigningCertPatch.)
// ---------------------------------------------------------------------------
private val fakeSignaturePermissionPatch = resourcePatch {
    finalize {
        document("AndroidManifest.xml").use { doc ->
            val manifest = doc.documentElement
            val permission = "android.permission.FAKE_PACKAGE_SIGNATURE"
            val existing = doc.getElementsByTagName("uses-permission")
            val present = (0 until existing.length).any {
                (existing.item(it) as Element).getAttribute("android:name") == permission
            }
            if (!present) {
                manifest.insertBefore(
                    doc.createElement("uses-permission")
                        .apply { setAttribute("android:name", permission) },
                    manifest.firstChild,
                )
            }
        }
    }
}

@Suppress("unused")
val gmsSignInSupportPatch = bytecodePatch(
    name = "Google sign-in via microG",
    description = "Lets the Google sign-in button work on a de-Googled phone that runs " +
        "microG (GmsCore) instead of the real Google Play services. Storywise signs in " +
        "with Google through the AndroidX Credential Manager. Two gates are forced here.\n" +
        "\n" +
        "(1) The Credential Manager provider factory (androidx.credentials, " +
        "CredentialProviderFactory) picks a provider by Android version: on Android 14+ " +
        "(API 34) it tries the PLATFORM provider (CredentialProviderFrameworkImpl, which " +
        "wraps android.credentials.CredentialManager) FIRST and only falls back to the " +
        "Play-services provider when the platform provider reports isAvailableOnDevice() " +
        "== false. On a de-Googled phone the platform CredentialManager exists but has no " +
        "Google credential provider registered, so the sign-in silently returns \"no " +
        "credential\" and microG never shows an account picker. Forcing the framework " +
        "provider's isAvailableOnDevice() to return false makes the factory skip it and " +
        "fall back to the Play-services provider, which microG can service.\n" +
        "\n" +
        "(2) The Play-services provider (CredentialProviderPlayServicesImpl) first calls " +
        "isAvailableOnDevice(): it asks GoogleApiAvailability for a minimum Play-services " +
        "APK version and, when microG reports a lower/spoofed version, returns false, so " +
        "Credential Manager refuses to use the provider. Forcing isAvailableOnDevice(int) " +
        "to return true makes Credential Manager route the sign-in through the " +
        "Play-services provider, which microG then handles, so signInWithCredential " +
        "receives a real Google ID token. On a device with no Play-services at all the " +
        "follow-up HiddenActivity launch is already wrapped in a try/catch and degrades to " +
        "the normal failure callback, so this does not crash.\n" +
        "\n" +
        "Also declares FAKE_PACKAGE_SIGNATURE so a spoofing-capable microG/ROM reports the " +
        "original app-signing certificate to microG, letting the OAuth token request match " +
        "the registered client after re-signing.\n" +
        "\n" +
        "Enable this instead of \"Bypass login\" when you want to sign in with a Google " +
        "account; leave it off and use \"Skip for now\" / \"Bypass login\" when you use the " +
        "app without an account.\n" +
        "\n" +
        "Requires a microG/GmsCore build that implements the Credential Manager / Identity " +
        "(BeginSignIn) flow AND device-level signature spoofing; without those the account " +
        "picker may still not appear and Google login cannot complete on a de-Googled phone.",
) {
    compatibleWith(COMPATIBILITY_STORYWISE)
    dependsOn(fakeSignaturePermissionPatch)

    execute {
        // ---------------------------------------------------------------------
        // Gate (1): the platform (framework) credential provider.
        //
        //   On v1.0.5 CredentialProviderFrameworkImpl is obfuscated to Ly0/h; — the
        //   only CredentialProvider (Ly0/d;) whose field type is the platform
        //   android.credentials.CredentialManager. Its isAvailableOnDevice()Z returns
        //   true on API 34+ when that manager is non-null. The provider factory
        //   (LO3/c;->c) consults it BEFORE the Play-services provider and, when it is
        //   available, uses it and never falls back — which is exactly what traps the
        //   sign-in on a de-Googled Android 14+ phone. Force it to report false so the
        //   factory falls through to the Play-services provider below.
        //
        //   .registers 3 (p0 = this -> v2); v0/v1 are free locals.
        // ---------------------------------------------------------------------
        val framework = mutableClassDefBy("Ly0/h;")
        val frameworkGate = framework.methods.first {
            it.name == "isAvailableOnDevice" &&
                it.parameters.isEmpty() &&
                it.returnType == "Z"
        }
        frameworkGate.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """.trimIndent(),
        )

        // ---------------------------------------------------------------------
        // Gate (2): the Play-services credential provider.
        //
        //   isAvailableOnDevice(I)Z is the real gate; the no-arg isAvailableOnDevice()Z
        //   delegates to it (passing MIN_GMS_APK_VERSION), so returning true here forces
        //   both overloads to report the provider as available. .registers 5 for this
        //   method (p0=this -> v3, p1=int -> v4), so v0 is a free local.
        // ---------------------------------------------------------------------
        val impl = mutableClassDefBy(
            "Landroidx/credentials/playservices/CredentialProviderPlayServicesImpl;",
        )
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
