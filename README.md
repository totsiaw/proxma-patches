# Proxma Patches

Morphe patch bundle for **Simosa** (`com.jazz.jazzworld`) and **MyZong** (`com.zong.customercare`).

## How to use

1. Install [Morphe Manager](https://morphe.software).
2. Add this source: https://morphe.software/add-source?github=totsiaw/proxma-patches
   (already added? open the source and tap **Update** / pull-to-refresh to get the latest version).
3. Pick your app, select the patches you want, tap **Patch**, then install the result.

> Patches target a specific app version (below). If your installed app is a different version, it won't match until that version is added.

**Details:**
- Simosa — [WHAT_IT_REMOVES.md](WHAT_IT_REMOVES.md)
- MyZong — [WHAT_IT_REMOVES_MYZONG.md](WHAT_IT_REMOVES_MYZONG.md)


## Patches

### Simosa (`com.jazz.jazzworld`)

_Supported version(s): 3.3.2_

| Patch | Description |
|-------|-------------|
| **Bypass signature verification** | Disables Simosa's anti-tamper signature check so a re-signed APK launches normally instead of stalling on the splash / "version is not correct" dialog. |
| **Remove ads & tracking** | Removes every ad (interstitial, banner, daily-reward) and every tracker (Mixpanel, Firebase, Facebook, AppsFlyer) — app events, network sends, ad-SDK requests (Google Ads / AppLovin / AnyMind / Prebid), SDK auto-collection, and the ipify IP leak. The app then phones home only to its own Jazz API. |

### MyZong (`com.zong.customercare`)

_Supported version(s): 5.19.19.112_

| Patch | Description |
|-------|-------------|
| **Remove ads & tracking** | Removes every ad (AdMob) and every tracker (Firebase Analytics, AppsFlyer, Facebook, TikTok, and the Veridium SDK's own Google Analytics) — event sends, full SDK init (AppsFlyer init, TikTok initializeSdk/startTrack, MobileAds.initialize), and auto-collection. Pushwoosh push is left intact. The app then phones home only to its own Zong API. |
| **Unlock daily reward (skip ad)** | Claim MyZong's daily reward with no 'watch ad' popup and no ad. Forces the reward-bubble tap to take the app's own direct-claim branch (the same claim call the post-ad path makes), upstream of both the popup and the rewarded ad. |

### Daraz (`com.daraz.android`)

_Supported version(s): 9.36.2_

| Patch | Description |
|-------|-------------|
| **Remove analytics & tracking** | No-ops every analytics/ad/fingerprint egress in Daraz — Alibaba UserTrack clickstream, AUDID/UTDID fingerprint upload, Behavix behavior collection, Meta/Facebook App Events + GAID, Firebase Analytics, Zalo device-tracking, Motu crash/APM, and the UCWeb webview telemetry uploaders — plus manifest auto-collection flags. Login, push (ACCS/FCM), eKYC and the app's own commerce API are left intact. |
