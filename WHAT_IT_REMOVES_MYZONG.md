# MyZong patches — what they do

For **MyZong** (`com.zong.customercare`, v5.19.19.112). Two patches: **Remove ads & tracking** and
**Unlock daily reward (skip ad)**. All results below were confirmed on-device (before/after, via each
SDK's own debug logging).

---

## Remove ads & tracking

MyZong ships **six** analytics/attribution SDKs and Google's ad stack on top of its own Zong API.
This patch neuters every one of them. After patching, the app makes analytics/ad calls to **none** of
them — it talks only to its own Zong backend.

### Trackers (analytics & attribution)

| SDK | What it sent | After patch |
|-----|--------------|-------------|
| **Firebase Analytics** | The loudest — an event on nearly every screen/tap. Payloads carried your **mobile number (MSISDN)** and network type. | Event sends stubbed + auto-collection disabled → **0 events** |
| **AppsFlyer** | Install / session / attribution reporting. | SDK never initializes → **0** |
| **TikTok** | App-event pixel (ByteDance). | Init + track stubbed → **0** |
| **Facebook** | App Events + advertiser-ID collection (login by OTP, so the SDK was there only for tracking). | Init/flags off → **0** |
| **Firebase Crashlytics / Performance** | Crash reports + network-request traces. | Auto-collection off |
| **Veridium** (biometric SDK) | Ships its **own** Google Analytics tracker. | Send methods stubbed → **0** |

> **Pushwoosh** (push notifications) is **left intact** — it's a feature, not analytics-only. Disable it separately if you want.

Your balance/MSISDN being attached to analytics events is the sharpest finding — that stops here.

### Ads

**Google AdMob** only (no third-party ad networks). Every ad format is blocked at load:
interstitial, banner, native, rewarded, **rewarded-interstitial** (the "watch to earn" reward ad),
and **app-open** (the ad on launch). The auto-init provider is also disabled.

Result: **no ad is ever requested or shown.** (The Google ad SDK still loads its own bootstrap
script once on launch — no ad renders from it; fully stopping that would risk crashing the app, so
it's left alone.)

---

## Unlock daily reward (skip ad)

MyZong's **Daily Rewards** "Free Mode" bubble game normally makes you watch a rewarded ad to claim
("Enjoy your free daily reward after this short Ad" → Proceed → ad → reward).

The claim itself is a normal server request — the app already grants it directly when it can't show
an ad (e.g. offline). This patch makes **every** tap take that direct-claim path: tap a bubble →
reward claimed. **No popup, no ad.** It uses the app's own claim call, so the reward is granted
exactly as if you'd watched the ad.

> This one is a convenience/feature toggle (it bypasses an ad gate). Enable it only if you want to skip the reward ad.

---

## Notes

- **Z Play** (the in-app games tab, "Exscape") is a third-party website shown in a webview. Its ads
  and tracking live on that website, not in the app's own code, so these patches don't affect it.
- Verified by watching each SDK's debug logs (no analytics/ad event is generated). This confirms the
  app stops *producing* tracker/ad traffic; it isn't a network-packet capture.
