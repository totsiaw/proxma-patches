# Proxma Patches

Morphe patch bundle for Simosa (com.jazz.jazzworld).

Add in Morphe Manager: https://morphe.software/add-source?github=totsiaw/proxma-patches


## Patches

| Patch | Description |
|-------|-------------|
| **Block ad & tracking network requests** | Stops all ad-SDK network activity (Google Ad Manager + the AppLovin/AnyMind/Prebid mediation it drives) and the app's api.ipify.org public-IP fetch. No ads, no ad/IP telemetry. |
| **Bypass signature verification** | Disables Simosa's anti-tamper signature check so a re-signed APK launches normally instead of stalling on the splash / "version is not correct" dialog. |
| **Remove analytics tracking** | Stops all analytics/attribution telemetry (Mixpanel, Firebase, Facebook, AppsFlyer) — app events, network sends, and SDK auto-collection — so no tracking data or PII leaves the device. |
| **Remove daily reward ads** | Skips the ad shown before granting daily-reward MBs. |
| **Remove daily reward banner ad** | Removes the Google Ad Manager banner (MREC) on the Daily Reward screen by returning an empty, un-loaded ad view — nothing renders and no ad request is made. |
| **Remove interstitial ads** | Suppresses every Google Ad Manager interstitial (Dashboard, Radio, Daily Reward) by forcing the app's ad-holder accessors to report no loaded ad. |
