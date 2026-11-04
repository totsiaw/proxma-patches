package patches.daraz

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal val COMPATIBILITY_DARAZ = Compatibility(
    name = "Daraz",
    packageName = "com.daraz.android",
    apkFileType = ApkFileType.APK_REQUIRED,
    appIconColor = 0xF85606, // Daraz orange
    // emptySet() = accept any signing key, so Manager lists an installed Daraz as patchable.
    signatures = emptySet(),
    targets = listOf(
        // The hooks match on unobfuscated class + method names (LazadaApplication.onCreate and the
        // two WindVane onPageFinished methods), which are stable across these neighbouring builds,
        // so the same patch applies to 9.41.0 as well as 9.41.1.
        AppTarget(
            version = "9.41.1",
            minSdk = 21,
        ),
        AppTarget(
            version = "9.41.0",
            minSdk = 21,
        ),
    ),
)
