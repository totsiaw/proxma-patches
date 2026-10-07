package patches.foodpanda

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal val COMPATIBILITY_FOODPANDA = Compatibility(
    name = "foodpanda",
    packageName = "com.global.foodpanda.android",
    apkFileType = ApkFileType.APK_REQUIRED,
    appIconColor = 0xD70F64, // foodpanda pink
    // emptySet() = accept any signing key, so Manager lists an installed foodpanda as patchable.
    signatures = emptySet(),
    targets = listOf(
        AppTarget(
            version = "26.38.1",
            minSdk = 29,
        ),
    ),
)
