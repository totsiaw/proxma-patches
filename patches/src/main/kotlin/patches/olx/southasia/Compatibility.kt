package patches.olx.southasia

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

/**
 * OLX India (`com.olx.southasia`) — a different codebase from `com.olx.pk`, not a rename.
 *
 * Deliberately a sibling of `patches.olx.COMPATIBILITY_OLX` rather than a widening of it: the
 * Pakistan entry must keep rejecting this app (its `version = "18.8.0"` / `minSdk = 32` targets
 * describe that build only).
 *
 * Values verified against the APK handed over in totsiaw/proxma-patches#24 (comment #4),
 * sha256 `4e1dd7c1ce53cc9b256278d8ebaba8b49edecb5ae1fad85664ae3146dfd729c6`, 37,350,722 bytes:
 *
 * ```
 * $ aapt dump badging olx-southasia-20.07.001.apk
 * package: name='com.olx.southasia' versionCode='20007001' versionName='20.07.001'
 * sdkVersion:'23'
 * targetSdkVersion:'35'
 * application: label='OLX India'
 * ```
 */
internal val COMPATIBILITY_OLX_SOUTHASIA = Compatibility(
    name = "OLX India",
    packageName = "com.olx.southasia",
    apkFileType = ApkFileType.APK_REQUIRED,
    // OLX brand teal, justified from this build rather than copied off the .pk entry:
    // color/adpv_unselected_tab_text_color = #cc002f34, color/abundance_bar_color = #33002f34.
    appIconColor = 0x002F34,
    // emptySet() = accept any signing key, so Manager lists an installed OLX India as patchable.
    signatures = emptySet(),
    targets = listOf(
        AppTarget(
            // minSdk is this APK's own `sdkVersion: '23'` — not the 32 of the .pk build.
            version = "20.07.001",
            minSdk = 23,
        ),
    ),
)
