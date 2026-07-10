package patches.daraz

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal val COMPATIBILITY_DARAZ = Compatibility(
    name = "Daraz",
    packageName = "com.daraz.android",
    // Split APK (base + config/dynamic-feature splits) — Manager must patch the whole app.
    apkFileType = ApkFileType.APK_REQUIRED,
    appIconColor = 0xF85606, // Daraz orange
    // emptySet() = accept any signing key, so Manager lists an installed Daraz as patchable.
    signatures = emptySet(),
    targets = listOf(
        AppTarget(
            version = "9.36.2",
            minSdk = 21,
        ),
    ),
)
