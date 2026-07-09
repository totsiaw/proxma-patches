# What "Remove ads & tracking" strips

Simosa (com.jazz.jazzworld) ships **four analytics/attribution SDKs, four ad SDKs, and two
public-IP lookups** on top of its own Jazz API. Below is what was observed on device (v3.3.2) and
what the patch neutralizes. After patching, the app makes network calls **only to its own Jazz
backend** — every item below drops to zero.

## Trackers (analytics & attribution)

| SDK | Endpoint | What it sent |
|-----|----------|--------------|
| **Mixpanel** | `api.mixpanel.com` (US) | The loudest. Fired on nearly every screen view / tap / ad event. Every event carried PII **super-properties**: `CustomerID`, MSISDN (`distinct_id`), **`Current_Balance`**, `Plan_Type`, `AdvertisementID`, `carrier`, `Network`, `UserType`, `Login_Date`, `Selected_Language`, device model/OS. Observed events include `Ads_Third_Party`, `App_Open`, `$ae_session`. |
| **Firebase** | `*.googleapis.com`, `crashlytics.com` | Analytics (explicit events + auto `screen_view`/`session`/`first_open`), **Crashlytics** (crash reports), **Performance** (network-request traces). |
| **Facebook SDK** | `graph.facebook.com` | App Events (`logEvent`), automatic events, **advertiser-ID collection**, and an init "gatekeeper" handshake. Note: the app logs in by **phone/OTP**, so Facebook *login* is never used — the SDK was present only for tracking. |
| **AppsFlyer** | AppsFlyer servers | Install / session / attribution reporting. |

Balance and subscriber identity being shipped to US analytics vendors on essentially every screen
is the sharpest finding — financial data does not belong in an analytics payload.

## Ad SDKs

| SDK | Role |
|-----|------|
| **Google Ad Manager** | Primary. Interstitials (Dashboard, Radio, Daily Reward), a MEDIUM_RECTANGLE banner on the Daily Reward screen, and small native ads. |
| **AppLovin MAX** | Mediation demand (`rt.applovin.com` / `ms.applovin.com`). |
| **AnyMind** | Mediation adapters (driven by Google Ad Manager). |
| **Prebid** | Runs **independently** (not under GMA mediation): initializes at startup, downloads the OM-SDK JS from `cdn.jsdelivr.net`, and header-bids for the "VEON" ad slots. |

## Public-IP lookups (IP leaks)

The device sits behind carrier NAT, so the app can't see its own public IP locally — it asks
third parties, then forwards the result:

| Source | Lookup | Where the IP goes |
|--------|--------|-------------------|
| **App (Omno topup flow)** | `GET https://api.ipify.org` | Written into `OmnoTopUpRequest.clientIp` / `deviceIpAddress` and `OmnoSubscriptionRequest.ipAddress`, then sent to Jazz's Omno backend (geo/fraud gating). Because the value is client-fetched, it is **attacker-spoofable** — a proxy can make the app report any IP. |
| **Prebid** | `GET https://api.ipify.org` → fallback `https://checkip.amazonaws.com` | Ad geo-targeting. |

## What the patch does

- **Trackers**: no-ops each SDK's event/send path (Mixpanel `track`/`eventsMessage`/`postToServer`,
  Firebase `logEvent`, Facebook `logEvent` + forces auto-collection getters false + disables FB init,
  AppsFlyer `start`/`logEvent`) and adds manifest flags disabling Firebase Analytics / Crashlytics /
  Performance and Google-Analytics ad-id/ssaid auto-collection.
- **Ads**: no-ops `MobileAds.initialize` (so the whole GMA + mediation stack never starts) plus the
  interstitial/banner load calls and holders, and no-ops Prebid init.
- **IP leaks**: the app's ipify fetch returns an empty string; Prebid's own IP fetch never runs
  (its init is disabled).

Result: Mixpanel / Firebase / Facebook / AppsFlyer / Google-Ads / AppLovin / AnyMind / Prebid /
ipify / checkip — **all zero**. The app talks only to its own Jazz API.
