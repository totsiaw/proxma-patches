# Proxma Patches

Morphe patch bundle for **My Telenor**, **Investify**, **Simosa** (Jazz), **OLX**, **MTProxy**, **MyZong** (Zong), and **NetMonster**. See [Patches](#patches) below for the current list of apps, supported versions, and what each patch does.

## How to use

1. Install [Morphe Manager](https://morphe.software).
2. Add this source: https://morphe.software/add-source?github=totsiaw/proxma-patches
   (already added? open the source and tap **Update** / pull-to-refresh to get the latest version).
3. Pick your app, select the patches you want, tap **Patch**, then install the result.

> Patches target a specific app version (below). If your installed app is a different version, it won't match until that version is added.


## Patches

### My Telenor (`com.telenor.pakistan.mytelenor`)

_Supported version(s): 4.2.62_

| Patch | Description |
|-------|-------------|
| **Block trackers** | Stops the Insider, TikTok Business SDK and Mixpanel Session Replay trackers from initializing (event/attribution/session-replay tracking never starts). Leaves AWS Amplify, Firebase core/RemoteConfig/FCM and Google Ads init untouched so app data, push and the remote ad-config the ads patch relies on keep working. |
| **Block trackers (manifest flags)** | Disables Firebase Analytics + Google advertiser-id auto-collection and the Facebook SDK's auto app-events / advertiser-id / auto-init via AndroidManifest <meta-data> flags — the SDK-side auto-collection that no bytecode init-stub can reach. Leaves Firebase core (FCM push, RemoteConfig) and the Facebook ContentProvider intact. Separate from the bytecode Block trackers patch so you can pick either or both. |
| **Bypass device validation** | Neutralizes the client-side device-validation gate (anti-root / anti-emulator / anti-Frida / anti-Xposed / anti-tamper battery) that shows a "Device validation failed" toast and kills the app via finishAffinity() on a rooted, emulated or re-signed build. Redirects the single failure callback to the success path so all ~16 checks report as passed and the app keeps running pre- and post-login. |
| **Remove ads** | Removes every Google Ad Manager ad (banners + interstitials) across Daily Rewards, Home, Test Your Skills and Explore by nulling the remote ad-config chokepoint, so each surface renders its no-ads layout and never requests an interstitial. |

### Investify (`com.blueinklabs.investifystocks.free`)

_Supported version(s): 5.6.0_

| Patch | Description |
|-------|-------------|
| **Bypass PairIP license check** | Disables Google PairIP's license/installer check (com.pairip.licensecheck) so a re-signed build runs on a real device instead of being redirected to the Play Store and killed. No-ops the LicenseContentProvider entry point and LicenseClient.initializeLicenseCheck(). |
| **Unlock premium (remove ads)** | Unlocks Investify premium — forces the backend `no_ads` entitlement getter to report true in both the model and its Realm proxy, so the app treats the account as ad-free without any purchase. Ad SDK loads are gated on this flag app-wide. |

### Simosa (`com.jazz.jazzworld`)

_Supported version(s): 3.3.4.2_

| Patch | Description |
|-------|-------------|
| **Bypass signature verification** | Disables Simosa's anti-tamper signature check so a re-signed APK launches normally instead of stalling on the splash / "version is not correct" dialog. |
| **Remove ads & tracking** | Removes every ad (interstitial, banner, daily-reward) and every tracker (Mixpanel, Firebase, Facebook, AppsFlyer) — app events, network sends, ad-SDK requests (Google Ads / AppLovin / AnyMind / Prebid), SDK auto-collection, and the ipify IP leak. The app then phones home only to its own Jazz API. |
| **Remove daily check-in ads** | Removes the SocialPlus daily check-in / in-feed ads (FeedAdsManager banner + native loaders). Separate from "Remove ads & tracking" to keep that patch Morphe-Manager-safe; enable this one when patching with the desktop CLI. |

### OLX (`com.olx.pk`)

_Supported version(s): 18.8.0_

| Patch | Description |
|-------|-------------|
| **Force dark mode** | Forces OLX into dark (night) mode regardless of the system theme, using the app's built-in -night resources via UiModeManager.setApplicationNightMode at startup. |
| **Remove ads** | Removes native feed ads (Google GMA), the full-height ad slot, and the "Buy with Delivery" promo section (bar, cards and View all) from OLX. Pinned to the 18.8.0 build (matches that build's obfuscated feed classes). |

### MTProxy (`com.sdev.mtproxy`)

_Supported version(s): 2.1.4_

| Patch | Description |
|-------|-------------|
| **Remove ads** | Removes every AdMob ad — the interstitial (rerouted to its onAdFailedToLoad branch so the proxy-apply action still runs, but no ad loads or shows) and the on-screen banner (load skipped). No feature is lost. |

### MyZong (`com.zong.customercare`)

_Supported version(s): 5.19.19.112_

| Patch | Description |
|-------|-------------|
| **Remove ads & tracking** | Removes every ad (AdMob) and every tracker (Firebase Analytics, AppsFlyer, Facebook, TikTok, and the Veridium SDK's own Google Analytics) — event sends, full SDK init (AppsFlyer init, TikTok initializeSdk/startTrack, MobileAds.initialize), and auto-collection. Pushwoosh push is left intact. The app then phones home only to its own Zong API. |
| **Unlock daily reward (skip ad)** | Claim MyZong's daily reward with no 'watch ad' popup and no ad. Forces the reward-bubble tap to take the app's own direct-claim branch (the same claim call the post-ad path makes), upstream of both the popup and the rewarded ad. |

### NetMonster (`cz.mroczis.netmonster`)

_Supported version(s): 3.4.1_

| Patch | Description |
|-------|-------------|
| **Unlock premium (NetMonster)** | Unlocks NetMonster Premium — forces the premium repo's derived flows so real-time LTE/NR-NSA location calculation is unlocked, ads are removed, and the status shows Active (far-future expiry) without an Adapty subscription. |

## Troubleshooting

### Patched app crashes on launch with `ClassNotFoundException` (large apps, e.g. Jazz World / Simosa)

Symptom: right after patching, the app crashes with
`java.lang.ClassNotFoundException: Didn't find class "...Application"` — even though the class is present in the APK.

Cause: **Morphe Manager runs the patcher on-device (Android ART), and its build output is defective for large multi-dex apps** (Jazz World is ~181 MB / 9 DEX). Once the number of modified classes crosses a threshold, Manager's dex writer emits an extra **empty/duplicate DEX** (`class_defs=0`) alongside the real one; Android's runtime then rejects the dex set, so the DEX holding the app's `Application` class never loads. This is [morphe-manager#616](https://github.com/MorpheApp/morphe-manager/issues/616) — and it happens in **Fast (STRIP_FAST) too**, not only FULL.

Verified it is the *build*, not the device or install: the same Manager-built DEX files installed manually via `adb` still crash, while the identical patches built with the **desktop CLI** (patcher on a normal JVM) run fine on the same phone.

**Fixes, most reliable first:**

1. **Patch with the morphe-desktop CLI** — it builds a clean single patched DEX, no empty duplicate:
   ```
   java -jar morphe-desktop-<ver>-all.jar patch      --patches proxma-patches.mpp -e "Bypass signature verification" -e "Remove ads & tracking"      -i com.jazz.jazzworld.apk
   ```
2. **In Morphe Manager, enable fewer patches** so the modified-class count stays under the threshold (see the Simosa note below). Keep **Bytecode mode = Fast**; avoid **FULL**.

### Simosa (Jazz World) in Morphe Manager

Jazz World is one of those large apps. Because of the bug above, in **Morphe Manager** enable only:

- ✅ **Bypass signature verification**
- ✅ **Remove ads & tracking**

and leave **❌ Remove daily check-in ads** OFF — that extra patch pushes the build past the threshold that triggers #616 and crashes the app. Enable **Remove daily check-in ads** only when patching with the **morphe-desktop CLI** (which is not affected). The two-patch selection covers every ad except the daily check-in one.
