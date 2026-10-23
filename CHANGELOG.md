## [1.27.0](https://github.com/totsiaw/proxma-patches/compare/1.26.9...1.27.0) (2026-10-23)

### New Features

* **mytelenor:** auto-claim daily streak MBs and quiz rewards ([49ddd6c](https://github.com/totsiaw/proxma-patches/commit/49ddd6caca9f537cbe9f53195c65b341de86fea4))

## [1.26.8](https://github.com/totsiaw/proxma-patches/compare/1.26.7...1.26.8) (2026-10-22)

### Bug Fixes

* resolve cold-start no-account skip in background worker ([a05eccb](https://github.com/totsiaw/proxma-patches/commit/a05eccbb8af6b04032ed17b7200d75b46ed4c242))

## [1.26.7](https://github.com/totsiaw/proxma-patches/compare/1.26.6...1.26.7) (2026-10-22)

### Bug Fixes

* auto-claim daily reward ladder from background worker ([a876bba](https://github.com/totsiaw/proxma-patches/commit/a876bba4f3ea85eb0f0b9bb1c96d979f43f688c2))

## [1.26.6](https://github.com/totsiaw/proxma-patches/compare/1.26.5...1.26.6) (2026-10-22)

### Bug Fixes

* **simosa:** refresh stale token on milestone 419/401 ([51dc4f4](https://github.com/totsiaw/proxma-patches/commit/51dc4f4dc6e6a610448d58311efd277949c81737))

## [1.26.5](https://github.com/totsiaw/proxma-patches/compare/1.26.4...1.26.5) (2026-10-22)

### Bug Fixes

* **simosa:** retry transient refresh failures, fix notification bugs ([105f94f](https://github.com/totsiaw/proxma-patches/commit/105f94fca5cce4c0459dbe430bb8149b6b17793b))

## [1.26.4](https://github.com/totsiaw/proxma-patches/compare/1.26.3...1.26.4) (2026-10-22)

### Bug Fixes

* **simosa:** retry on transient failure with exponential backoff ([0484bb3](https://github.com/totsiaw/proxma-patches/commit/0484bb3687e4bcfa77d7dc96b18d43870350628d))

## [1.26.3](https://github.com/totsiaw/proxma-patches/compare/1.26.2...1.26.3) (2026-10-22)

### Bug Fixes

* **simosa:** schedule worker at 00:05 PKT instead of 24h drift ([2141977](https://github.com/totsiaw/proxma-patches/commit/214197741e0b79dbb77ebb545b1237ef377e547e))

## [1.26.2](https://github.com/totsiaw/proxma-patches/compare/1.26.1...1.26.2) (2026-10-22)

### Bug Fixes

* **simosa:** hide the Social+ tab as well, by its own route ([d15e804](https://github.com/totsiaw/proxma-patches/commit/d15e804a78bfca6935c1769f3da1189c66e7a2f1)), closes [#31](https://github.com/totsiaw/proxma-patches/issues/31)
* **simosa:** refuse to inject the tab filter into an unexpected register ([081d90a](https://github.com/totsiaw/proxma-patches/commit/081d90adbab9119a5818245db763e0256570970d))

## [1.25.1](https://github.com/totsiaw/proxma-patches/compare/1.25.0...1.25.1) (2026-10-22)

### Bug Fixes

* **simosa:** add the daily check-in worker instead of hooking a class the app lacks ([57e91dc](https://github.com/totsiaw/proxma-patches/commit/57e91dc99a4c10328c6fc8cbf58160c04cd01f10)), closes [#41](https://github.com/totsiaw/proxma-patches/issues/41)

## [1.25.0](https://github.com/totsiaw/proxma-patches/compare/1.24.0...1.25.0) (2026-10-22)

### Bug Fixes

* **simosa:** B26 — stale token retry, 409 day-gate, JSON fallback ([6a486e1](https://github.com/totsiaw/proxma-patches/commit/6a486e1c3eed2ab55b2d5e0035659e96b385303a))
* **simosa:** B27 NO_SESSION cold-start + B28 notification channel ([7b04280](https://github.com/totsiaw/proxma-patches/commit/7b0428036891f42bd7ff7cbd22ba57bada769296))

### New Features

* **simosa:** add background auto-claim worker with token refresh and notification ([8bff6cf](https://github.com/totsiaw/proxma-patches/commit/8bff6cfdb6492aca060c7532e887bea2c2086d58))
* **simosa:** add BackgroundWorkerPatch — B26 check-in via Kotlin patcher ([d0ee564](https://github.com/totsiaw/proxma-patches/commit/d0ee56495a550a5ef645e256258575af9f432d0a))

## [1.24.0](https://github.com/totsiaw/proxma-patches/compare/1.23.2...1.24.0) (2026-10-21)

### Bug Fixes

* **simosa:** force the identifier accessor without Java 9's canAccess ([c698c88](https://github.com/totsiaw/proxma-patches/commit/c698c88b8b558dda3f97bef73a9bfbd05401a510))

### New Features

* **simosa:** remove Shop, Social+ and Lifestyle tabs ([d202b1e](https://github.com/totsiaw/proxma-patches/commit/d202b1ecb173dc196f6956bec693530e7188c8dc)), closes [#31](https://github.com/totsiaw/proxma-patches/issues/31)

## [1.23.2](https://github.com/totsiaw/proxma-patches/compare/1.23.1...1.23.2) (2026-10-21)

### Bug Fixes

* **simosa:** claim from t2's completion callback with correct guards and a daily latch ([33aebbd](https://github.com/totsiaw/proxma-patches/commit/33aebbd3295d3e95cc15b5949b1762bc2b563886))
* **simosa:** run the auto-claim after the response is applied, with the app's own guards ([0e48a21](https://github.com/totsiaw/proxma-patches/commit/0e48a21fd2d2ea7497ccb1cb2a8c1ba4482ed8dd))

## [1.23.1](https://github.com/totsiaw/proxma-patches/compare/1.23.0...1.23.1) (2026-10-21)

### Bug Fixes

* **simosa:** claim the Daily Rewards rung on every response, not on screen open ([69ea3b1](https://github.com/totsiaw/proxma-patches/commit/69ea3b1bf88882885a3a2d59679a596cccbaf2c3)), closes [#29](https://github.com/totsiaw/proxma-patches/issues/29) [#29](https://github.com/totsiaw/proxma-patches/issues/29)
* **simosa:** match the coroutine launch by shape, not the repackaged nb/ class ([af1231c](https://github.com/totsiaw/proxma-patches/commit/af1231cf46d03518c20f06345b8b9e38d1c1ad86))

## [1.23.0](https://github.com/totsiaw/proxma-patches/compare/1.22.2...1.23.0) (2026-10-21)

### New Features

* **olx:** suppress home banner and Google banner container loads ([a4f3d5f](https://github.com/totsiaw/proxma-patches/commit/a4f3d5fcada5b9d453ae30afd2d5c7de5fe34472))

## [1.22.2](https://github.com/totsiaw/proxma-patches/compare/1.22.1...1.22.2) (2026-10-21)

### Bug Fixes

* **simosa:** resolve StateFlow descriptor dynamically to fix 3.3.5 crash ([8d34d69](https://github.com/totsiaw/proxma-patches/commit/8d34d693b8936d398449a4ed8b52a38c8aab49f7))

## [1.22.1](https://github.com/totsiaw/proxma-patches/compare/1.22.0...1.22.1) (2026-10-21)

### Bug Fixes

* **simosa:** switch the app's Compose theme to dark instead of editing XML ([1eb7f8c](https://github.com/totsiaw/proxma-patches/commit/1eb7f8cf2a1d1630f66380f96110805fa52c36eb))

## [1.22.0](https://github.com/totsiaw/proxma-patches/compare/1.21.0...1.22.0) (2026-10-20)

### New Features

* **simosa:** add pure black (AMOLED) background patch ([4788869](https://github.com/totsiaw/proxma-patches/commit/47888698a54a5de0f824da52ff753b7cb393ac13)), closes [#000000](https://github.com/totsiaw/proxma-patches/issues/000000)

## [1.21.0](https://github.com/totsiaw/proxma-patches/compare/1.20.0...1.21.0) (2026-10-20)

### New Features

* **simosa:** support Simosa 3.3.5 ([f9cbd96](https://github.com/totsiaw/proxma-patches/commit/f9cbd96275ebff5e8b46098cdf35a45f499ce586))

## [1.20.0](https://github.com/totsiaw/proxma-patches/compare/1.19.1...1.20.0) (2026-10-20)

### Bug Fixes

* **netmonster:** expose the Google Maps key option in the patch list ([f88ad0e](https://github.com/totsiaw/proxma-patches/commit/f88ad0e2f959846caa9bc18f88ae4d5b60610892))
* **netmonster:** retarget premium patch to 5.0.0 / 5.0.1 ([c81d553](https://github.com/totsiaw/proxma-patches/commit/c81d553509f26bafa707fbb00dda26dc2b91a25c))
* **simosa:** claim the daily check-in reward as soon as the state loads ([03b9885](https://github.com/totsiaw/proxma-patches/commit/03b988599b51e3c582063845416b2623b3908049))
* **simosa:** claim the daily rung when the rewards screen is shown ([2183c29](https://github.com/totsiaw/proxma-patches/commit/2183c29575f24f67bfeed8906740c932ae23ece8)), closes [#29](https://github.com/totsiaw/proxma-patches/issues/29)

### New Features

* **indrive:** add remove-ads patch for sinet.startup.inDriver 5.188.0 / 5.189.0 ([3d4c6f8](https://github.com/totsiaw/proxma-patches/commit/3d4c6f885c478eae8e545a27dd0ad1b1f2a9ae41))
* **olx:** remove the item-detail (PDP) banner ad slots ([c2833a3](https://github.com/totsiaw/proxma-patches/commit/c2833a37a9d6cebbd842f6a05300998d5c1e9e8c))

## [1.19.1](https://github.com/totsiaw/proxma-patches/compare/1.19.0...1.19.1) (2026-10-20)

### Bug Fixes

* **netmonster:** show premium as owned on 5.0.x (card reads a date, not the flag) ([fb83d9c](https://github.com/totsiaw/proxma-patches/commit/fb83d9cec781108223558219d82a91bdbc99173b)), closes [#32](https://github.com/totsiaw/proxma-patches/issues/32)

## [1.19.0](https://github.com/totsiaw/proxma-patches/compare/1.18.1...1.19.0) (2026-10-20)

### Bug Fixes

* **simosa:** claim the daily rung when the rewards screen is shown ([3c17535](https://github.com/totsiaw/proxma-patches/commit/3c1753563f83e500b53062d0abd940db1830cfde)), closes [#29](https://github.com/totsiaw/proxma-patches/issues/29)

### New Features

* **olx:** remove the item-detail (PDP) banner ad slots ([82e20dd](https://github.com/totsiaw/proxma-patches/commit/82e20dd0a2c9182fbcd0c08f3aeff575fb8e176f))

## [1.18.1](https://github.com/totsiaw/proxma-patches/compare/1.18.0...1.18.1) (2026-10-20)

### Bug Fixes

* **netmonster:** bypass Google Play PairIP license check on 5.0.x ([b78b70c](https://github.com/totsiaw/proxma-patches/commit/b78b70c4c60607b6c394336e8a0df2cf5368f2d7))

## [1.18.0](https://github.com/totsiaw/proxma-patches/compare/1.17.0...1.18.0) (2026-10-20)

### Bug Fixes

* **netmonster:** expose the Google Maps key option in the patch list ([0a064e7](https://github.com/totsiaw/proxma-patches/commit/0a064e7103ef85140649eeac472178b5cb6ae3b9))
* **netmonster:** retarget premium patch to 5.0.0 / 5.0.1 ([3e9a6ab](https://github.com/totsiaw/proxma-patches/commit/3e9a6abc5466a7333fb51da70fe5239a028fb171))
* **simosa:** claim the daily check-in reward as soon as the state loads ([e9c07ad](https://github.com/totsiaw/proxma-patches/commit/e9c07ade230961213bb1f2d13ed8c35351075577))

### New Features

* **indrive:** add remove-ads patch for sinet.startup.inDriver 5.188.0 / 5.189.0 ([2c7a0a8](https://github.com/totsiaw/proxma-patches/commit/2c7a0a832496a2d221e250e8564cb6a167d16968))

## [1.17.0](https://github.com/totsiaw/proxma-patches/compare/1.16.1...1.17.0) (2026-10-17)

### New Features

* **olx:** add OLX India remove-ads and remove-Elite-promotions patches ([4632596](https://github.com/totsiaw/proxma-patches/commit/463259672e3467319a9011283b2cd97c7bf0a751)), closes [#24](https://github.com/totsiaw/proxma-patches/issues/24)

## [1.16.1](https://github.com/totsiaw/proxma-patches/compare/1.16.0...1.16.1) (2026-10-14)

### Bug Fixes

* foodpanda — build the toggle row without the app's classes ([5b3d0a1](https://github.com/totsiaw/proxma-patches/commit/5b3d0a1d240156b398d02ec1a418e40aa3df673e))
* foodpanda — ship the dark mode toggle's classes in the bundle ([0ad4af6](https://github.com/totsiaw/proxma-patches/commit/0ad4af65a100658be769ff390df37d2215ad8ff4))

## [1.16.0](https://github.com/totsiaw/proxma-patches/compare/1.15.1...1.16.0) (2026-10-14)

### Bug Fixes

* foodpanda — add second anti-tamper layer short-circuiting the block-screen orchestrator ([88e93d3](https://github.com/totsiaw/proxma-patches/commit/88e93d33e250e6472aba8a12676a0a02899f6434))
* foodpanda dark mode — empty FIELD_OVERRIDES so remapped fields actually darken ([2fcdeab](https://github.com/totsiaw/proxma-patches/commit/2fcdeabe648fafdbfabf532d35e1318517881fb3))
* storywise — block force-update prompt ([76b5bf9](https://github.com/totsiaw/proxma-patches/commit/76b5bf9ff6ba965d76eb26f2687185d46a0fe75e))
* storywise — embedded credential restore (sw_cred.json) in bypass login ([a16dac8](https://github.com/totsiaw/proxma-patches/commit/a16dac8abec927ef03f2d855498556d7a1a3c863))
* storywise — substitute Google sign-in with anonymous auth (A3) ([c3caba3](https://github.com/totsiaw/proxma-patches/commit/c3caba31289564a882e8041b5abd537b654fe8dc))
* storywise — use real signInAnonymously chain in bypass login ([7b4a129](https://github.com/totsiaw/proxma-patches/commit/7b4a129c9712ced0fdfa6db0c684fe883af0bf32))

### New Features

* foodpanda — dark mode toggle in settings ([ffa90b1](https://github.com/totsiaw/proxma-patches/commit/ffa90b1b8a9ab0992e1c1631a8fa6c700d6011fb))
* storywise — bypass login and unlock pass ([af36c44](https://github.com/totsiaw/proxma-patches/commit/af36c44897297f51c6cfafd5c611d4bddb61edd1))

## [1.15.1](https://github.com/totsiaw/proxma-patches/compare/1.15.0...1.15.1) (2026-10-13)

### Bug Fixes

* foodpanda — add second anti-tamper layer short-circuiting the block-screen orchestrator ([c7ffeb9](https://github.com/totsiaw/proxma-patches/commit/c7ffeb91bcd6b2883cc1ba35f10292d30d1dc949))

## [1.15.0](https://github.com/totsiaw/proxma-patches/compare/1.14.0...1.15.0) (2026-10-12)

### New Features

* foodpanda — remove home/reels ad banners, CrossSell popup, and forced review prompt ([d8df775](https://github.com/totsiaw/proxma-patches/commit/d8df775652a805e579e4cff95b4c6d59e361f4c5))

## [1.14.0](https://github.com/totsiaw/proxma-patches/compare/1.13.0...1.14.0) (2026-10-07)

### New Features

* add foodpanda order-tracking ad + PandaMart popup removal ([804b285](https://github.com/totsiaw/proxma-patches/commit/804b2854313bf446f327883158562aebd0d29877))
* add Safar (com.safar.fyi) ad removal ([299dc68](https://github.com/totsiaw/proxma-patches/commit/299dc68b66bdd3978784079923aef927605a5cc7))
* bypass PairIP license check for Safar re-signed builds ([e00e097](https://github.com/totsiaw/proxma-patches/commit/e00e0972c78b36b789b72a8fac1b89326d657fe9))
* update NetMonster premium unlock for 4.0.4 ([5ae6b63](https://github.com/totsiaw/proxma-patches/commit/5ae6b63e5dc8771c171f2d59632aae8622ea0c88))

## [1.13.0](https://github.com/totsiaw/proxma-patches/compare/1.12.1...1.13.0) (2026-09-30)

### New Features

* add foodpanda Bypass anti-tamper + Unlock premium patches ([73da778](https://github.com/totsiaw/proxma-patches/commit/73da778994bc5973d0bdd56fec7365c3ae9c9d03))
* **simosa:** remove day-10/20/30 check-in milestone RewardedAd ([8946da4](https://github.com/totsiaw/proxma-patches/commit/8946da4f1f764e29f6b120f6bad3f01f09be88f2))

## [1.12.1](https://github.com/totsiaw/proxma-patches/compare/1.12.0...1.12.1) (2026-09-21)

### Bug Fixes

* **netmonster:** retarget Unlock premium for 3.4.3 ([8beed38](https://github.com/totsiaw/proxma-patches/commit/8beed389993f2760daa2d859564d119a74d54cd9))

## [1.12.0](https://github.com/totsiaw/proxma-patches/compare/1.11.0...1.12.0) (2026-09-21)

### New Features

* **simosa:** split check-in ad removal into its own patch ([2797106](https://github.com/totsiaw/proxma-patches/commit/27971066a76b9ee8693588a6da389fd9fcbbb43e)), closes [morphe-manager#616](https://github.com/totsiaw/morphe-manager/issues/616) [#616](https://github.com/totsiaw/proxma-patches/issues/616)

## [1.11.0](https://github.com/totsiaw/proxma-patches/compare/1.10.0...1.11.0) (2026-09-21)

### New Features

* **olx:** add Remove ads patch ([02b00d9](https://github.com/totsiaw/proxma-patches/commit/02b00d968b6e0ec169bc1280775db9cc19a070d6))

## [1.10.0](https://github.com/totsiaw/proxma-patches/compare/1.9.0...1.10.0) (2026-09-21)

### New Features

* **olx:** add Force dark mode patch ([5cf94c1](https://github.com/totsiaw/proxma-patches/commit/5cf94c1481102a5eeff72e8cc7a6d362d5edf6bf))

## [1.9.0](https://github.com/totsiaw/proxma-patches/compare/1.8.0...1.9.0) (2026-09-21)

### Bug Fixes

* **simosa:** target Simosa 3.3.4.2 ([0363c02](https://github.com/totsiaw/proxma-patches/commit/0363c026d685ecc5e2b333f4e5b4dd04325aa727))

### New Features

* **simosa:** remove daily check-in / SocialPlus feed ads ([0c962f7](https://github.com/totsiaw/proxma-patches/commit/0c962f7b8c38cdb1e80e147b3d87defe2a97fd21))

## [1.8.0](https://github.com/totsiaw/proxma-patches/compare/1.7.0...1.8.0) (2026-07-19)

### New Features

* add mtproxy Remove ads patch ([a35a449](https://github.com/totsiaw/proxma-patches/commit/a35a449731fad72f7e7634cced08c7164fb631a9))

## [1.7.0](https://github.com/totsiaw/proxma-patches/compare/1.6.0...1.7.0) (2026-07-19)

### New Features

* add mytelenor Bypass device validation patch ([7d9a1a7](https://github.com/totsiaw/proxma-patches/commit/7d9a1a76c66a1bc436a9998ea3f18d5d1cb52b39))

## [1.6.0](https://github.com/totsiaw/proxma-patches/compare/1.5.1...1.6.0) (2026-07-17)

### New Features

* add mytelenor patches (Remove ads, Block trackers, manifest flags) ([47b6550](https://github.com/totsiaw/proxma-patches/commit/47b65503d78f254b9d8517051ef17dac33107e16))

## [1.5.1](https://github.com/totsiaw/proxma-patches/compare/1.5.0...1.5.1) (2026-07-14)

### Bug Fixes

* delist Daraz — remove patches (unusable on re-signed builds) ([e6cd42e](https://github.com/totsiaw/proxma-patches/commit/e6cd42ef5c4e43adb7fbab16d303b809d197740c))

## [1.5.0](https://github.com/totsiaw/proxma-patches/compare/1.4.1...1.5.0) (2026-07-13)

### New Features

* Investify unlock premium + PairIP bypass ([20fc9b5](https://github.com/totsiaw/proxma-patches/commit/20fc9b52bf743634826ee77eaca18bac91b1d880))
* NetMonster unlock premium ([d4de8f8](https://github.com/totsiaw/proxma-patches/commit/d4de8f8de83083d53c588436f8f1612cee81dbd5))

## [1.4.1](https://github.com/totsiaw/proxma-patches/compare/1.4.0...1.4.1) (2026-07-10)

### Bug Fixes

* mark Daraz patch as WIP ([70e51cd](https://github.com/totsiaw/proxma-patches/commit/70e51cdba01af8c738bb9b96156abc3e3523fa2a))

## [1.4.0](https://github.com/totsiaw/proxma-patches/compare/1.3.1...1.4.0) (2026-07-10)

### New Features

* Daraz (com.daraz.android) remove analytics & tracking ([4ddb59c](https://github.com/totsiaw/proxma-patches/commit/4ddb59c0e76e74845ca816ad464b01fb89639cdb))

## [1.3.1](https://github.com/totsiaw/proxma-patches/compare/1.3.0...1.3.1) (2026-07-10)

### Bug Fixes

* compile errors in unlock-daily-reward patch ([4628acc](https://github.com/totsiaw/proxma-patches/commit/4628acc9000d94f0bff8eb5180e0cff94723fcc0))
* MyZong daily reward — remove the popup too, claim directly ([578ef3d](https://github.com/totsiaw/proxma-patches/commit/578ef3d820c6375847ef48ee57eb21002be68362))

## [1.3.0](https://github.com/totsiaw/proxma-patches/compare/1.2.2...1.3.0) (2026-07-10)

### New Features

* MyZong — unlock daily reward without watching ad ([1bae4e5](https://github.com/totsiaw/proxma-patches/commit/1bae4e5ede5bbc2bd029b2d23108114b06bbeda9))

## [1.2.2](https://github.com/totsiaw/proxma-patches/compare/1.2.1...1.2.2) (2026-07-10)

### Bug Fixes

* MyZong — stub AppOpenAd (launch ad slipped past v1.2.1) ([a4113dc](https://github.com/totsiaw/proxma-patches/commit/a4113dce237c55d202b2c785b568ea9fa147a882))

## [1.2.1](https://github.com/totsiaw/proxma-patches/compare/1.2.0...1.2.1) (2026-07-09)

### Bug Fixes

* MyZong — fully kill AdMob (auto-init provider + all ad loads) ([5a092ad](https://github.com/totsiaw/proxma-patches/commit/5a092adb888015fb92d01e1cce1318387481f3fe))

## [1.2.0](https://github.com/totsiaw/proxma-patches/compare/1.1.0...1.2.0) (2026-07-09)

### New Features

* MyZong — stub AppsFlyer/TikTok SDK init too ([7e17a9c](https://github.com/totsiaw/proxma-patches/commit/7e17a9cd9b60c3af0d52f55ef08a5fb588512015))

## [1.1.0](https://github.com/totsiaw/proxma-patches/compare/1.0.0...1.1.0) (2026-07-09)

### New Features

* MyZong (com.zong.customercare) remove ads & tracking ([09513ad](https://github.com/totsiaw/proxma-patches/commit/09513ad8cf0fcd776eaaf24feefd4a207676897b))

## 1.0.0 (2026-07-09)

### New Features

* Proxma patches for Simosa (Jazz World) ([6d3db83](https://github.com/totsiaw/proxma-patches/commit/6d3db837b0c6c15591efc881ad221ff979c56359))
