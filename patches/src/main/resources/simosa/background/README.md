# Simosa Background Worker (B26)

Modified smali for ClaimWorker + Notify that adds:
- **Auto-claim**: Daily Rewards ladder claim via WorkManager at 00:35
- **Auto-check-in**: Simosa daily check-in (api-prod-dailycheckin.simosa.app/checkin)
- **Token refresh**: 2-tier auth — cached token (Tier 1) + stored refresh_token fallback (Tier 2)
- **Status notification**: Android notification on morphe_auto channel after each run

## B26 fixes (over B25g)
1. **419/401 → refresh retry**: Stale cached token returned 419 "Access token has expired" but code only logged failure. Now 401 OR 419 with cached-path flag → `goto :try_refresh_token` for Tier 2 refresh.
2. **409 → mark checkin_day**: Server "Already checked in today" (409) now writes `checkin_day=today` to prefs, preventing retry spam every 10 min.
3. **JSON `data` wrapper fallback**: `optJSONObject("data")` returning null (flat response) now falls back to root JSONObject instead of silently dropping the access_token.

## Files
- `ClaimWorker.smali` — modified classes10.dex smali (replaces original 7KB → 30KB)
- `Notify.smali` — modified classes11.dex smali (adds notifyStatus BigTextStyle notification)
- `classes10.dex` — pre-assembled dex with ClaimWorker changes
- `classes11.dex` — pre-assembled dex with Notify changes

## Build recipe (from signed B23+ APK)
```bash
cp base-signed.apk output.apk
zip -d output.apk classes10.dex classes11.dex META-INF/MORPHE.SF META-INF/MORPHE.RSA META-INF/MANIFEST.MF
zip -j output.apk classes10.dex classes11.dex
zipalign -p -f 4 output.apk output-aligned.apk
apksigner sign --ks deploy.jks --ks-pass pass:morphe123 --out output-signed.apk output-aligned.apk
```

## Prefs (proxma_claim.xml)
- `simosa_refresh_token` — 30-day Simosa JWT refresh token
- `simosa_uuid` — UUID for refresh calls
- `claimed_day` / `checkin_day` — day-of-year latch
- `att_day` / `att_n` — attempt tracking

## Tested 2026-10-22
- Device: OnePlus 12T (Android 15)
- App: Jazz World 3.3.5
- B25g: claim, check-in (409), notification, token save
- B26: 409→checkin_day written, 419→refresh retry path wired (exercises on stale cache day)
