package patches.storywise

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal val COMPATIBILITY_STORYWISE = Compatibility(
    name = "Storywise",
    packageName = "com.storywise.storywise",
    apkFileType = ApkFileType.APK_REQUIRED,
    appIconColor = 0x6C63FF,
    signatures = emptySet(),
    targets = listOf(
        AppTarget(
            version = "1.0.3",
            minSdk = 25,
        ),
    ),
)
