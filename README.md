# Proxma Patches

Morphe patch bundle for Simosa (com.jazz.jazzworld).

Add in Morphe Manager: https://morphe.software/add-source?github=totsiaw/proxma-patches

See [**WHAT_IT_REMOVES.md**](WHAT_IT_REMOVES.md) for the trackers, ad SDKs, and public-IP lookups this strips.


## Patches

| Patch | Description |
|-------|-------------|
| **Bypass signature verification** | Disables Simosa's anti-tamper signature check so a re-signed APK launches normally instead of stalling on the splash / "version is not correct" dialog. |
| **Remove ads & tracking** | Removes every ad (interstitial, banner, daily-reward) and every tracker (Mixpanel, Firebase, Facebook, AppsFlyer) — app events, network sends, ad-SDK requests (Google Ads / AppLovin / AnyMind / Prebid), SDK auto-collection, and the ipify IP leak. The app then phones home only to its own Jazz API. |
