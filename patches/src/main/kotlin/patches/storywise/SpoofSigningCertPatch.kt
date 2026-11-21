package patches.storywise

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val spoofSigningCertPatch = bytecodePatch(
    name = "Spoof signing certificate",
    description = "Makes the Firebase/GMS client report the ORIGINAL Play-signed " +
        "certificate SHA-1 instead of the re-signing key. Storywise's Firebase " +
        "project restricts its Android API key / app authorization to the original " +
        "app-signing certificate. The Firebase SDK attaches an X-Android-Cert header " +
        "(uppercase SHA-1 hex of the installed signing cert, computed in-process by " +
        "AndroidUtilsLight.getPackageCertificateHashBytes) to every Firebase Auth, " +
        "Firestore, Remote Config and Installations request. After Morphe re-signs the " +
        "APK, that header carries the WRONG fingerprint, so Google rejects every request " +
        "with ERROR_APP_NOT_AUTHORIZED / 403 - which makes BOTH guest (anonymous) sign-in " +
        "and Google sign-in fail identically. This forces the single choke point " +
        "(M3/c.e = getPackageCertificateHashBytes) to return the original certificate's " +
        "20-byte SHA-1 digest, so every Firebase service sends the whitelisted fingerprint " +
        "378875D7D060897810393424FF25AAB5F93E264B and the backend accepts the login.",
) {
    compatibleWith(COMPATIBILITY_STORYWISE)

    execute {
        // Original Play-App-Signing certificate SHA-1 of com.storywise.storywise 1.0.5
        // (Signer #1 of the unmodified apkmirror APK), as raw 20 digest bytes.
        val origSha1 = intArrayOf(
            0x37, 0x88, 0x75, 0xD7, 0xD0, 0x60, 0x89, 0x78, 0x10, 0x39,
            0x34, 0x24, 0xFF, 0x25, 0xAA, 0xB5, 0xF9, 0x3E, 0x26, 0x4B,
        )

        // M3/c.e(Context,String)[B is .registers 6 (p0=v4, p1=v5); v0..v2 are free.
        // Build the fixed digest in v0 and return it before the real lookup runs.
        val smali = buildString {
            append("const/16 v0, 0x14\n")
            append("new-array v0, v0, [B\n")
            origSha1.forEachIndexed { i, b ->
                append("const/16 v1, 0x" + b.toString(16) + "\n")
                append("const/16 v2, 0x" + i.toString(16) + "\n")
                append("aput-byte v1, v0, v2\n")
            }
            append("return-object v0\n")
        }

        val helper = mutableClassDefBy("LM3/c;")
        val getCertHash = helper.methods.first {
            it.name == "e" &&
                it.parameters.size == 2 &&
                it.returnType == "[B"
        }
        getCertHash.addInstructions(0, smali)
    }
}
