# Reader Refactor Progress

## 2026-05-20 Source Quality Routing And Seed Scoring

- In progress: replacing pure hard-coded source priority with a persistent
  source-quality routing model.
- Design document added:
  `docs/SOURCE_QUALITY_ROUTING_DESIGN.md`.
- Runtime model target:
  code-shipped `source-quality-seed-v1.tsv` provides global source
  `tier/bucket/base score`; MMKV stores only local source and book-source
  deltas. A new install therefore starts with a usable global ranking, while
  real user/runtime evidence adapts that ranking locally.
- Scoring boundary agreed in this pass:
  wrong latest chapters and western/foreign-tail pollution are common on
  fast-updating sites, so they are not treated as catastrophic global source
  failures. Tail trim scoring rewards newly verified readable chapters and only
  lightly penalizes the bad tail count, while same-aligned sources still rank by
  fewer bad tail chapters.
- Cover boundary agreed in this pass:
  missing cover is not a strong source-quality failure. It should only lightly
  affect the source/book-source score because some good reading sources do not
  expose covers. Product UX still requires visible covers in search/detail/shelf,
  so cover must be dynamically filled from other tier 1/2/3 sources and cached.
- Current implementation checkpoint:
  `SourceQualityRouter` now accepts a seed profile plus MMKV-backed local
  storage, and unit tests are being added for seed parsing, waterfall breadth,
  local persistence, adult/fanfic boundaries, and tail scoring behavior.
- Seed generation checkpoint:
  added `tools/source-quality/generate-source-quality-seed.mjs` and generated
  `app/src/main/assets/source-quality-seed-v1.tsv` from the current
  `device-book-sources.json` plus the latest 100-book probe evidence. The seed
  currently contains 2214 compatible searchable sources:
  tier1=5, tier2=255, tier3=1954.
- Validation status:
  full unit/build and debug APK build passed in this pass. Broader 100-book
  batch probe and selected real-device UI revalidation remain pending.
- Batch checkpoint:
  `build/tmp/probe-100-after-source-quality-seed.tsv` completed in about
  60 minutes. Raw status was PASS=49, WARN=40, FAIL=11. After separating
  cover-only WARNs from source-quality failures, 34 WARNs are cover fallback
  obligations rather than bad reading-source signals. Remaining quality issues:
  9 short-catalog cases, 8 last-chapter-unreadable cases, 4 no-readable-sample
  cases, and 8 weak PASS cases with catalog count <= 60. High-priority
  follow-up books: `我在修仙界万古长青`, `后宫甄嬛传`, `偷偷藏不住`, `坤宁`,
  `镇魂`, `道诡异仙`, `最佳导演`, `明朝那些事儿`.
- Interaction timing checkpoint:
  search now separates first-visible publishing from continued source
  discovery. It can publish multi-source trusted groups without synchronously
  fetching detail/catalog/content for every candidate, while the same query
  waterfall keeps running and can publish later result batches. Detail shows
  direct metadata/catalog first. Heavy catalog tail trimming, content belonging
  checks, and stronger book-source scoring are left for reading/catalog/
  shelf-intent paths.
- Generic exact-title ranking checkpoint:
  removed the temporary known-title/known-author rescue path. Search is now a
  progressive waterfall session: it may publish the first trusted multi-source
  batch early, but source discovery continues under the same query lifecycle
  and can publish later batches. Multi-source prefix/same-title groups are
  capped below exact-title ranking instead of being deleted, so users can choose
  among real consensus books without a book-name whitelist. Actual source search
  no longer expands from the hot-word list; hot words are only suggestions.
- Exact-title MCP JSON-RPC spot check:
  installed the debug APK through MCP `tools/call`, launched
  `SplashActivity`, and searched `破云` and `斩神` through native
  `input-text`. The visible first results were exact titles:
  `破云` and `斩神 / 天刈留香`. App-pid error logcat was empty.
- Per-book source routing checkpoint:
  global tier 1/2/3 remains an immutable cold-start waterfall. Each book now
  has one learned personal tier in front of the global waterfall. That personal
  tier is built only from this book's own runtime evidence, sorted by
  book-source score, and can both add sources after successful catalog/content
  validation and remove them after repeated failures, catalog disorder,
  unreadable content, or polluted-tail penalties. Search, detail fallback,
  cover fallback, and content fallback now ask the router for the book-specific
  waterfall before falling back to the global tier 1/2/3 order.
- Validation checkpoint after the personal-tier change:
  targeted tests passed for `BookSearchRankerTest`, `SourceQualityRouterTest`,
  `SourceEngineReaderContentProviderTest`, and
  `SourceEngineIsolationContractTest`. Full
  `:source-engine:test :app:testDebugUnitTest :app:assembleDebug` also passed.
  The 100-book probe harness was corrected to evaluate the final readable
  catalog after tail trimming and to reject title-mismatched candidates instead
  of treating long wrong-book catalogs as success.
- Remaining probe findings:
  `我在修仙界万古长青` has a real 525-chapter source whose first 517 chapters are
  readable after tail trimming, so it should be a final-reading success once the
  probe/app selection prefers readable-tail coverage over short catalogs.
  `玄鉴仙族` currently has exact-title sources that are either short but readable
  or long but unreadable in sampled content; it remains the active source
  coverage/final-read failure to fix or document with evidence.
- Probe evidence boundary:
  local JVM probes run from the Windows host, whose network may differ from the
  phone because the computer is behind VPN. These probes are useful for ranking
  logic and candidate-shape debugging, but final source availability and final
  reading success must be verified on the Android device through MCP JSON-RPC
  `tools/call`, using the app's actual runtime/network path.
- Real-device checkpoint with `@mobileaidev/ai-app-bridge` / Android bridge
  `0.2.0` on OnePlus PKR110:
  `玄鉴仙族` search result appeared in about 5.6s and
  `我在修仙界万古长青` in about 3.3s after the search/detail split, with no app
  error logs from MCP logcat. `玄鉴仙族` detail resolved to the correct author
  and a repaired latest chapter boundary, but remains a speed/tier tuning case.
- Final real-device checkpoint for this pass used MCP JSON-RPC `tools/call`
  against the Android app runtime on OnePlus PKR110, not host-side probes. The
  app now uses OkHttp-backed source-engine fetches so MCP network capture sees
  actual source requests. `玄鉴仙族` search showed the catalog-backed result in
  6s (`ranked_1`, `count_1`, three `detail-catalog` validation events,
  `chapters_1541`). Opening detail now runs the same readable-tail boundary
  used by reading/catalog: raw 1541 chapters were trimmed to 1539 readable
  chapters, first removed `第1496章 无央...`, and both detail and catalog now
  show verified latest `第1495章 玄体...` instead of the unverified `第1497章`.
  Reading opened `ReadActivity` and rendered `第1章 初入`; the catalog drawer
  opened in 1s and displayed ordered chapters starting `第1章 初入`,
  `第2章 李家`, `第3章 鉴子`. Evidence artifacts:
  `build/device-final-proof-direct/28-r08-final-xuanjian-full-flow.json`,
  `28-r08-final-xuanjian-read.png`, and
  `29-r08-final-xuanjian-catalog.png`.
- Secondary real-device checkpoint: `我在修仙界万古长青` search showed the
  catalog-backed `快餐店` result in 4s (`ranked_1`, `count_1`,
  `chapters_525`). Detail/catalog resolved to 524 readable chapters, trimming
  the single unreadable tail entry `第521章 诚不我欺，翻手为云` and showing
  verified latest `第520章 威逼仙医，长青道丹`. Reading opened `ReadActivity`
  and rendered existing shelf progress at `第514章 九龙呈威...`. Evidence
  artifacts: `build/device-final-proof-direct/31-r09-wangu-search.json`,
  `32-r09-wangu-detail-read.json`, and `32-r09-wangu-read.png`.
- 100-book real-device validation is now running through MCP JSON-RPC
  `tools/call` on OnePlus PKR110. Interim checkpoint at case #50:
  PASS=45, WARN=4, FAIL=1. Evidence is being written to
  `build/device-100-real/device-100-real-results.json`,
  `build/device-100-real/device-100-real-results.tsv`, and per-book read
  screenshots under `build/device-100-real/screenshots/`. Confirmed issues so
  far are source coverage for `雪中悍刀行` (`raw_2_count_0` after catalog-backed
  search filtering), a catalog-drawer proof gap for `九鼎记`, and same-title
  candidate quality for `将夜`, `学霸的黑科技系统`, and `重生之财源滚滚`, where
  the top readable result is a shorter same-title book by another author even
  though longer same-title catalogs were observed.
- Runtime defect fixed in this pass: long catalogs containing both ordinal
  chapters and non-ordinal entries could crash Kotlin sort with
  `IllegalArgumentException: Comparison method violates its general contract!`.
  `ChapterListFusion` now preserves original order whenever a long catalog
  contains non-ordinal entries or ordinal restarts, and has a regression test
  covering `卷首杂谈` inside a 1200-chapter catalog.
- Cover checkpoint:
  search must not show an empty cover. The app already uses generated title
  covers as a visible fallback, and real-cover fallback is kept as a required
  background completion/caching obligation rather than a reason to heavily
  demote otherwise good reading sources.
- Dependency checkpoint:
  Gradle plugin/runtime were migrated from the old `ldpGitHub` bridge artifact
  to `com.github.mobileAiDev.ai-app-bridge:...:0.2.1` and plugin id
  `io.github.mobileaidev.aiappbridge.android`.

## 2026-05-19 Source Engine Full-Chain Retest

- Re-ran the source-engine migration against the user-facing path instead of
  only the lab path. Runtime interaction used AI Bridge MCP for install, view
  tree, input, taps, screenshots, and logcat; the only non-MCP runtime step was
  launching the app because the MCP tool surface has no generic activity-launch
  command.
- Fixed search ranking and cover selection around the contract the product now
  depends on: the best title group is ranked first by multi-source consensus,
  then a usable source inside that group is selected for reading, while a
  verified cover can be borrowed from another source in the same group. This
  keeps partial title searches such as `斗破` from being beaten by unrelated
  derivatives, and avoids blank covers when another source already has a valid
  image for the same book.
- Expanded real-device search validation from one book to 20 representative
  books. The first result was correct for: `斗破`, `诡秘`, `凡人`, `遮天`,
  `完美世界`, `牧神记`, `大奉`, `剑来`, `雪中`, `庆余年`, `将夜`,
  `择天记`, `全职高手`, `盗墓笔记`, `鬼吹灯`, `斗罗大陆`, `神印王座`,
  `星辰变`, `吞噬星空`, and `盘龙`. The expected canonical book was first in
  each case; covers were present for the first result, with the lowest observed
  first-page cover count being `择天记` at 1 and most high-confidence books at
  6-9 visible covers.
- Added author-query validation because author search exercises a different
  ranking failure mode. Real-device MCP searches for `天蚕土豆`, `辰东`,
  `猫腻`, `我吃西红柿`, `唐家三少`, `耳根`, `烽火戏诸侯`, and
  `爱潜水的乌贼` ranked that author's works ahead of unrelated title matches.
- Fixed catalog fusion for volume restart catalogs. `诡秘之主` previously
  collapsed to 269-285 chapters because later volumes restarted at `第一章` and
  were mistaken for recent-update prefixes. The fusion layer now tracks ordinal
  cycles, preserves same-ordinal chapters with different titles, and only drops
  a recent-update prefix when the prefix itself is a short high-ordinal list
  followed by a main catalog restart.
- Fixed two other catalog cleanup failures found during device testing:
  announcement rows such as `今天和明天都只有一更（中午已更）` are removed, and a
  small restarted side-story tail after a terminal main chapter is trimmed so
  `斗破苍穹` ends at `第一千六百二十三章 结束，也是开始` instead of a later
  side-story `第十五章`.
- Added intro cleanup for malformed scraped metadata. `庆余年` detail once
  displayed an `og:image` HTML fragment as the book intro; source-engine detail
  now rejects those fragments instead of showing raw page metadata to users.
- Device detail/catalog/content spot checks after the fixes:
  `诡秘之主` resolved 1396 chapters from `第一章 绯红` to
  `第四十一章 新的旅程`, and the first two chapters scored 90/100 and 90/100
  quality/coherence with no pollution markers. `斗破苍穹` resolved 1646
  chapters from `第一章 陨落的天才` to
  `第一千六百二十三章 结束，也是开始`. `凡人修仙传` resolved 2476 chapters and
  the first two sampled chapters scored 100/100 and 100/100. `庆余年` resolved
  716 chapters and the first two sampled chapters scored 96/100 and 100/100.
  `剑来` resolved 1293 chapters and sampled content scored 92/100 and 100/100.
  `全职高手` resolved 1762 chapters and sampled content scored 100/100 and
  92/100. All sampled content markers were `none`.
- The chapter-content splice detector is now a deterministic, replaceable
  `ContentBelongingChecker` boundary. It looks for the common novel-site
  failure where the first few hundred characters belong to the requested book
  but later paragraphs drift into another novel or chapter; the interface can be
  replaced later by a local model-backed checker without changing the app
  routing layer.
- Final validation command passed:
  `.\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx3072m" :source-engine:test :app:testDebugUnitTest :app:assembleDebug`.
- No new confirmed AI Bridge MCP defect was found during this retest, so
  `C:\CompanyProject\ai-app-bridge\docs\KNOWN_ISSUES.md` was not updated in
  this pass.

## 2026-05-18 Source Engine Default Migration

- Switched the normal reader path to the local `source-engine` provider instead
  of the old backend spider path. Search, detail, catalog, and chapter content
  now route through `BookContentProviderRouter` and source-engine book/chapter
  route IDs.
- Reworked search ranking around title-group consensus: books found by multiple
  sources rank ahead of single-source derivatives, partial-title searches such
  as `斗破` put `斗破苍穹` first, and the selected reading source is separated
  from the selected cover source so a good cover can be reused from another
  source in the same title group.
- Added real cover validation and fallback selection. Placeholder/lazy/no-cover
  URLs are rejected, image dimensions are checked, and detail/search pages use a
  shared cover loader with a local placeholder only when no verified cover is
  available.
- Added catalog fusion cleanup for recent/latest chapter prefixes, duplicate
  entries, announcement/new-book rows, trailing non-chapter extras, and
  non-ordinal prologue ordering.
- Added deterministic content quality and belonging checks, including detection
  for the common failure where a chapter starts correctly but later embeds a
  different novel/chapter. The checker is behind `ContentBelongingChecker` so it
  can later be replaced by a local model-backed implementation.
- Fixed source-engine reading cache writes by mapping long route IDs and unsafe
  chapter titles to stable filesystem-safe cache keys. The full route payload is
  still kept for source-engine decoding; only the on-disk path segment is
  hashed.
- Validation:
  `:source-engine:test :app:testDebugUnitTest :app:assembleDebug` passed.
  Installed the APK through AI Bridge MCP and tested on OnePlus PKR110. Search
  `斗破` showed `斗破苍穹` first with a real cover; detail showed title
  `斗破苍穹`, author `天蚕土豆`, latest chapter
  `第一千六百二十三章 结束，也是开始`, and the cover rendered. Reading opened
  `第一章 陨落的天才` and displayed book text instead of staying on loading. The catalog
  drawer visibly started at `第一章 陨落的天才`, `第二章 斗气大陆`, `第三章 客人`
  and continued in order.
- Full source-engine lab validation via MCP passed: `sources=6500`,
  `searchable=3223`, `testedSources=77`, `canonicalChapters=1618`,
  `duplicateChapters=41`, `sampledChapters=5`,
  `minContentQualityScore=92`, `minContentCoherenceScore=100`, and all sampled
  content markers were `none`, including chapters 1616, 1617, and 1618.
- AI Bridge MCP issues found during this validation were recorded in
  `C:\CompanyProject\ai-app-bridge\docs\KNOWN_ISSUES.md`.

## 2026-05-10

- Kept the first refactor slice limited to home, home overflow menu, and login
  page resources.
- Updated the local ai-app-bridge integration from `0.1.2` to `0.1.3` so this
  app can verify multi-window view-tree/tap behavior and redacted network
  capture.
- Reduced the home toolbar and bookshelf item density. The goal is a cleaner,
  lighter bookshelf without changing bookshelf data loading or reading logic.
- Removed inert overflow menu entries that had empty handlers or commented-out
  routes: message, download, Wi-Fi transfer, feedback, night mode, and settings.
- Fixed the menu title update to target `action_login` directly instead of
  assuming a fixed menu index.
- Validation after the second UI pass:
  `:app:testDebugUnitTest` passed, `:app:assembleDebug` passed, APK install
  succeeded, `/v1/status` reported ai-app-bridge `0.1.3`, `/v1/view/tree`
  reported the home subtitle as `effectiveVisible=true`, and logcat did not
  show `FATAL EXCEPTION` or `AndroidRuntime` crash output.
- Bridge loop validation from this app: the overflow menu appeared as a
  `popup` window root, bridge tap on the first menu row hit `windowType=popup`
  and opened `LoginActivity`, and a synthetic sensitive network payload was
  redacted by the bridge capture layer.

## 2026-05-10 Home Redesign Pass 2

- Reworked the home page from a single oversized toolbar/list into a lighter
  bookshelf surface: compact toolbar, shelf summary panel, section label, tighter
  book rows, redesigned empty state, and dashed add-row footer.
- Kept bookshelf data, sync, local scan, search, and read-entry logic unchanged.
- Removed the home RecyclerView divider decoration; spacing is now controlled by
  item layout margins and subtle row backgrounds.
- Validation: `:app:testDebugUnitTest` passed, `:app:assembleDebug` passed, APK
  install succeeded, `/v1/status` reported ai-app-bridge `0.1.3`,
  `/v1/view/tree` reported `我的书架`, `继续阅读`, `书架`, and add-row text as
  `effectiveVisible=true`, overflow menu still appeared as a `popup` window
  root, and logcat did not show `FATAL EXCEPTION` or `AndroidRuntime` crash
  output.
- Screenshot artifact: `C:\project\reader\build\codex-home-redesign-20260510-r2.png`.
- Next cleanup candidates: trace deprecated Zhuishushenqi/search-category pages
  from actual navigation entry points, then remove only unreachable UI routes in
  small slices with compile and device checks.

## 2026-05-10 Home Overall Style Pass 3

- Shifted the home screen to a reader-app bookshelf pattern without copying a
  specific app: light toolbar, large reading prompt panel, search entry, quick
  actions, continue-reading card, and a stronger book-row hierarchy.
- Added first-class home quick actions for search, local import, and bookshelf
  sync. Search opens `SearchActivity`; import opens `FileSystemActivity`; sync
  posts the existing `BookSyncEvent` instead of creating a new sync path.
- Moved the home status bar to dark icons on the light background while keeping
  the transparent status-bar behavior local to `MainActivity`.
- Added vector quick-action icons and expanded
  `HomeUiResourceContractTest` so the new home surface IDs and drawables stay
  bound by unit tests.
- Validation: `:app:testDebugUnitTest --tests
  com.ldp.reader.ui.HomeUiResourceContractTest` passed, `:app:assembleDebug`
  passed, APK install succeeded, `/v1/status` reported `MainActivity` with
  ai-app-bridge app bridge `0.1.4`, `/v1/view/tree` reported `今晚读点什么`,
  `搜索书名或作者`, `找书`, `导入`, `同步`, `继续阅读`, and `我的书架`
  as visible, and logcat did not show `AndroidRuntime` or `FATAL EXCEPTION`.
- Bridge interaction validation: `tap-text 找书` opened `SearchActivity`,
  `tap-text 导入` opened `FileSystemActivity`, and `tap-text 同步` returned to
  `MainActivity` while bridge network capture advanced, proving the existing
  sync path was invoked.
- Screenshot artifact:
  `C:\project\reader\build\codex-home-redesign-20260510-r6.png`.
- Checkpoint rule from this point forward: after each verified small step,
  update this progress document and create a git commit as a rollback point.

## 2026-05-10 Login Page Polish Pass 1

- Rebuilt the login page as the second ordered refactor slice, keeping the
  existing SMS/direct-login/logout behavior intact.
- Added a clear header, form title/subtitle, icon-led phone and code rows,
  stronger primary/secondary button hierarchy, and a separate logged-in state
  panel. The logged-in and logged-out branches now share the same home visual
  system instead of looking like a placeholder page.
- Adjusted `LoginActivity` status-bar handling to use dark icons on the light
  login background.
- Added `LoginUiResourceContractTest` to pin the login root, header, form rows,
  logged-in state text, input controls, buttons, and new login drawables.
- Validation: the new login resource contract first failed on missing IDs and
  drawables, then passed after the layout/resources were added. Full
  `:app:testDebugUnitTest` passed, `:app:assembleDebug` passed, APK install
  succeeded, and logcat did not show `AndroidRuntime` or `FATAL EXCEPTION`.
- Bridge validation: direct shell start of `LoginActivity` was rejected because
  the Activity is not exported, so validation used the real app path: start
  `MainActivity`, open the overflow menu, tap the account row, and enter
  `LoginActivity`. `/v1/status` reported `LoginActivity`; `/v1/view/tree`
  verified the logged-in panel visible and the hidden form branch
  `visible=false`. After backing up SharedPreferences, `tap-text 退出登录`
  verified the logged-out form visible and the logged-in panel hidden; the
  saved token/userName/loginType were restored afterward.
- Screenshot artifacts:
  `C:\project\reader\build\codex-login-redesign-20260510-r1.png` and
  `C:\project\reader\build\codex-login-redesign-20260510-r2-logged-out.png`.
- AI App Bridge note: while validating this slice, the device was being used
  manually and `screenshot --package-name` could still capture the Android
  launcher. This is recorded in
  `C:\CompanyProject\ai-app-bridge\docs\KNOWN_ISSUES.md`; reader validation now
  explicitly checks foreground activity/tree before trusting screenshots.

## 2026-05-10 Deprecated Zhuishu Cleanup Pass 1

- Removed the first low-risk batch of obsolete Zhuishushenqi-era leftovers:
  commented Manifest activity entries and whole-commented legacy source stubs
  for the old discovery/community/book-list/ranking/download pages.
- Kept active search, book-detail, bookshelf, reading, local import, and login
  paths unchanged. This pass deliberately did not touch `BookApi`,
  `RemoteRepository`, or active adapters still referenced by current screens.
- Added `DeprecatedZhuishuCleanupContractTest`. The test first failed on the
  commented Manifest entries and existing stub files, then passed after removal.
- Validation: `:app:testDebugUnitTest --tests
  com.ldp.reader.cleanup.DeprecatedZhuishuCleanupContractTest` passed after the
  cleanup; full `:app:testDebugUnitTest :app:assembleDebug` passed; APK install
  succeeded.
- Bridge validation: started `com.ldp.reader/.ui.activity.MainActivity`
  explicitly, `ai-app-bridge status --package-name com.ldp.reader` reported
  `MainActivity` with app bridge `0.1.4`, `/v1/view/tree` verified `Reader`,
  `今晚读点什么`, `搜索书名或作者`, `找书`, `导入`, `同步`, and `我的书架`
  visible, and logcat did not show `AndroidRuntime` or `FATAL EXCEPTION`.
- AI App Bridge note: no new bridge-library issue was found in this pass. The
  existing foreground-activity/tree check remains required before trusting
  visual capture while the phone is also being used manually.

## 2026-05-10 Deprecated Zhuishu Cleanup Pass 2

- Removed the remaining whole-commented legacy source stubs: old discovery,
  community, ranking, book-list, and discussion contracts, `BillboardAdapter`,
  and `BaseTabActivity`.
- Expanded `DeprecatedZhuishuCleanupContractTest` so future whole-commented
  legacy stubs fail the cleanup contract instead of silently staying in source.
  The expanded test failed first, then passed after removal.
- Kept currently referenced adapters and resources in place for this pass:
  `BookListAdapter`, `HotCommentAdapter`, `CategoryAdapter`, read-page category
  resources, and active search/detail/read code were not changed.
- Validation: targeted cleanup contract passed; full
  `:app:testDebugUnitTest :app:assembleDebug` passed; APK install succeeded.
- Bridge validation: explicit `MainActivity` launch succeeded,
  `ai-app-bridge status --package-name com.ldp.reader` reported
  `MainActivity`, `/v1/view/tree` verified the home nodes still visible, and
  logcat had no `AndroidRuntime` or `FATAL EXCEPTION` output.
- AI App Bridge note: no new bridge-library issue was found in this pass.

## 2026-05-10 Deprecated Zhuishu Cleanup Pass 3

- Removed unreachable legacy UI layer files for old ranking, category, book-list,
  discussion/community, and download-panel pages: adapters, holders, and their
  page/item/header layouts.
- Expanded `DeprecatedZhuishuCleanupContractTest` to cover those old UI-layer
  files. The test failed before deletion, then passed after the cleanup.
- Corrected one false-positive cleanup candidate: `fragment_refresh_list.xml`
  is still used by the active search page, so it was restored and removed from
  the retired-resource list.
- Kept active `BookListAdapter`, `HotCommentAdapter`, `CategoryAdapter`, search,
  detail, reading, bookshelf, login, and local import code intact for this pass.
- Validation: targeted cleanup contract passed; full
  `:app:testDebugUnitTest :app:assembleDebug` passed; APK install succeeded.
- Bridge validation: started `MainActivity`, tapped the home quick action
  `找书`, and `ai-app-bridge status --package-name com.ldp.reader` reported
  `SearchActivity`. `/v1/view/tree` showed search-page text such as `热门搜索`
  and `换一批` visible, and logcat had no `AndroidRuntime` or
  `FATAL EXCEPTION` output.
- AI App Bridge note: no new bridge-library issue was found in this pass.

## 2026-05-10 Deprecated Zhuishu Cleanup Pass 4

- Removed the active book-detail page's remaining Zhuishushenqi-era community
  sections: hot comments, community/post count, recommended book lists, old
  reader-count/update-word stats, their adapters/holders, package beans, item
  layouts, API declarations, repository wrappers, and view contract callbacks.
- Simplified `activity_book_detail.xml` to the still-active detail surface:
  cover, title, author, chase/read actions, and description. Current bookshelf
  and reading paths remain unchanged.
- Expanded `DeprecatedZhuishuCleanupContractTest` so this detail cleanup fails
  if the removed files, old API tokens, old detail view IDs, or old string
  resources return. The expanded test failed before the implementation and
  passed after cleanup.
- Validation: targeted cleanup contract passed; full
  `:app:testDebugUnitTest :app:assembleDebug` passed; APK install succeeded.
- Bridge validation: started `MainActivity`, used visible bookshelf entry
  `仙人消失之后` to enter `ReadActivity`, opened the top menu, tapped `简介`,
  and `ai-app-bridge status --package-name com.ldp.reader` reported
  `BookDetailActivity`. `wait-text` verified `书籍详情`, `简介`, and
  `继续阅读`; UIAutomator tree search found no `追书人数`, `热门书评`,
  `推荐书单`, `社区`, `日更新字数`, or `读者留存率`; logcat had no
  `AndroidRuntime` or `FATAL EXCEPTION` output.
- AI App Bridge note: found and recorded a new bridge-library issue in
  `C:\CompanyProject\ai-app-bridge\docs\KNOWN_ISSUES.md`: `tap-text` can return
  `ok=true` for a bridge-tree node whose tap center is outside the current
  viewport. Workaround for reader validation is to use UIAutomator-visible taps
  and verify the resulting activity/text after every navigation tap.

## 2026-05-10 Deprecated Zhuishu Cleanup Pass 5

- Removed unreachable legacy Zhuishushenqi remote API declarations and
  `RemoteRepository` wrappers for community posts/details/comments, rankings,
  categories, theme book lists, old book detail, and tag search.
- Kept the still-referenced legacy endpoints intact: recommendations used by
  `BookShelfPresenter`, chapter metadata/content used by download/reading, and
  search hot-word/auto-complete used by `SearchActivity`.
- Expanded `DeprecatedZhuishuCleanupContractTest` so those unreachable remote
  layer tokens fail if they return. The new test failed first, then passed after
  the API/wrapper cleanup.
- Validation: targeted cleanup contract passed; full
  `:app:testDebugUnitTest :app:assembleDebug` passed; APK install succeeded.
- Bridge validation: launched `MainActivity`, verified `我的书架` and `找书`,
  tapped `找书` with UIAutomator-visible targeting, and verified `SearchActivity`
  content `热门搜索` and `换一批`; logcat had no `AndroidRuntime` or
  `FATAL EXCEPTION` output.
- AI App Bridge note: no new bridge-library issue was found in this pass.

## 2026-05-10 Deprecated Zhuishu Cleanup Pass 6

- Removed the unused legacy package/model batch that became dead after the
  remote-layer cleanup: old search result packages, Biquge detail/chapter beans,
  discussion/comment/ranking/category/book-list packages, and their stale
  imports or commented contract/repository references.
- Removed the last unreachable `BookApi.getSearchBookPackage` declaration. The
  active search page still uses hot-word/auto-complete plus the current search
  result model, so this pass did not touch `SearchActivity` behavior.
- Expanded `DeprecatedZhuishuCleanupContractTest` so these unused package beans,
  imports, and old comments fail if they return. The new test failed first, then
  passed after cleanup.
- Validation: targeted cleanup contract passed; full
  `:app:testDebugUnitTest :app:assembleDebug` passed; APK install succeeded.
- Bridge validation: launched `MainActivity`, `ai-app-bridge status
  --package-name com.ldp.reader` reported `MainActivity`, `wait-text` verified
  `我的书架` and `找书`, UIAutomator tapped `找书`, `SearchActivity` opened, and
  `wait-text` verified `热门搜索` plus `换一批`; logcat had no `AndroidRuntime` or
  `FATAL EXCEPTION` output.
- AI App Bridge note: no new bridge-library issue was found in this pass.

## 2026-05-10 Deprecated Zhuishu Cleanup Pass 7

- Removed the obsolete local cache layer for the retired Zhuishushenqi
  community/ranking/category/book-list features: `SaveDbHelper`, `GetDbHelper`,
  `DeleteDbHelper`, old cache methods in `LocalRepository`, old GreenDAO
  entities, generated DAOs, old ranking/category beans, and the unused
  comment-detail Rx zipper.
- Kept `LocalRepository` only for the still-active download-task path used by
  `DownloadService`: `saveDownloadTask` and `getDownloadTaskList`.
- Removed now-unused shared-preference and book-state constants that only
  supported the retired local cache.
- Expanded `DeprecatedZhuishuCleanupContractTest` so the old local cache
  sources, local repository methods, Rx helper, constants, and generated DAOs
  fail if they return. The new test failed first, then passed after cleanup.
- Validation: targeted cleanup contract passed; full
  `:app:testDebugUnitTest :app:assembleDebug` passed; APK install succeeded.
- Bridge validation: launched `MainActivity`, `ai-app-bridge status
  --package-name com.ldp.reader` reported `MainActivity`, `wait-text` verified
  `我的书架` and `找书`, UIAutomator tapped the existing bookshelf item
  `仙人消失之后`, `ReadActivity` opened, and logcat had no `AndroidRuntime` or
  `FATAL EXCEPTION` output.
- AI App Bridge note: no new bridge-library issue was found in this pass.

## 2026-05-10 Deprecated Zhuishu Cleanup Pass 8

- Removed retired discovery/community flags, sort/filter marker classes, old
  package beans, orphan event classes, tag selectors, billboard/review/section
  icons, and obsolete text styles that only supported the removed
  Zhuishushenqi pages.
- Removed the hidden read-page community menu hook and trimmed old
  community/find/book-list string arrays while keeping the active bookshelf,
  search, detail, download, and reading paths intact.
- Expanded `DeprecatedZhuishuCleanupContractTest` so the retired flags,
  constants, strings, hidden menu hook, and orphan resources fail if they
  return. The new test failed first, then passed after cleanup.
- Validation: targeted cleanup contract passed; full
  `:app:testDebugUnitTest :app:assembleDebug` passed; APK install succeeded.
- Bridge validation: launched `MainActivity`, `ai-app-bridge status
  --package-name com.ldp.reader` reported `MainActivity`, `wait-text` verified
  `我的书架`, `找书`, and the existing bookshelf item `仙人消失之后`;
  UIAutomator tapped that item, `ReadActivity` opened, and logcat had no
  `AndroidRuntime`, `FATAL EXCEPTION`, or bridge error output.
- AI App Bridge note: found and recorded one bridge-library ergonomics issue in
  `C:\CompanyProject\ai-app-bridge\docs\KNOWN_ISSUES.md`: `status
  --package-name` can expose a raw `socket hang up` when the target package is
  not started or the bridge is not ready yet. Workaround for reader validation
  is to explicitly launch the app component and retry status/text checks.

## 2026-05-10 Deprecated Zhuishu Cleanup Pass 9

- Removed the dead recommendation event/listener path: `RecommendBookEvent`,
  the bookshelf fragment observer, `BookShelfPresenter.loadRecommendBooks`, and
  the matching presenter contract method.
- Removed unused Zhuishushenqi API wrappers and package beans for the retired
  recommendation and old chapter-list endpoints: `/book/recommend`,
  `/mix-atoc/{bookId}`, `RecommendBookPackage`, and `BookChapterPackage`.
- Kept active search hot words/auto-complete and active chapter-content
  download code intact. `BookApi` still owns only currently referenced
  Zhuishushenqi endpoints plus the app's own API surface.
- Expanded `DeprecatedZhuishuCleanupContractTest` so the dead event, presenter
  method, repository wrappers, Retrofit declarations, and package beans fail if
  they return. The new test failed first, then passed after cleanup.
- Validation: targeted cleanup contract passed; full
  `:app:testDebugUnitTest :app:assembleDebug` passed; APK install succeeded.
- Bridge validation: launched `MainActivity`, verified `我的书架` and `找书`,
  UIAutomator tapped `找书`, verified `SearchActivity` with `热门搜索` and
  `换一批`, returned to the bookshelf, tapped `仙人消失之后`, verified
  `ReadActivity`, and logcat had no `AndroidRuntime`, `FATAL EXCEPTION`, or
  bridge error output.
- AI App Bridge note: no new bridge-library issue was found in this pass.

## 2026-05-10 Deprecated Zhuishu Cleanup Pass 10

- Removed the unreachable offline download cache chain: `DownloadService`,
  download task events, `DownloadTaskBean`, the download task GreenDAO DAO,
  `LocalRepository`, the bookshelf cache menu action, and the old download
  strings/colors.
- Removed the retired Zhuishushenqi chapter-content endpoint and wrappers:
  `chapter2.zhuishushenqi.com`, `ChapterInfoBean`, `ChapterInfoPackage`,
  `BookApi.getChapterInfoPackage`, and `RemoteRepository.getChapterInfo`.
- Kept active bookshelf, search, and reading behavior intact. Active reading
  still uses the app-owned `getBookContent` path through `ReadPresenter`.
- Expanded `DeprecatedZhuishuCleanupContractTest` so the old download service,
  task entity/DAO, RxBus events, menu strings, color resources, and old chapter
  API tokens fail if they return. The new test failed first, then passed after
  the cleanup.
- Validation: targeted cleanup contract passed; full
  `:app:testDebugUnitTest :app:assembleDebug` passed; APK install succeeded.
- Bridge validation: launched `MainActivity`, `ai-app-bridge status
  --package-name com.ldp.reader` reported `MainActivity`, `wait-text` verified
  `我的书架`, `找书`, and `仙人消失之后`; a long press on the bookshelf item showed
  `删除` while `wait-text 缓存` returned `text_not_found`; UIAutomator then tapped
  `找书`, verified `SearchActivity` with `热门搜索` and `换一批`, returned to the
  bookshelf, tapped `仙人消失之后`, and verified `ReadActivity`. Narrow logcat
  filtering for fatal/error bridge or app crashes was empty.
- AI App Bridge note: no new bridge-library issue was found in this pass.
  Attempting `adb shell am startservice` only exposed an app-side fact: the
  removed `DownloadService` was not exported and had no live in-app starter, so
  it was cleanup evidence rather than a bridge defect.

## 2026-05-10 Deprecated Zhuishu Cleanup Pass 11

- Removed dead presenter writes to the legacy `BookChapterBean.validInZhuishu`
  marker. The field was only written while building chapter lists and had no
  active read path.
- Kept the persisted `BookChapterBean`/GreenDAO column in this pass. Existing
  installs can still have a `VALID_IN_ZHUISHU NOT NULL` column, so deleting the
  schema field requires a separate migration-oriented slice instead of a small
  logic cleanup.
- Expanded `DeprecatedZhuishuCleanupContractTest` so the old
  `setValidInZhuishu` writes fail if they return in `BookDetailPresenter`,
  `BookShelfPresenter`, or `ReadPresenter`. The new test failed first, then
  passed after the writes were removed.
- Validation: targeted cleanup contract passed; full
  `:app:testDebugUnitTest :app:assembleDebug` passed; APK install succeeded.
- Bridge validation: launched `MainActivity`, verified `我的书架`, `找书`, and
  `仙人消失之后`; UIAutomator tapped `找书`, verified `SearchActivity` text
  `热门搜索` and `换一批`, returned to the bookshelf, tapped `仙人消失之后`, and
  verified `ReadActivity`. Narrow logcat filtering for fatal/error bridge or app
  crashes was empty.
- AI App Bridge note: no new bridge-library issue was found in this pass.

## 2026-05-10 Deprecated Zhuishu Cleanup Pass 12

- Removed orphan legacy scaffolding left after the download/community cleanup:
  unused `BaseService`, old retro/scroll demo layouts, the old sex-choice
  dialog, ad placeholder layouts, notification layout, and their dedicated
  download/community/sex-choice drawable assets.
- Expanded `DeprecatedZhuishuCleanupContractTest` so these unused service,
  layout, and drawable files fail if they return. The test failed first, then
  passed after deletion.
- Validation: targeted cleanup contract passed; full
  `:app:testDebugUnitTest :app:assembleDebug` passed; APK install succeeded.
- Bridge validation: launched `MainActivity`, verified `我的书架` and `找书`,
  UIAutomator tapped `找书`, verified `SearchActivity` with `热门搜索` and
  `换一批`, returned to the bookshelf, tapped `仙人消失之后`, and verified
  `ReadActivity`. Narrow logcat filtering for fatal/error bridge or app crashes
  was empty.
- AI App Bridge note: no new bridge-library issue was found in this pass.

## 2026-05-10 Deprecated Zhuishu Cleanup Pass 13

- Removed unused download-cache status constants from `CollBookBean`:
  `STATUS_UNCACHE`, `STATUS_CACHING`, and `STATUS_CACHED`.
- Removed unused legacy tag/sex filter strings from simplified and traditional
  Chinese resources: `nb.tag.all`, `nb.tag.sex`, `nb.tag.boy`, and
  `nb.tag.girl`.
- Expanded `DeprecatedZhuishuCleanupContractTest` so those constants and string
  keys fail if they return. The test failed first, then passed after removal.
- Validation: targeted cleanup contract passed; full
  `:app:testDebugUnitTest :app:assembleDebug` passed; APK install succeeded.
- Bridge validation: launched `MainActivity`, verified `我的书架` and `找书`,
  UIAutomator tapped `找书`, verified `SearchActivity` with `热门搜索` and
  `换一批`, returned to the bookshelf, tapped `仙人消失之后`, and verified
  `ReadActivity`. Narrow logcat filtering for fatal/error bridge or app crashes
  was empty.
- AI App Bridge note: no new bridge-library issue was found in this pass.

## Current Acceptance Snapshot

- Home and login UI have been refactored and verified through resource contract
  tests, full Gradle builds, APK installs, and ai-app-bridge device checks.
- Deprecated Zhuishushenqi-era UI routes, hidden community/detail sections,
  unreachable remote APIs, unused package beans, old local cache helpers,
  offline download service/task flow, orphan events, old strings, and orphan
  layouts/drawables have been removed across 13 committed cleanup passes.
- Latest full validation after the cleanup passes:
  `:app:testDebugUnitTest :app:assembleDebug` passed, APK install succeeded,
  ai-app-bridge verified `MainActivity`, `SearchActivity`, and `ReadActivity`,
  and narrow logcat filtering for fatal/error bridge or app crashes was empty.
- Remaining old names are intentionally deferred rather than silently removed:
  active search still uses the visible `SearchActivity` hot-word/auto-complete
  path backed by `api.zhuishushenqi.com`; `BookChapterBean.validInZhuishu` and
  `taskName` remain persisted GreenDAO schema fields; Biquge-named identifiers
  are still part of active reading/content naming and require a schema/API
  compatibility slice before renaming.
- AI App Bridge known issues found during this reader validation are recorded
  in `C:\CompanyProject\ai-app-bridge\docs\KNOWN_ISSUES.md`; the final cleanup
  passes did not reveal additional bridge-library defects.

## 2026-05-16 Refactor Strategy And MMKV Slice

- Read the new `AGENTS.md` rules. `C:\AGENTS.md` governs this repository and
  forbids unrequested fallback, compatibility mapping, substitute mapping, or
  backup logic. `D:\AGENTS.md` and `D:\CompanyProject\AGENTS.md` contain the same
  rule but only govern work under `D:`.
- Added `docs/REFACTOR_STRATEGY.md` as the rolling plan for the large refactor:
  MMKV first, ObjectBox spike second, ViewBinding cleanup, Kotlin migration, then
  MVVM/LiveData and RxJava removal by feature slice.
- Replaced the `SharedPreUtils` backend with MMKV using the existing
  `IReader_pref` logical namespace. No old `SharedPreferences` migration and no
  SharedPreferences fallback were added, matching the current one-user reset
  requirement.
- Initialized MMKV in `App.onCreate()` and added the app dependency
  `com.tencent:mmkv-static:2.4.0`.
- Root cause found during build verification: this repo started on AGP 7.4.2 /
  Gradle 7.5.1, whose embedded D8/R8 4.0.52 crashes while dexing MMKV's
  `MMKVLogLevel.class`. Direct D8 reproduction with the AGP 7.4.2 builder jar
  failed on the same class; direct D8 with AGP 8.0.2 / 8.2.1 builder jars
  produced `classes.dex`. The failure was not Dokit: Dokit classpath, plugin,
  runtime dependency, and app initialization are all commented out. The failing
  Gradle transform also showed `asm-transformed-variant=NONE`, so the MMKV
  failure was not caused by the debug ASM transform.
- Upgraded the build toolchain to AGP 8.2.1 and Gradle 8.2, kept JDK 17, added
  the required `namespace`, enabled `buildConfig`, aligned Java/Kotlin targets
  to 17, declared the Gradle 8 `greendao -> kaptGenerateStubs*` dependency, and
  set `android.nonFinalResIds=false` so existing Java `switch (R.id...)` code
  still compiles without a behavioral rewrite.
- Added `SharedPreUtilsStorageContractTest` to pin the storage contract:
  `SharedPreUtils` imports MMKV, uses `MMKV.mmkvWithID(SHARED_NAME)`, decodes
  existing primitive/string defaults through MMKV, and contains no
  `SharedPreferences`, `getSharedPreferences`, `MODE_MULTI_PROCESS`, `commit`,
  or `apply` path.
- Validation:
  `:app:testDebugUnitTest --tests com.ldp.reader.utils.SharedPreUtilsStorageContractTest`
  passed, full `:app:testDebugUnitTest` passed after source-contract tests were
  made explicit UTF-8 readers, `:app:mergeExtDexRelease` passed,
  `:app:assembleDebug` passed, and `:app:installDebug` installed on device
  `PKR110 - 16`. Static search found no `SharedPreferences`,
  `getSharedPreferences`, or `MODE_MULTI_PROCESS` in `app/src/main`; only the
  new contract test contains those strings as forbidden-token assertions.
- Bridge validation: launching through `monkey` selected LeakCanary's launcher
  activity, so runtime validation explicitly launched
  `com.ldp.reader/.ui.activity.SplashActivity`. `ai-app-bridge status
  --package-name com.ldp.reader` reported bridge `0.1.8` and
  `MainActivity`; `ai-app-bridge tree --compact --visible-only` showed visible
  nodes including `书架`, `本周读0分钟`, `筛选`, `编辑`, and bookshelf entries; and
  `ai-app-bridge wait-text --target-text 书架 --require-activity MainActivity`
  passed. Narrow logcat checks for `AndroidRuntime` and `FATAL EXCEPTION` were
  empty.
- Remaining toolchain notes: `assembleDebug` still prints AGP ASM
  instrumentation warnings about unresolved Mob/AndroidX-related classes, and
  D8 still prints non-fatal MobSDK stack-map-table warnings. They did not block
  APK generation or this runtime smoke test, but they should stay visible during
  later slices.

## 2026-05-16 ObjectBox Spike, ViewBinding Slice, And Reading Stats MVVM

- Added `BookRepositoryStorageContractTest` before touching database
  implementation. It pins the current GreenDAO behavior that future ObjectBox
  code must preserve: bookshelf ordered by `lastRead` descending, chapters
  queried by `bookId` and ordered by `start`, chapter replacement as delete then
  insert, and reading records saved/queried/deleted by `bookId`.
- Added ObjectBox 5.4.1 as a spike dependency and applied the plugin. The first
  Gradle run proved the Aliyun gradle-plugin mirror can expose an incomplete
  ObjectBox processor artifact, so `io.objectbox` is now explicitly resolved
  from Maven Central before mirrors.
- Added a non-production `ObjectBoxBookRecordEntity` and
  `ObjectBoxBookRecordStore`. The spike keeps ObjectBox's required long object
  ID separate from the app's indexed string `bookId`, and `kaptDebugKotlin`
  generated `MyObjectBox`, entity cursor code, and `app/objectbox-models/default.json`.
  Production `BookRepository` still uses GreenDAO.
- Migrated residual Activity-level view lookups to ViewBinding in
  `SearchActivity`, `MainActivity`, and `FileSystemActivity`. The search include
  now has a binding ID, while the main toolbar include keeps the existing
  `toolbar` ID so `BaseActivity` still initializes the ActionBar and
  `MainActivity.setUpToolbar()` still wires the ViewPager and bottom navigation.
  Removed stale FileSystem ButterKnife comments in the same slice.
- Started the MVVM/LiveData migration with `ReadingStatsActivity`. The Activity
  now observes `ReadingStatsViewModel.stats` and renders
  `ReadingStatsUiState`; formatting remains covered by `ReadingStatsUtilsTest`
  and `ReadingStatsViewModelTest`.
- Focused validation passed:
  `BookRepositoryStorageContractTest`, `ObjectBoxBookRecordEntityTest`,
  `ReadingStatsViewModelTest`, `ReadingStatsUtilsTest`, and
  `:app:compileDebugKotlin :app:compileDebugJavaWithJavac`.
- Full validation after fixing the main toolbar include contract:
  `:app:testDebugUnitTest` passed, `:app:installDebug` installed on device
  `PKR110 - 16`, `ai-app-bridge status --package-name com.ldp.reader` reported
  `MainActivity`, bridge tree exposed `com.ldp.reader:id/toolbar` plus
  bookshelf content, tapping the bottom `我的` tab showed the mine page
  `阅读时长` entry, and tapping that entry opened `ReadingStatsActivity` with
  `累计阅读`, `今日阅读`, and `本周阅读` visible. Narrow logcat checks for
  `FATAL EXCEPTION` and `AndroidRuntime` were empty.

## 2026-05-16 BookRecord Production ObjectBox Slice

- Moved production reading-progress storage from GreenDAO to ObjectBox. The
  `BookRepository.saveBookRecord`, `getBookRecord`, and `deleteBookRecord`
  methods now delegate to `ObjectBoxBookRecordStore`.
- Added `ObjectBoxDbHelper` as the app-context-backed `BoxStore` owner and kept
  `ObjectBoxBookRecordEntity.bookId` as the indexed business key. The ObjectBox
  long `id` remains only the storage identity.
- Removed GreenDAO annotations from `BookRecordBean`. `DaoMaster` and
  `DaoSession` now generate only `BookChapterBeanDao` and `CollBookBeanDao`;
  the old `BookRecordBeanDao` file is absent.
- Added a real ObjectBox JVM test for save/read/update/delete by business
  `bookId`, and expanded source-contract tests so the old GreenDAO
  `BookRecordBeanDao` path fails if it returns.
- Validation:
  targeted `BookRepositoryStorageContractTest`,
  `ObjectBoxBookRecordEntityTest`, and `ObjectBoxBookRecordStoreTest` passed;
  full `:app:testDebugUnitTest` passed; `:app:assembleDebug :app:installDebug`
  passed. Runtime validation launched `SplashActivity`, bridge status reported
  `MainActivity`, `wait-text 书架` passed, the bookshelf item `黄昏分界` opened
  `ReadActivity`, backing out returned to `MainActivity`, and narrow logcat
  checks for `FATAL EXCEPTION` and `AndroidRuntime` were empty.

## 2026-05-16 Full GreenDAO Removal Slice

- Migrated bookshelf and chapter storage from GreenDAO to ObjectBox. Production
  `BookRepository` now uses `ObjectBoxBookStore` for `CollBookBean` and
  `BookChapterBean`, plus `ObjectBoxBookRecordStore` for reading progress.
- Kept ObjectBox storage IDs separate from app business IDs. Books are upserted
  by `_id`, chapters are queried by `bookId` and ordered by `start`, and chapter
  replacement remains delete-then-insert inside the repository transaction path.
- Removed GreenDAO from the build and source tree: plugin, dependency/version,
  generated DAOs, `DaoDbHelper`, `MyOpenHelper`, and old GreenDAO migration
  helpers are gone. `CollBookBean`, `BookChapterBean`, and `BookRecordBean` are
  plain model objects.
- Added `ObjectBoxBookStoreTest` as a real JVM ObjectBox test for save, ordered
  list query, chapter replacement, chapter deletion, and book deletion. Expanded
  existing source-contract tests so GreenDAO build wiring and generated source
  directories fail if they return.
- Static validation found no `greendao`, `DaoSession`, `DaoMaster`,
  `DaoDbHelper`, `BookChapterBeanDao`, or `CollBookBeanDao` references under
  `app/src/main` or Gradle build files.
- Validation: targeted ObjectBox/storage contract tests passed; full
  `:app:testDebugUnitTest :app:assembleDebug :app:installDebug` passed. Runtime
  validation launched `SplashActivity`, bridge status reported `MainActivity`,
  `wait-text 书架` passed, and logcat had no app fatal/ObjectBox/GreenDAO storage
  errors. Because old GreenDAO data is intentionally not migrated, the first
  bookshelf state was empty; bridge then imported
  `codex-local-import-probe.txt`, verified the new book on the shelf, tapped it,
  and status reported `ReadActivity`.

## 2026-05-16 Full ViewBinding Lookup Removal Slice

- Removed the remaining `findViewById` usage from `app/src/main` without adding
  generic lookup fallbacks. `BaseActivity` now gets its toolbar from concrete
  Activity bindings through `toolbarView()`.
- Migrated the remaining lookup sites to generated bindings: custom selector and
  scroll-refresh widgets, tab view, `ReadSettingDialog`, bookshelf empty/delete
  dialog views, and adapter item holders.
- Added `ViewBindingMigrationContractTest` so main Java/Kotlin sources fail if
  `findViewById`, `ButterKnife`, `@BindView`, or `@OnClick` return.
- Focused validation: static source scan found no manual lookup or ButterKnife
  tokens, `:app:compileDebugKotlin :app:compileDebugJavaWithJavac` passed, and
  `ViewBindingMigrationContractTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  verified `MainActivity` and shelf text through ai-app-bridge, opened the
  visible local book with UIAutomator coordinates, verified `ReadActivity`,
  opened the read menu, tapped `设置`, and verified read-setting dialog text such
  as `默认`, `仿真`, `覆盖`, `滚动`, and `无`. Narrow logcat checks for app fatal
  output were empty after the fix.
- Runtime fix during validation: binding `view_refresh_tip` against the
  container root caused a `ClassCastException` in `ScrollRefreshRecyclerView`
  inflation. The binding target is now the included tip child view inside
  `scrollRefreshFlContent`.
- Validation note: the app was manually logged in during this run, so the
  temporary LoginActivity navigation was not treated as a regression signal.

## 2026-05-16 Kotlin Migration Batch 1

- Migrated the first low-risk data/event batch from Java to Kotlin:
  `BookSyncEvent`, `BaseBean`, `BookIdBean`, `ChapterBean`, `ContentBean`,
  `HotWordPackage`, `KeyWordPackage`, and `model.local.Void`.
- Kept the Java interop contract direct: mutable Kotlin properties still expose
  JavaBean getters/setters for existing Retrofit/Gson/Presenter call sites, and
  no field fallback or compatibility mapping was added.
- Source shape after this batch: 125 Java files and 29 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin :app:compileDebugJavaWithJavac`
  passed, and `KotlinMigrationContractTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge reported `MainActivity`, `wait-text 书架` passed, and narrow
  logcat checks for app fatal output were empty.

## 2026-05-16 Kotlin Migration Batch 2

- Migrated the login and shelf-sync response/request model batch from Java to
  Kotlin: `DirectLoginResultBean`, `LoginResultBean`, `SmsLoginBean`,
  `SyncBookShelfBean`, and `DirectSycBookShelfBean`.
- Preserved the existing Java call surface for active Presenter and Retrofit
  paths, including `isStatus()` on boolean response models and `getIsValid()` /
  `setIsValid()` on the direct-login result. No field fallback, compatibility
  mapping, or substitute values were added.
- `LoginActivity` now uses a non-null assertion for the direct-login `res`
  payload where the old Java code also expected the payload to exist. A missing
  `res` still fails loudly instead of being replaced with a default object.
- Source shape after this batch: 120 Java files and 34 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed, and
  `KotlinMigrationContractTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge reported `MainActivity`, `wait-text 书架` passed, and narrow
  logcat checks for app fatal output were empty.

## 2026-05-16 Kotlin Migration Batch 3

- Migrated the ObjectBox-facing storage model batch from Java to Kotlin:
  `BookRecordBean` and `BookChapterBean`.
- Preserved the constructor and JavaBean method surface used by repository,
  ObjectBox entity mappers, page loaders, and tests. `BookChapterBean` keeps the
  existing `isUnreadble()`, `getUnreadble()`, `isValidInZhuishu()`, and
  `getValidInZhuishu()` methods instead of adding alternate boolean mappings.
- Source shape after this batch: 118 Java files and 36 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `ObjectBoxBookStoreTest`, `ObjectBoxBookRecordEntityTest`, and
  `ObjectBoxBookRecordStoreTest` passed.
- Full validation: after updating the storage contract test to read the current
  Kotlin model sources, `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge reported `MainActivity`, `wait-text 书架` passed, and narrow
  logcat checks for app fatal output were empty.

## 2026-05-16 Kotlin Migration Batch 4

- Migrated remote book search/detail models from Java to Kotlin:
  `BookSearchResult` and `BookDetailBeanInOwn`.
- Preserved search-result identity behavior: `getId()` still derives from
  `title` and `author`, `hashCode()` still uses `Objects.hash(title, author)`,
  and `equals()` still fails loudly if the current result has a missing title or
  author instead of treating nulls as equal.
- Preserved the existing `BookDetailBeanInOwn.collBookBean` lazy creation path
  used by adding a remote book to the shelf.
- Source shape after this batch: 116 Java files and 38 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest` and
  `RemoteBookModelKotlinInteropTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge reported `MainActivity`, `wait-text 书架` passed, and narrow
  logcat checks for app fatal output were empty.

## 2026-05-16 Kotlin Migration Batch 5

- Migrated `CollBookBean` from Java to Kotlin.
- Preserved its mixed call surface: Kotlin screens can still use property access
  for title/author/cover fields, while existing Java and Kotlin call sites keep
  `get_id()`, `set_id()`, `isLocal()`, `setLocal()`, `isUpdate()`,
  `setUpdate()`, `getIsLocal()`, and `getIsUpdate()`.
- Kept Parcelable write/read order unchanged and kept `setBookChapters()` as
  the owner that stamps each chapter with the current book ID.
- Source shape after this batch: 115 Java files and 39 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `ObjectBoxBookStoreTest`, `CollBookAdapterTest`,
  `CollBookHolderLocalBookTest`, `BookShelfPresenterFilterTest`, and
  `BookShelfPresenterSyncTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge reported `MainActivity`, `wait-text 书架` passed, bridge tapped
  the visible shelf item `黄昏分界`, `ReadActivity` opened, and narrow logcat
  checks for app fatal output were empty.

## 2026-05-16 Kotlin Migration Batch 6

- Migrated a foundation batch from Java to Kotlin: `PageMode`, `PageStyle`,
  `TxtPage`, `TabItem`, `TabView`, `ToastUtils`, `IOUtils`, `Charset`,
  `LoaderCreator`, `BaseContract`, `IViewHolder`, `BaseViewHolder`,
  `ViewHolderImpl`, `KeyWordAdapter`, and `SearchBookAdapter`.
- Preserved Java interop where existing Java code depends on it:
  `ToastUtils.show()`, `IOUtils.close()`, and `LoaderCreator.create()` remain
  static-callable; `Charset.BLANK` remains a static constant; `TxtPage` and
  `TabItem` keep direct field access through `@JvmField`; and adapter holder
  access remains direct through `BaseViewHolder.holder`.
- Source shape after this batch: 100 Java files and 54 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `CollBookAdapterTest`, `CollBookHolderLocalBookTest`,
  `ViewBindingMigrationContractTest`, and
  `RemoteBookModelKotlinInteropTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified `MainActivity` and `书架`, opened `SearchActivity`
  through the toolbar search icon and verified `热门搜索`, opened shelf item
  `黄昏分界` into `ReadActivity`, opened the read-setting dialog, verified
  `默认`, and narrow logcat checks for app fatal output were empty.

## 2026-05-16 Kotlin Migration Batch 7

- Migrated the active Retrofit API interfaces and Presenter contract interfaces
  from Java to Kotlin: `BookApi`, `BookApiOwn`, `BookShelfContract`,
  `BookDetailContract`, `LoginContract`, `ReadContract`, and `SearchContract`.
- Kept method names, Retrofit annotations, RxJava return types, and MVP generic
  ownership unchanged. Nullable parameters are used only at the Java/Kotlin
  boundary where existing callers can already pass null through the old Java
  interfaces.
- Added `@JvmSuppressWildcards` on the migrated interfaces so existing Java
  Presenter implementations keep exact `List<T>` override signatures.
- Source shape after this batch: 93 Java files and 61 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `DeprecatedZhuishuCleanupContractTest`, `BookShelfPresenterFilterTest`, and
  `BookShelfPresenterSyncTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified `MainActivity` and `书架`, opened `SearchActivity`
  through the toolbar search icon, verified `热门搜索`, and narrow logcat checks
  for app fatal output were empty.

## 2026-05-16 Kotlin Migration Batch 13

- Migrated static utility helpers from Java to Kotlin: `CacheUtils`,
  `BrightnessUtils`, `SimilarityCharacterUtils`, and `MediaStoreHelper`.
- Preserved static Java call surfaces with `object` + `@JvmStatic`. Existing
  guard behavior in cache and brightness helpers was kept as-is; no new
  compatibility mapping was added.
- Source shape after this batch: 56 Java files and 98 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `HomeUiResourceContractTest`, and `FileSystemUiResourceContractTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified `MainActivity` and `书架`, opened shelf item
  `黄昏分界` into `ReadActivity`, opened the read-setting dialog, verified
  `默认`, and narrow logcat checks for app fatal output were empty.

## 2026-05-16 Kotlin Migration Batch 14

- Migrated the local file import fragments from Java to Kotlin:
  `BaseFileFragment`, `FileCategoryFragment`, and `LocalBookFragment`.
- Preserved the existing file-import contract directly: the tab listener,
  checked-file methods, path-based loaded-book checks, directory sorting, and
  null-exposure behavior remain aligned with the old Java implementation. No
  compatibility fallback or alternate file lookup was added.
- Source shape after this batch: 53 Java files and 101 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `FileSystemUiResourceContractTest`, and `HomeUiResourceContractTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified `MainActivity` and `书架`, navigated through
  `我的 -> 设置 -> 本机书籍导入` into `FileSystemActivity`, verified
  `智能导入` and `手机目录`, switched to the phone-directory tab, observed the
  storage path/list and bottom import actions, and narrow logcat checks for
  app fatal output were empty.

## 2026-05-16 Kotlin Migration Batch 15

- Migrated isolated custom views and rendering helpers from Java to Kotlin:
  `BookTextView`, `ReboundScrollView`, `CustomTextView`,
  `CustomExpandableListView`, `CircleTransform`, `DividerItemDecoration`,
  `DividerGridItemDecoration`, and `BezierEvaluator`.
- Preserved the existing constructor surfaces, drawing math, nullable drawable
  exposure, and custom text span behavior directly. This slice does not add
  defensive fallbacks for missing view attrs, RecyclerView adapters, or drawing
  state.
- Source shape after this batch: 45 Java files and 109 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `ViewBindingMigrationContractTest`, `HomeUiResourceContractTest`,
  `FileSystemUiResourceContractTest`, and `CollBookAdapterTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified `MainActivity`, `书架`, and `黄昏分界`, opened
  `ReadActivity`, then navigated through `我的 -> 设置 -> 本机书籍导入` into
  `FileSystemActivity` and verified `智能导入` plus `加入书架`. Narrow logcat
  checks for app fatal output were empty.

## 2026-05-16 Kotlin Migration Batch 16

- Migrated text/screen utility classes from Java to Kotlin: `LogUtils`,
  `SystemBarUtils`, `ScreenUtils`, `ReadingStatsUtils`, and `StringUtils`.
- Preserved static Java call surfaces with `object` + `@JvmStatic`, including
  existing relative-time formatting, reading-stat normalization, system-bar
  flag handling, screen metric helpers, and text conversion behavior. This
  slice does not add fallback defaults for missing resources, null paint/text,
  or missing conversion context.
- Source shape after this batch: 40 Java files and 114 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `ReadingStatsUtilsTest`, `StringUtilsRelativeTimeTest`,
  `PageLoaderLayoutTest`, and `ReadingStatsViewModelTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified `MainActivity`, `书架`, `本周读`, and `黄昏分界`,
  opened `ReadActivity`, returned to the shelf, opened `SearchActivity`, and
  verified `热门搜索`. Narrow logcat checks for `FATAL EXCEPTION` and
  `E AndroidRuntime` were empty; the observed `AndroidRuntime: Shutting down VM`
  line belonged to the short-lived UiAutomator process used by bridge.

## 2026-05-16 Kotlin Migration Batch 8

- Migrated a thin utility and adapter batch from Java to Kotlin: `Constant`,
  `BookshelfLocalProgressStore`, `PermissionsChecker`, `KeyWordHolder`,
  `PageStyleHolder`, `CategoryAdapter`, `PageStyleAdapter`, `RxUtils`,
  `MD5Utils`, `FileStack`, and `NetworkUtils`.
- Preserved Java call surfaces used by remaining Java files: constants remain
  static-callable, utility methods remain `@JvmStatic`, `RxUtils.TwoTuple`
  keeps direct `first`/`second` fields, and `FileStack.FileSnapshot` keeps
  direct field access.
- Source shape after this batch: 82 Java files and 72 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `DeprecatedZhuishuCleanupContractTest`, `FileSystemUiResourceContractTest`,
  `HomeUiResourceContractTest`, `CollBookAdapterTest`, and
  `CollBookHolderLocalBookTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified `MainActivity` and `书架`, opened `SearchActivity`
  and verified `热门搜索`, opened shelf item `黄昏分界` into `ReadActivity`,
  opened the read-setting dialog, verified `默认`, and narrow logcat checks for
  app fatal output were empty.

## 2026-05-16 Kotlin Migration Batch 9

- Migrated the remaining active list adapter/holder batch from Java to Kotlin:
  `CategoryHolder`, `SearchBookHolder`, `FileHolder`, `CollBookHolder`,
  `CollBookAdapter`, and `FileSystemAdapter`.
- Preserved Java-facing helper methods used by tests and remaining Java
  fragments, including `CollBookHolder.coverTitle`,
  `CollBookHolder.progressLabel`, `CollBookAdapter.selectionKey`, and
  `FileSystemAdapter` checked-file methods.
- Source shape after this batch: 76 Java files and 78 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `FileSystemUiResourceContractTest`, `HomeUiResourceContractTest`,
  `CollBookAdapterTest`, and `CollBookHolderLocalBookTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified `MainActivity` and `书架`, opened `SearchActivity`
  through the toolbar search icon and verified `热门搜索`, opened shelf item
  `黄昏分界` into `ReadActivity`, opened the read-setting dialog, verified
  `默认`, and narrow logcat checks for app fatal output were empty.

## 2026-05-16 Kotlin Migration Batch 10

- Migrated the base adapter/load-more layer from Java to Kotlin:
  `BaseListAdapter`, `GroupAdapter`, `BaseAdapter`, `EasyAdapter`,
  `WholeAdapter`, `LoadMoreDelegate`, and `LoadMoreView`.
- Preserved existing Kotlin and Java call surfaces: `items` still exposes the
  generated `getItems()` getter for Java callers, item listener setters keep
  SAM-callable interfaces, `WholeAdapter.Options` keeps direct field access,
  and load-more status constants remain static-callable.
- Source shape after this batch: 69 Java files and 85 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `FileSystemUiResourceContractTest`, `HomeUiResourceContractTest`,
  `CollBookAdapterTest`, and `CollBookHolderLocalBookTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified `MainActivity` and `书架`, opened `SearchActivity`
  through the toolbar search icon and verified `热门搜索`, opened shelf item
  `黄昏分界` into `ReadActivity`, opened the read-setting dialog, verified
  `默认`, and narrow logcat checks for app fatal output were empty.

## 2026-05-16 Kotlin Migration Batch 11

- Migrated the ObjectBox layer from Java to Kotlin: `ObjectBoxBookChapterEntity`,
  `ObjectBoxBookRecordEntity`, `ObjectBoxCollBookEntity`,
  `ObjectBoxBookRecordStore`, `ObjectBoxBookStore`, and `ObjectBoxDbHelper`.
- Kept the ObjectBox schema field names, business-key queries, mapper methods,
  and app-context-backed helper unchanged. The Kotlin entity pass does not add
  null defaults or compatibility mappings.
- Source shape after this batch: 63 Java files and 91 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `ObjectBoxBookRecordEntityTest`, `ObjectBoxBookStoreTest`, and
  `ObjectBoxBookRecordStoreTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified `MainActivity` and `书架`, opened shelf item
  `黄昏分界` into `ReadActivity`, verified `简介`, and narrow logcat checks for
  app fatal output were empty.

## 2026-05-16 Kotlin Migration Batch 12

- Migrated the app entrypoint, MMKV storage wrapper, and RxJava event bus from
  Java to Kotlin: `App`, `SharedPreUtils`, and `RxBus`.
- Kept MMKV as the only storage backend, preserved static Java call surfaces
  (`App.getContext()`, `SharedPreUtils.getInstance()`, `RxBus.getInstance()`),
  and kept `SharedPreUtils.getString()` non-null to match its old Java method
  contract. The non-null assertion exposes an unexpected MMKV null directly.
- Source shape after this batch: 60 Java files and 94 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `SharedPreUtilsStorageContractTest`, `DeprecatedZhuishuCleanupContractTest`,
  `LoginUiResourceContractTest`, and `HomeUiResourceContractTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified `MainActivity` and `书架`, opened `SearchActivity`
  through the toolbar search icon, verified `热门搜索`, and narrow logcat checks
  for app fatal output were empty.

## 2026-05-16 Kotlin Migration Batch 17

- Migrated tab/selector/page-model widgets from Java to Kotlin:
  `EasyRatingBar`, `SelectorView`, `TabTextView`, `TabViewGroup`, `ScrollTab`,
  and `TxtChapter`.
- Preserved the existing runtime contracts directly: XML custom-view
  constructors still exist, tab click/listener behavior is unchanged, selector
  popup state is still owned by the child item, and `TxtChapter` keeps both
  field access for remaining Java page loaders and getter/setter access for
  existing presenter/page code.
- Source shape after this batch: 34 Java files and 120 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `ViewBindingMigrationContractTest`, `HomeUiResourceContractTest`,
  `PageLoaderLayoutTest`, and `FileSystemUiResourceContractTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified `MainActivity` and `书架`, navigated through
  `我的 -> 设置 -> 本机书籍导入`, verified `FileSystemActivity`, `智能导入`,
  `手机目录`, switched to the phone-directory tab, verified `加入书架`, and
  narrow logcat checks for `FATAL EXCEPTION` and `E AndroidRuntime` were empty.

## 2026-05-16 Kotlin Migration Batch 18

- Migrated refresh/status widgets from Java to Kotlin:
  `widget.RefreshLayout`, `widget.ScrollRefreshLayout`,
  `widget.refresh.RefreshLayout`, `widget.refresh.RefreshRecyclerView`,
  `widget.refresh.ScrollRefreshLayout`, and
  `widget.refresh.ScrollRefreshRecyclerView`.
- Preserved the existing XML/custom-view contracts: status transitions,
  save/restore status, child-count enforcement, adapter observer behavior,
  misspelled `getReyclerView()`, and the existing tip animation cancellation
  behavior remain direct translations.
- Source shape after this batch: 28 Java files and 126 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed after restoring the Kotlin
  `emptyView` property call surface. `KotlinMigrationContractTest`,
  `HomeUiResourceContractTest`, `FileSystemUiResourceContractTest`,
  `ViewBindingMigrationContractTest`, and `PageLoaderLayoutTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified `MainActivity` and `书架`, exercised the bookshelf
  `ScrollRefreshRecyclerView`, opened `SearchActivity` and verified `热门搜索`,
  navigated to `FileSystemActivity`, verified `智能导入` and `加入书架`, and
  narrow logcat checks for `FATAL EXCEPTION` and `E AndroidRuntime` were empty.

## 2026-05-16 Kotlin Migration Batch 19

- Migrated remote infrastructure from Java to Kotlin: `RemoteHelper`,
  `RemoteRepository`, and `LenientGsonConverterFactory`.
- Preserved Java-facing singleton entry points and network contracts:
  `RemoteHelper.getInstance()`, `RemoteRepository.getInstance()`, Retrofit API
  creation, RxJava `Single` return types, hot-word/key-word mapping, and the
  lenient Gson request/response converter remain direct translations.
- Source shape after this batch: 25 Java files and 129 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed after switching the converter to
  OkHttp's Kotlin `toMediaTypeOrNull()` / `toRequestBody()` APIs.
  `KotlinMigrationContractTest`, `DeprecatedZhuishuCleanupContractTest`,
  `BookShelfPresenterFilterTest`, `BookShelfPresenterSyncTest`, and
  `RemoteBookModelKotlinInteropTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified `MainActivity` and `书架`, opened `SearchActivity`,
  verified `热门搜索`, captured OkHttp records for `/getBookInfoBatch` and
  `/book/hot-word`, and narrow logcat checks for `FATAL EXCEPTION` and
  `E AndroidRuntime` were empty.

## 2026-05-16 Kotlin Migration Batch 20

- Migrated local read-setting and local media loading code from Java to Kotlin:
  `ReadSettingManager` and `LocalFileLoader`.
- Preserved the existing Java/Kotlin call contracts directly:
  `ReadSettingManager.getInstance()`, JavaBean setters/getters,
  `setAutoBrightness(boolean)`, static constant access, MMKV-backed setting
  keys, and `LocalFileLoader.parseData(...)` cursor/category behavior remain
  direct translations.
- Source shape after this batch: 23 Java files and 131 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed after restoring the Kotlin constant
  import and Java-facing `setAutoBrightness(boolean)` method.
  `KotlinMigrationContractTest`, `StringUtilsRelativeTimeTest`,
  `PageLoaderLayoutTest`, `FileSystemUiResourceContractTest`, and
  `ViewBindingMigrationContractTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified `MainActivity` and `书架`, opened a shelf book into
  `ReadActivity`, opened the read-setting dialog and verified `默认`, navigated
  through `我的 -> 设置 -> 本机书籍导入`, verified `FileSystemActivity`,
  `智能导入`, `手机目录`, and `加入书架`, and app-pid logcat checks for
  `FATAL EXCEPTION` and `E AndroidRuntime` were empty.

## 2026-05-16 Kotlin Migration Batch 21

- Migrated the Presenter base and read-setting dialog from Java to Kotlin:
  `RxPresenter` and `ReadSettingDialog`.
- Preserved Java Presenter subclass interop by keeping protected `mView`,
  protected `mDisposable`, `attachView`, `detachView`, and `addDisposable`
  callable from remaining Java subclasses. The read-setting dialog keeps the
  existing ViewBinding setup, window placement, brightness/font/page-mode
  handlers, and `isBrightFollowSystem` getter shape used by `ReadActivity`.
- Source shape after this batch: 21 Java files and 133 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed after keeping the dialog constructor
  nullable for the existing mutable `ReadActivity.mPageLoader` call site.
  `KotlinMigrationContractTest`, `PageLoaderLayoutTest`,
  `ViewBindingMigrationContractTest`, and `HomeUiResourceContractTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified `MainActivity` and `书架`, opened a shelf book into
  `ReadActivity`, opened the read-setting dialog and verified `默认`, opened
  `SearchActivity` and verified `热门搜索`, and app-pid logcat checks for
  `FATAL EXCEPTION` and `E AndroidRuntime` were empty.

## 2026-05-16 Kotlin Migration Batch 22

- Migrated `FileUtils` from Java to Kotlin.
- Preserved the static Java/Kotlin call surface with `const val` suffixes and
  `@JvmStatic` helper methods for cache paths, file creation/deletion, size
  formatting, local TXT discovery, and charset detection. Existing null/IO
  failure behavior remains direct rather than adding replacement defaults.
- Source shape after this batch: 20 Java files and 134 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `FileSystemUiResourceContractTest`, `PageLoaderLayoutTest`, and
  `HomeUiResourceContractTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified the local `codex-local-import-probe` shelf item,
  opened it into `ReadActivity`, navigated through
  `我的 -> 设置 -> 本机书籍导入`, verified `FileSystemActivity` and `加入书架`,
  and app-pid logcat checks for `FATAL EXCEPTION` and `E AndroidRuntime` were
  empty.

## 2026-05-16 Kotlin Migration Batch 23

- Migrated the legacy `BookManager` cache helper from Java to Kotlin.
- Preserved the existing singleton/static entry points used by Java and Kotlin
  callers: `BookManager.getInstance()`, `getBookFile`, `getBookSize`, and
  `isChapterCached`. Paragraph position/cache behavior and weak-reference
  chapter content reloads remain direct translations.
- Source shape after this batch: 19 Java files and 135 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `HomeUiResourceContractTest`, `PageLoaderLayoutTest`, and
  `FileSystemUiResourceContractTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified the local `codex-local-import-probe` shelf item,
  opened it into `ReadActivity`, opened the chapter drawer, verified visible
  local chapters `第1章(1)`, `第1章(2)`, and `第1章(3)`, and app-pid logcat
  checks for `FATAL EXCEPTION` and `E AndroidRuntime` were empty.

## 2026-05-16 Kotlin Migration Batch 24

- Migrated `SearchPresenter` and `LoginPresenter` from Java to Kotlin.
- Preserved the existing Presenter contracts: search hot-word/key-word/book
  flows still use RxJava and direct `mView` calls, while login keeps its
  existing null-view guards around asynchronous callbacks, MobPush registration
  lookup, SecVerify pre-verify, SMS login, and direct-login callbacks.
- Source shape after this batch: 17 Java files and 137 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `LoginUiResourceContractTest`, `HomeUiResourceContractTest`,
  `BookShelfPresenterFilterTest`, and `BookShelfPresenterSyncTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`,
  ai-app-bridge verified `MainActivity` and `书架`, opened `SearchActivity`
  through the toolbar search action, verified `热门搜索`, and app-pid logcat
  checks for `FATAL EXCEPTION` and `E AndroidRuntime` were empty. Direct
  `LoginActivity` launch is not exported, so the login Presenter was verified
  through compile and contract tests without changing the current login state.

## 2026-05-16 Kotlin Migration Batch 25

- Migrated `ReadPresenter` and `BookDetailPresenter` from Java to Kotlin.
- Preserved the existing Presenter contracts and RxJava repository flow:
  category loading, chapter content loading/refreshing, book-detail refresh,
  add-to-bookshelf, and server bookshelf sync remain direct translations. The
  Kotlin code keeps the old fail-loud null behavior where Java called through
  nullable contract values directly.
- Source shape after this batch: 15 Java files and 139 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `DeprecatedZhuishuCleanupContractTest`, `HomeUiResourceContractTest`, and
  `PageLoaderLayoutTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`
  with the current SMS-login state intact, ai-app-bridge verified
  `MainActivity` and `书架`, opened the remote shelf book `黄昏分界` into
  `ReadActivity`, opened the chapter drawer and verified real remote chapter
  rows, then opened `BookDetailActivity` through the read-page `简介` action
  and verified `书籍详情`, `黄昏分界`, and `作品简介`. App-pid logcat checks for
  `FATAL EXCEPTION` and `AndroidRuntime` were empty.

## 2026-05-16 Kotlin Migration Batch 26

- Migrated `BookShelfPresenter` from Java to Kotlin.
- Preserved the Java-visible static helper surface used by tests and UI:
  `FilterKey`, filter labels, filter matching, empty-state text,
  `onlineBookIdsFrom`, `onlineBookLongIdsFrom`,
  `mergeServerAndLocalOnlineIds`, and `normalizeServerBookIds`. The Presenter
  still uses RxJava repository flows for refresh, login-triggered shelf sync,
  manual shelf sync, update checks, batch book-detail fetches, and chapter
  folder refreshes.
- Source shape after this batch: 14 Java files and 140 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed after marking server shelf list
  elements nullable to preserve the existing partial-null contract.
  `KotlinMigrationContractTest`, `DeprecatedZhuishuCleanupContractTest`,
  `HomeUiResourceContractTest`, `BookShelfPresenterFilterTest`, and
  `BookShelfPresenterSyncTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation launched `SplashActivity`
  with the SMS-login state intact, ai-app-bridge verified `MainActivity`,
  `书架`, and `黄昏分界`, opened the bookshelf filter, selected `本地书`,
  verified the local shelf item, reset to `全部书籍`, triggered the `同步书架`
  menu action, and bridge network capture showed 200 responses for
  `getBookShelfByMobile`, `getBookInfoBatch`, and `synBookShelfByMobile`.
  The final installed APK was launched again and opened `黄昏分界` into
  `ReadActivity`; app-pid logcat checks for `FATAL EXCEPTION` and
  `AndroidRuntime` were empty.

## 2026-05-16 Kotlin Migration Batch 27

- Migrated the ObjectBox-backed `BookRepository` from Java to Kotlin.
- Preserved the existing repository surface used by Java and Kotlin callers:
  `BookRepository.getInstance()`, the Java-visible `getCollBooks()` property
  getter, RxJava chapter/delete methods, chapter replacement transactions,
  chapter file writes, and book-record delegation all keep the same storage
  responsibilities. Existing explicit null branches remain, and missing
  upstream data is not silently substituted.
- Source shape after this batch: 13 Java files and 141 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed after making old platform-type
  dereferences explicit at the existing call sites. `KotlinMigrationContractTest`,
  `BookRepositoryStorageContractTest`, `ObjectBoxBookStoreTest`,
  `ObjectBoxBookRecordStoreTest`, `HomeUiResourceContractTest`,
  `DeprecatedZhuishuCleanupContractTest`, and `BookShelfPresenterFilterTest`
  passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation kept the SMS-login state intact,
  launched `SplashActivity`, ai-app-bridge verified `MainActivity` and `书架`,
  opened `黄昏分界` into `ReadActivity`, opened the chapter drawer, and verified
  real remote chapter rows. Bridge network capture showed 200 responses for
  `getBookInfoBatch` and `getBookFolder`; app-pid logcat checks for
  `FATAL EXCEPTION` and `AndroidRuntime` were empty.

## 2026-05-16 Kotlin Migration Batch 28

- Migrated the reading-page animation stack from Java to Kotlin:
  `AnimationProvider`, `PageAnimation`, `HorizonPageAnim`, `CoverPageAnim`,
  `SlidePageAnim`, `NonePageAnim`, `ScrollPageAnim`, and `SimulationPageAnim`.
- Kept the existing `PageView` call surface and animation behavior: the page
  mode classes, `PageAnimation.Direction`, `OnPageChangeListener`,
  `HorizonPageAnim.changePage()`, `ScrollPageAnim.resetBitmap()`, bitmap
  buffers, scroller usage, and page-cancel callbacks remain direct translations.
- Source shape after this batch: 5 Java files and 149 Kotlin files under
  `app/src/main`.
- Focused validation: `:app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` passed. `KotlinMigrationContractTest`,
  `HomeUiResourceContractTest`, `PageLoaderLayoutTest`, and
  `DeprecatedZhuishuCleanupContractTest` passed.
- Full validation: `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` passed. Runtime validation kept the SMS-login state intact,
  launched `SplashActivity`, ai-app-bridge verified `MainActivity` and `书架`,
  opened `黄昏分界` into `ReadActivity`, verified the visible `PageView`, opened
  the chapter drawer, performed a horizontal read-page swipe, and confirmed
  `ReadActivity` remained current. Bridge network capture showed 200 responses
  for `getBookInfoBatch`, `getBookFolder`, and multiple `getBookContent`
  requests; app-pid logcat checks for `FATAL EXCEPTION` and `AndroidRuntime`
  were empty.

## 2026-05-16 Kotlin Migration Batch 29

- Migrated `PageView` from Java to Kotlin.
- Preserved the existing reading-page host responsibilities: page-mode
  selection, animation creation, touch dispatch, menu-center detection,
  `PageLoader` creation, bitmap handoff, auto page turns, and direct page
  cancel callbacks remain the same. Existing direct nullable call sites are
  explicit and no new fallback path was added.
- Source shape after this batch: 4 Java files and 150 Kotlin files under
  `app/src/main`.
- Validation:
  `:app:compileDebugKotlin :app:compileDebugJavaWithJavac` passed after making
  the old `ReadActivity` platform-type call explicit with `mCollBook!!`.
  `KotlinMigrationContractTest`, `HomeUiResourceContractTest`,
  `PageLoaderLayoutTest`, and `DeprecatedZhuishuCleanupContractTest` passed.
  The full `:app:testDebugUnitTest :app:assembleDebug :app:installDebug`
  sequence also passed.
- ai-app-bridge runtime validation used the existing logged-in app state:
  launched `SplashActivity`, verified `MainActivity`/`书架`, opened `黄昏分界`
  into `ReadActivity`, verified the full-screen `PageView`, performed a
  horizontal page swipe, opened the reading menu and chapter drawer, and
  observed real chapter rows in `read_iv_category`. Bridge network capture
  showed 200 responses for `getBookInfoBatch` and `getBookFolder`; app-pid
  logcat checks for `FATAL EXCEPTION` and `AndroidRuntime` were empty.

## 2026-05-16 Kotlin Migration Batch 30

- Removed unused Java-only `EncryptUtils`. Repository-wide search showed no
  callers, so keeping or translating the full crypto helper would preserve dead
  code without moving the app behavior forward.
- Added a migration contract assertion that the old Java utility file is gone.
- Source shape after this batch: 3 Java files and 150 Kotlin files under
  `app/src/main`; the remaining Java files are the reading page-loader stack.
- Validation:
  `:app:compileDebugKotlin :app:compileDebugJavaWithJavac` passed.
  `KotlinMigrationContractTest`, `HomeUiResourceContractTest`,
  `PageLoaderLayoutTest`, and `DeprecatedZhuishuCleanupContractTest` passed.
  The full `:app:testDebugUnitTest :app:assembleDebug :app:installDebug`
  sequence also passed.
- ai-app-bridge runtime validation used the existing logged-in app state:
  launched `SplashActivity`, verified `MainActivity`/`书架`, opened `黄昏分界`
  into `ReadActivity`, verified the full-screen `PageView`, observed 200
  responses for `getBookInfoBatch` and `getBookFolder`, and app-pid logcat
  checks for `FATAL EXCEPTION` and `AndroidRuntime` were empty.

## 2026-05-16 Kotlin Migration Batch 32

- Migrated local TXT reading `LocalPageLoader` from Java to Kotlin while keeping
  the existing Java `PageLoader` base for this slice.
- Preserved local-file behavior directly: cached chapter conversion, charset
  detection, chapter regex selection, virtual chapter splitting, byte-range
  chapter loading, local progress persistence, and RxJava background parsing
  remain in place. Missing local file path still fails directly at the old
  contract boundary.
- Updated source-contract tests from `.java` to `.kt` for the local loader and
  added a Kotlin-only assertion for `LocalPageLoader`.
- Source shape after this batch: 1 Java file and 152 Kotlin files under
  `app/src/main`; the only remaining Java file is `PageLoader`.
- Validation:
  `:app:compileDebugKotlin :app:compileDebugJavaWithJavac` passed.
  `KotlinMigrationContractTest`, `HomeUiResourceContractTest`,
  `PageLoaderLayoutTest`, `DeprecatedZhuishuCleanupContractTest`, and
  `CollBookHolderLocalBookTest` passed. The full
  `:app:testDebugUnitTest :app:assembleDebug :app:installDebug` sequence also
  passed.
- ai-app-bridge runtime validation used the existing logged-in app state:
  launched `SplashActivity`, verified `MainActivity`/`书架`, opened the local
  TXT `codex-local-import-probe` into `ReadActivity`, verified the full-screen
  `PageView`, and checked logcat evidence for `LocalPageLoader +refreshChapterList`,
  `PageLoader loadPages/drawContent`, and rendered local text. App-pid logcat
  checks for `FATAL EXCEPTION` and `AndroidRuntime` were empty.

## 2026-05-16 Kotlin Migration Batch 31

- Migrated network reading `NetPageLoader` from Java to Kotlin while keeping it
  on the existing Java `PageLoader` base for this slice.
- Preserved network chapter behavior directly: chapter conversion, cached file
  lookup, previous/current/next preload windows, immediate next-readable request
  guard, `saveRecord()` progress persistence, and readable-end persistence all
  remain in place. No fallback or compatibility mapping was added.
- Updated source-contract tests from `.java` to `.kt` for the network loader and
  added a Kotlin-only assertion for `NetPageLoader`.
- Source shape after this batch: 2 Java files and 151 Kotlin files under
  `app/src/main`; the remaining Java files are `PageLoader` and
  `LocalPageLoader`.
- Validation:
  `:app:compileDebugKotlin :app:compileDebugJavaWithJavac` passed.
  `KotlinMigrationContractTest`, `HomeUiResourceContractTest`,
  `PageLoaderLayoutTest`, and `DeprecatedZhuishuCleanupContractTest` passed.
  The full `:app:testDebugUnitTest :app:assembleDebug :app:installDebug`
  sequence also passed.
- ai-app-bridge runtime validation used the existing logged-in app state:
  launched `SplashActivity`, verified `MainActivity`/`书架`, opened `黄昏分界`
  into `ReadActivity`, verified the full-screen `PageView`, observed 200
  responses for `getBookInfoBatch` and `getBookFolder`, and app-pid logcat
  checks for `FATAL EXCEPTION` and `AndroidRuntime` were empty.

## 2026-05-16 Kotlin Migration Batch 33

- Migrated the reading `PageLoader` base from Java to Kotlin.
- Preserved page status constants, layout/progress helpers, background and text
  drawing, chapter parsing and preloading, page-turn cancellation, read-record
  saving, readable-end callbacks, and the nested `OnPageChangeListener`
  contract.
- Source shape after this batch: 0 Java files and 153 Kotlin files under
  `app/src/main`.
- Validation:
  `:app:compileDebugKotlin :app:compileDebugJavaWithJavac` passed before this
  slice's source-contract update. `KotlinMigrationContractTest`,
  `HomeUiResourceContractTest`, `PageLoaderLayoutTest`,
  `DeprecatedZhuishuCleanupContractTest`, and `CollBookHolderLocalBookTest`
  passed. The full `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` sequence also passed.
- ai-app-bridge runtime validation used the existing logged-in app state:
  launched `SplashActivity`, verified `MainActivity`/`书架`, opened `黄昏分界`
  into `ReadActivity`, verified the full-screen `PageView`, performed a
  horizontal page swipe, and observed 200 responses for `getBookInfoBatch` and
  `getBookFolder`. It then relaunched to the shelf, opened the local TXT
  `codex-local-import-probe`, verified `ReadActivity`/`PageView`, performed a
  horizontal page swipe, and checked logcat evidence for
  `LocalPageLoader +refreshChapterList`, `PageLoader +loadPages`,
  `PageLoader drawContent`, and rendered local text. App-pid logcat checks for
  `FATAL EXCEPTION` and `AndroidRuntime` were empty in both passes.

## 2026-05-16 Architecture Migration Batch 34

- Removed the global RxJava event bus path for bookshelf sync:
  `RxBus` and `BookSyncEvent` are gone from main sources.
- Added `BookshelfSyncRequest` as an explicit Activity result contract.
  `LoginActivity` and `SettingsActivity` now return that result after a
  successful login or sync action, `MineFragment` and `MainActivity` consume the
  result, and `MainActivity.requestBookShelfSync()` calls the current
  `BookShelfFragment.requestBookShelfSync()` directly.
- Preserved the existing server-sync behavior: logged-in mobile users still
  execute `getBookShelfByMobile`, refresh missing book info/folders, and post
  the merged shelf with `synBookShelfByMobile`.
- Source shape after this batch: 0 Java files and 152 Kotlin files under
  `app/src/main`.
- Validation:
  `:app:compileDebugKotlin :app:compileDebugJavaWithJavac` passed. The first
  focused test run hit Gradle daemon heap OOM during unit-test kapt at the
  default 1.5GiB heap, then the same focused tests passed with
  `-Dorg.gradle.jvmargs=-Xmx3072m`: `KotlinMigrationContractTest`,
  `HomeUiResourceContractTest`, `LoginUiResourceContractTest`, and
  `BookShelfPresenterFilterTest`. The full `:app:testDebugUnitTest
  :app:assembleDebug :app:installDebug` sequence also passed with the same heap
  setting.
- ai-app-bridge runtime validation used the existing logged-in app state:
  launched `SplashActivity`, verified `MainActivity`/`书架`, navigated to
  `我的` -> `设置`, opened `SettingsActivity`, tapped `书架备份与同步`, observed
  the return to `MainActivity`, and captured 200 responses for
  `getBookShelfByMobile`, `getBookInfoBatch`, `getBookFolder`, and
  `synBookShelfByMobile`. App-pid logcat checks for `FATAL EXCEPTION` and
  `AndroidRuntime` were empty.

## 2026-05-16 Architecture Migration Batch 35

- Migrated the search page from MVP callbacks to MVVM/LiveData.
- Added `SearchViewModel` with `LiveData` streams for hot words, keyword
  suggestions, book results, and book-search errors. It still uses the existing
  `RemoteRepository` RxJava contracts internally so this slice changes UI
  ownership without changing network contracts.
- `SearchActivity` now extends `BaseActivity`, creates the ViewModel with
  `ViewModelProvider`, observes its state, and calls ViewModel methods for hot
  words, keyword suggestions, and book search. `SearchPresenter` and
  `SearchContract` are removed.
- Source shape after this batch: 0 Java files and 151 Kotlin files under
  `app/src/main`.
- Validation:
  `:app:compileDebugKotlin :app:compileDebugJavaWithJavac` passed.
  `KotlinMigrationContractTest` and `DeprecatedZhuishuCleanupContractTest`
  passed. The full `:app:testDebugUnitTest :app:assembleDebug
  :app:installDebug` sequence passed with
  `-Dorg.gradle.jvmargs=-Xmx3072m`.
- ai-app-bridge runtime validation used the existing logged-in app state:
  launched `SplashActivity`, verified `MainActivity`/`书架`, tapped the search
  action into `SearchActivity`, verified the visible search input, entered
  `xian`, observed 200 responses for `/book/hot-word` and
  `/book/auto-complete` through the ViewModel-driven page, tapped 搜索, and
  observed a 200 response for `/search?bookName=xian`. The search result panel
  became visible and app-pid logcat checks for `FATAL EXCEPTION` and
  `AndroidRuntime` were empty.

## 2026-05-16 Architecture Migration Batch 36

- Migrated the login page from MVP callbacks to MVVM/LiveData.
- Added `LoginViewModel` with `LiveData` streams for password-login results,
  direct-login results, SMS-login results, login errors, and direct-login
  prompts. It still uses the existing `RemoteRepository`, MobPush, SMSSDK
  handoff, and SecVerify contracts internally so this slice changes UI ownership
  without changing auth/network contracts.
- `LoginActivity` now extends `BaseActivity`, creates the ViewModel with
  `ViewModelProvider`, observes login state, and keeps SMS event-handler and
  countdown lifecycle ownership in the Activity. `LoginPresenter` and
  `LoginContract` are removed.
- Source shape after this batch: 0 Java files and 150 Kotlin files under
  `app/src/main`.
- Validation:
  `:app:compileDebugKotlin :app:compileDebugJavaWithJavac` passed.
  `KotlinMigrationContractTest` and `LoginUiResourceContractTest` passed. The
  full `:app:testDebugUnitTest :app:assembleDebug :app:installDebug` sequence
  passed with `-Dorg.gradle.jvmargs=-Xmx3072m`.
- ai-app-bridge runtime validation used the existing logged-in app state:
  launched `SplashActivity`, verified `MainActivity`/`书架`, navigated to
  `我的` -> `账号`, opened `LoginActivity`, and verified `退出登录` was visible.
  App-pid logcat checks for `FATAL EXCEPTION` and `AndroidRuntime` were empty.

## 2026-05-16 Architecture Migration Batch 37

- Migrated the book-detail page from MVP callbacks to MVVM/LiveData.
- Added `BookDetailViewModel` with `LiveData` streams for detail refresh
  results, refresh errors, add-to-bookshelf waiting, add-to-bookshelf success,
  and add-to-bookshelf failure. It still uses the existing `RemoteRepository`,
  `BookRepository`, and bookshelf sync RxJava contracts internally so this slice
  changes UI ownership without changing network or storage contracts.
- `BookDetailActivity` now extends `BaseActivity`, creates the ViewModel with
  `ViewModelProvider`, observes detail/add-to-shelf state, and keeps the
  existing cover/title/author/brief/read-button UI updates in the Activity.
  `BookDetailPresenter` and `BookDetailContract` are removed.
- Source shape after this batch: 0 Java files and 149 Kotlin files under
  `app/src/main`.
- Validation:
  `:app:compileDebugKotlin :app:compileDebugJavaWithJavac` passed.
  `KotlinMigrationContractTest`, `DeprecatedZhuishuCleanupContractTest`, and
  `HomeUiResourceContractTest` passed with `-Dorg.gradle.jvmargs=-Xmx3072m`.
  The full `:app:testDebugUnitTest :app:assembleDebug :app:installDebug`
  sequence also passed with the same heap setting.
- ai-app-bridge runtime validation used the existing logged-in app state:
  launched `SplashActivity`, verified `MainActivity`/`书架`, searched `xian`,
  opened result `遗落仙境` into `BookDetailActivity`, verified
  `书籍详情`/`遗落仙境`/`开始阅读` were visible, observed 200 responses for
  `/search?bookName=xian` and `/getBookInfo?bookId=-203144539`, and app-pid
  logcat checks for `FATAL EXCEPTION` and `AndroidRuntime` were empty.

## 2026-05-16 Architecture Migration Batch 38

- Migrated the bookshelf page from MVP callbacks to MVVM/LiveData.
- Moved bookshelf refresh, server shelf fetch, shelf sync, update checks,
  category refresh, filter helpers, and sync ID normalization into
  `BookShelfViewModel`. It still uses the existing `RemoteRepository`,
  `BookRepository`, and RxJava repository contracts internally.
- `BookShelfFragment` now extends `BaseFragment`, creates the ViewModel with
  `ViewModelProvider`, observes shelf/update/sync/error/completion state, and
  keeps list rendering, filter UI, edit mode, delete dialogs, local-import entry,
  and MobPush registration ownership in the Fragment. `BookShelfPresenter`,
  `BookShelfContract`, and unused `BaseMVPFragment` are removed.
- `BookDetailViewModel` and `BookshelfFilterMenuView` now call the moved
  bookshelf helpers through `BookShelfViewModel`.
- Source shape after this batch: 0 Java files and 147 Kotlin files under
  `app/src/main`.
- Validation:
  `:app:compileDebugKotlin :app:compileDebugJavaWithJavac` passed.
  `KotlinMigrationContractTest`, `DeprecatedZhuishuCleanupContractTest`,
  `HomeUiResourceContractTest`, `BookShelfPresenterFilterTest`, and
  `BookShelfPresenterSyncTest` passed with
  `-Dorg.gradle.jvmargs=-Xmx3072m`. The full `:app:testDebugUnitTest
  :app:assembleDebug :app:installDebug` sequence also passed with the same heap
  setting.
- ai-app-bridge runtime validation used the existing logged-in app state:
  launched `SplashActivity`, verified `MainActivity`/`书架`, opened the
  bookshelf filter, selected `本地书`, verified `codex-local-import-probe` stayed
  visible under the local filter, reset to `全部书籍`, navigated to `我的` ->
  `设置`, tapped `书架备份与同步`, and returned to `MainActivity`. Network evidence
  showed 200 responses for `/getBookInfoBatch`, `/getBookShelfByMobile`, and
  `/synBookShelfByMobile`; app-pid logcat checks for `FATAL EXCEPTION` and
  `AndroidRuntime` were empty.

## 2026-05-16 Architecture Migration Batch 39

- Migrated the read page from MVP callbacks to MVVM/LiveData.
- Moved category loading, chapter content loading, chapter refresh/source
  switching, and chapter subscription cancellation into `ReadViewModel`. It
  still uses `RemoteRepository`, `BookRepository`, RxJava, and PageLoader-facing
  contracts internally.
- `ReadActivity` now extends `BaseActivity`, creates `ReadViewModel` with
  `ViewModelProvider`, observes category/chapter-finished/chapter-error state,
  and keeps PageLoader, reading menus, brightness observer, wake lock, reading
  stats, back navigation, and collection flow in the Activity.
- Removed `ReadPresenter`, `ReadContract`, `BaseMVPActivity`, `BaseContract`,
  and `RxPresenter`. Main sources no longer contain an MVP presenter, contract,
  or MVP base layer.
- Source shape after this batch: 0 Java files and 143 Kotlin files under
  `app/src/main`.
- Validation:
  `:app:compileDebugKotlin :app:compileDebugJavaWithJavac` passed.
  `KotlinMigrationContractTest`, `DeprecatedZhuishuCleanupContractTest`,
  `HomeUiResourceContractTest`, `PageLoaderLayoutTest`, and
  `CollBookHolderLocalBookTest` passed with
  `-Dorg.gradle.jvmargs=-Xmx3072m`. The full `:app:testDebugUnitTest
  :app:assembleDebug :app:installDebug` sequence also passed with the same heap
  setting.
- ai-app-bridge runtime validation used the existing SMS-login state: launched
  `SplashActivity`, verified `MainActivity`/`书架`, opened the network shelf
  book `叩问仙道` into `ReadActivity`, verified the full-screen `PageView`,
  performed a horizontal read-page swipe, and observed 200 responses for
  `/getBookFolder` and `/getBookContent`. It then returned to the shelf, opened
  local TXT `codex-local-import-probe`, verified `ReadActivity`/`PageView`, and
  performed another horizontal read-page swipe. App-pid logcat checks for
  `FATAL EXCEPTION`, `AndroidRuntime`, `PageLoader`, and `LocalPageLoader`
  errors were empty.

## 2026-05-16 Architecture Migration Batch 40

- Removed RxJava from app main sources and Gradle dependencies.
- Replaced Retrofit RxJava return types with `Call<T>` and removed
  `adapter-rxjava2`. `RemoteRepository` now exposes suspending methods and
  executes Retrofit calls on `Dispatchers.IO`, still failing directly on HTTP
  errors or missing response bodies.
- Converted `SearchViewModel`, `LoginViewModel`, `BookDetailViewModel`,
  `BookShelfViewModel`, and `ReadViewModel` from `CompositeDisposable`/`Single`
  subscriptions to `viewModelScope` coroutine jobs.
- Replaced local repository Rx wrappers with direct methods:
  `getBookChapters` and `deleteCollBookWithFiles`.
- Removed `RxUtils`, the remaining RxJava disposable helpers from
  `BaseActivity`/`BaseFragment`, and RxJava background parsing/preload usage in
  `PageLoader` and `LocalPageLoader`.
- Source shape after this batch: 0 Java files and 142 Kotlin files under
  `app/src/main`.
- Focused validation:
  `:app:compileDebugKotlin :app:compileDebugJavaWithJavac` passed.
  `KotlinMigrationContractTest`, `BookRepositoryStorageContractTest`,
  `DeprecatedZhuishuCleanupContractTest`, `HomeUiResourceContractTest`,
  `PageLoaderLayoutTest`, and `CollBookHolderLocalBookTest` passed with
  `-Dorg.gradle.jvmargs=-Xmx3072m`. Static search found no `io.reactivex`,
  `RxUtils`, `Single<`, `Observable<`, `CompositeDisposable`,
  `org.reactivestreams`, `adapter-rxjava`, `rxjavaVersion`, or
  `rxandroidVersion` in main sources and Gradle files.
- Runtime validation initially found two non-collected network read-path
  crashes where `ReadViewModel` still assumed the book was already stored in
  `BookRepository`. `ReadActivity` now passes the current `CollBookBean`
  directly into category/chapter loading and source refresh, preserving the
  non-collected read contract without adding fallback mapping.
- Full validation:
  `:app:testDebugUnitTest :app:assembleDebug :app:installDebug` passed with
  `-Dorg.gradle.jvmargs=-Xmx3072m`.
- ai-app-bridge runtime validation used the existing SMS-login state: launched
  `SplashActivity`, verified `MainActivity`/`书架`, searched `xian`, opened
  `遗落仙境` into `BookDetailActivity`, tapped `开始阅读`, verified
  `ReadActivity`/`目录`, verified the visible full-screen `PageView`, and
  performed a horizontal read-page swipe. Bridge network capture showed 200
  responses for `/search?bookName=xian`, `/getBookInfo`, `/getBookFolder`, and
  `/getBookContent`. It then relaunched the shelf, opened local TXT
  `codex-local-import-probe`, verified `ReadActivity`/`PageView`, and performed
  another horizontal read-page swipe. AndroidRuntime logcat checks were empty in
  both passes.

## 2026-05-17 Local TXT Reader Regression Fix

- Fixed a regression where collected local TXT books could remain on the
  `正在排版请等待` page after the coroutine/RxJava removal batch.
- `ReadActivity` now keeps local collected books on the local chapter pipeline
  after refreshing the DB chapter list. Local books no longer request the remote
  `/getBookFolder` directory endpoint with a local file id.
- `PageLoader.prepareDisplay` now opens the chapter when local parsing finished
  before the `PageView` size was prepared. This preserves the direct local parse
  flow instead of leaving `STATUS_PARING` on screen.
- Added regression contract coverage in `HomeUiResourceContractTest` and
  `PageLoaderLayoutTest`.
- Runtime validation on the OnePlus PKR110 opened local TXT
  `codex-local-import-probe` into `ReadActivity`; the page rendered chapter
  content instead of the layout wait screen, the post-open network capture was
  empty, and app-pid error logcat was empty.
- Validation:
  `:app:testDebugUnitTest` passed with `-Dorg.gradle.jvmargs=-Xmx3072m`.
  `:app:assembleDebug :app:installDebug` also passed with the same heap setting.

## 2026-05-17 Source Engine M1/M2

- Added the independent `:source-engine` module for the Legado-compatible local
  source-engine track. The module currently owns typed engine results, import
  failures, source diagnostics, source metadata, and Legado rule-set models.
- Implemented the first `LegadoSourceImporter` contract: parse supported source
  metadata, header objects or JSON-object header strings, and the four core rule
  groups. Missing `bookSourceName` or `bookSourceUrl` rejects the source with a
  typed contract failure. Unsupported or unknown fields produce explicit
  diagnostics instead of being silently mapped.
- Added a debug-only `SourceEngineLabActivity` under `app/src/debug`. It imports
  a local sample source and displays source count, rejected count, diagnostic
  count, and unsupported-field evidence. The lab Activity is exported for direct
  device validation but is not registered in the main manifest and is not linked
  from the normal reader UI.
- Added `SourceEngineIsolationContractTest` to keep the new entry isolated:
  `:app` uses `debugImplementation project(':source-engine')`, the lab entry is
  debug-manifest only, and current `ReadViewModel` / `SearchViewModel` do not
  import or call the source engine.
- Validation:
  `:source-engine:test`, `:app:testDebugUnitTest`, and
  `:app:assembleDebug :app:installDebug` all passed with
  `-Dorg.gradle.jvmargs=-Xmx3072m`.
- ai-app-bridge runtime validation on OnePlus PKR110:
  launched the normal `SplashActivity`, verified `MainActivity` / `书架`, saw
  bridge `0.1.8`, visible bookshelf nodes, no app-pid error logcat output, and
  existing backend requests for `/getBookInfoBatch` and `/getBookFolder`. Then
  launched `com.ldp.reader.debug.SourceEngineLabActivity` directly, verified
  `Source Engine Lab`, `Import result`, `Codex Sample Source`, and
  `unsupported_top_level_field` in the view tree. `network --since-id 2`
  returned zero new records after entering the lab.

## 2026-05-18 Source Engine Storage Import

- Upgraded the debug-only source-engine lab from sample-only import to real
  app-private storage import. On launch and on the `Import storage` button, the
  lab reads `files/source-engine/book-sources.json`, imports it through
  `LegadoSourceImporter`, and renders accepted source count, rejected source
  count, diagnostics, and unsupported-field evidence.
- Kept the sample import button as a local fixture path only. Storage import is
  explicit and does not silently substitute the sample when the file is missing.
- Added a storage fixture with one accepted source and one rejected source, then
  expanded importer tests and app isolation tests so storage import shape,
  `book-sources.json`, and the debug-only lab controls are pinned.
- Validation:
  `:source-engine:test`, `:app:testDebugUnitTest`, and
  `:app:assembleDebug :app:installDebug` passed with
  `-Dorg.gradle.jvmargs=-Xmx3072m`.
- Device storage-import validation on OnePlus PKR110:
  wrote `codex-storage-book-sources.json` into
  `files/source-engine/book-sources.json` under `com.ldp.reader`, launched
  `SourceEngineLabActivity`, and verified the view tree showed
  `Storage import`, `sources=1`, `rejected=1`, `Codex Storage Source`, and
  `unsupported_top_level_field`. ai-app-bridge reported network count `0` for
  the lab import and app-pid error logcat was empty.

## 2026-05-18 Source Engine Real Legado Chain

- Imported the real Legado source dump into reader-owned app-private storage.
  The previous 871-byte storage file was only the local fixture; the verified
  device file is now
  `files/source-engine/book-sources.json` at 22,754,277 bytes.
- The real dump came from Legado Web API data, was normalized to a standalone
  source array, and is now owned by reader. Runtime parsing no longer depends
  on Legado after the JSON is copied into reader storage.
- Added real source-chain execution in the debug-only lab:
  search -> detail -> catalog -> first chapter content. The lab reports each
  source attempt and the final source/book/chapter/content evidence.
- Extended the first rule subset based on real failures:
  `@JSon:`/`@JSON:` prefixes, JSONPath wildcard arrays such as `[*]`, combined
  `&&` rule fragments, multi-index exclusions, HTML meta charset decoding, and
  shorter lab network timeouts for bad sources.
- Kept the normal app path isolated. `:app` still depends on `:source-engine`
  only as `debugImplementation`, the lab Activity remains under `app/src/debug`,
  and the normal reader/search/read code was not routed to the new engine.
- Device validation on OnePlus PKR110:
  launched `SourceEngineLabActivity`, verified storage import with
  `sources=6500`, `rejected=1`, and then tapped `Run chain`. The lab completed
  with `Full chain verified`: keyword `斗破苍穹`, source `👍 悠久小说`,
  book `斗破苍穹`, author `天蚕土豆`, catalog `1644` chapters, first chapter
  `第一章 陨落的天才`, and `contentChars=1327` with readable Chinese正文 preview.
- Normal-entry validation after the lab run:
  launched `SplashActivity`; ai-app-bridge reported `MainActivity`, proving the
  default app entry did not enter the source-engine lab. App-pid logcat had no
  `AndroidRuntime` or `FATAL EXCEPTION`.
- Validation commands passed:
  `:source-engine:test :app:assembleDebug`,
  `:source-engine:test :app:assembleDebug :app:installDebug`, and
  `:app:testDebugUnitTest`, all with `-Dorg.gradle.jvmargs=-Xmx3072m`.

## 2026-05-18 Source Engine Formal Home Entry And Cleaning

- Promoted the source-engine screen from a debug-only lab to an explicit formal
  app entry: the bookshelf home toolbar now shows `书源`, which opens
  `SourceEngineActivity`.
- Kept the old reader route unchanged. `ReadViewModel` and `SearchViewModel`
  still do not import or call `sourceengine`; backend books still use the
  existing `RemoteRepository` path.
- Moved the app dependency to `implementation project(':source-engine')` only
  because the formal entry now calls the engine directly. The dependency does
  not replace search, detail, catalog, or reading flows.
- Added deterministic catalog and content algorithms in `:source-engine`:
  chapter title normalization, Arabic/Chinese ordinal extraction,
  canonical chapter fusion, duplicate chapter counting, missing ordinal range
  detection, HTML/text cleanup, duplicate line removal, pollution marker
  removal, and content quality scoring.
- `SourceEngineActivity` now runs the full chain:
  storage import -> source search -> detail -> canonical catalog ->
  cleaned content -> quality report -> content preview.
- Added unit coverage for chapter normalization/fusion and content cleaning
  quality reports. Updated app contract tests to pin the formal entry, the
  homepage button, and the preserved backend-owned reader/search path.

Validation:

- `:source-engine:test` passed with `-Dorg.gradle.jvmargs=-Xmx3072m`.
- `:app:testDebugUnitTest :app:assembleDebug` passed with
  `-Dorg.gradle.jvmargs=-Xmx3072m`.
- `:app:assembleDebug :app:installDebug` passed after the final APK update.
- Device file check on OnePlus PKR110:
  `files/source-engine/book-sources.json` is still `22,754,277` bytes.
- Runtime path:
  launched `SplashActivity`, verified `MainActivity` shows `书源`, tapped it,
  verified `SourceEngineActivity`, and verified automatic storage import with
  `sources=6500`, `rejected=1`.
- Full-chain runtime evidence from the formal entry:
  `Full chain verified`, keyword `斗破苍穹`, `testedSources=77`,
  source `👍 55读书`, book `斗破苍穹`, author `作者：天蚕土豆`,
  `canonicalChapters=1619`, `duplicateChapters=41`, and
  `contentQualityScore=100`.
- App-pid error logcat after the full-chain run and return to home was empty.

## 2026-05-18 Switchable Reader Provider And Rollback

- Added a switchable reader boundary instead of replacing the old route in
  place. `BookContentProviderRouter` now owns search, detail, catalog, and
  content routing.
- Rollback is explicit: `SourceEngineSwitch` persists
  `source_engine_reader_enabled`. `SourceEngineActivity` exposes
  `启用书源阅读` and `回到后端`.
- Backend mode uses `BackendReaderContentProvider`, which delegates to the
  existing `RemoteRepository`.
- Source-engine mode uses `SourceEngineReaderContentProvider`, which imports
  the reader-owned source JSON, searches compatible Legado sources, builds a
  canonical catalog, and returns cleaned content.
- Search hot words and keyword suggestions are routed through the same switch.
  Source-engine mode does not silently call the backend for these side
  requests.
- Added content quality gates: cleaned content shorter than 200 characters or
  quality below 70 is rejected instead of displayed as a chapter body.
- Main search now scans source-engine sources with bounded concurrency and a
  total timeout, then logs `searchCompleted` with count and duration.

Validation:

- `:source-engine:test :app:testDebugUnitTest` passed with
  `-Dorg.gradle.jvmargs=-Xmx3072m`.
- `:source-engine:test :app:assembleDebug :app:installDebug` passed with
  `-Dorg.gradle.jvmargs=-Xmx3072m`.
- Device storage remained present:
  `files/source-engine/book-sources.json` = `22,754,277` bytes.
- Formal entry full-chain validation still completed:
  `Full chain verified`, `sources=6500`, `searchable=3223`, keyword
  `斗破苍穹`, source `👍 悠久小说`, book `斗破苍穹`,
  `canonicalChapters=1595`, `duplicateChapters=49`, and
  `contentQualityScore=92`.
- Source-engine switch validation with ai-app-bridge:
  `当前阅读链路：书源引擎`, then SearchActivity logs showed
  `operation=hotWords provider=source-engine`,
  `operation=keyWords provider=source-engine`, and
  `operation=search provider=source-engine`.
- Main search no longer stayed in the loading state for the adb-entered test
  query. The log showed
  `operation=searchCompleted provider=source-engine key=doupocangqiong count=30 durationMs=30451`.
- Detail route validation from a source-engine search result logged
  `operation=detail provider=source-engine`.
- Rollback validation:
  tapping `回到后端` changed the visible mode to `当前阅读链路：后端`; reopening
  SearchActivity then logged
  `operation=hotWords provider=backend sourceEngineEnabled=false`.

## 2026-05-18 AI Bridge Unicode Input Update

- Updated reader debug bridge dependencies to
  `ai-app-bridge-gradle-plugin:0.1.9` and
  `ai-app-bridge-android:0.1.9`.
- This picks up bridge-native `/v1/action/input-text`, so validation agents can
  input Chinese through the app bridge instead of falling back to
  `adb shell input text` on Android 16.
- Validation used `ai-app-bridge@0.1.25` on OnePlus PKR110:
  `/v1/status` reported bridge `0.1.9`, `wait-text 书架` passed in
  `MainActivity`, SearchActivity accepted `斗破苍穹` through `transport=bridge`
  into `com.ldp.reader:id/search_et_input`, and app-pid error logcat was empty.

## 2026-05-19 Source Engine Reader UX Regression Pass

- Reproduced the user-visible `叩问仙道` crash after opening the search result
  detail page and entering reading. SpiderMan showed `Index 0 out of bounds for
  length 0` from `EasyAdapter.getItem(EasyAdapter.kt:19)` through the
  `ReadActivity` change-source click handler, which could read
  `mCategoryAdapter.getItem(chapterPos)` before the source-engine catalog list
  had populated.
- Fixed the crash boundary:
  `ReadActivity` now ignores change-source taps while the catalog adapter is
  empty or the current chapter position is out of range; `BookDetailActivity`
  now refuses start/add actions until `mCollBookBean` exists;
  `CollBookBean` now preserves `bookIdInBiquge` through Parcel and accepts a
  null chapter list without throwing.
- Fixed a search UX overlap found during the same pass. `SearchActivity` now
  hides assistant and hot-search panels once a real query/result list is active,
  so accessibility and screenshots no longer mix hot words into the result
  list.
- Source-engine shelf identity was kept stable at the user layer:
  canonical-title shelf IDs are used for collected books, the concrete
  source-engine route is stored separately in `bookIdInBiquge`, repository
  reads/writes merge duplicate source-engine shelf rows by canonical title, and
  detail/re-open paths resolve an existing collected book before creating a new
  one.
- Cover selection now rejects known bad logo-like source URLs and prefers a
  valid cover from any merged source candidate. This specifically fixed the
  earlier `斗破苍穹` case where one source supplied an incorrect blue-logo cover
  and another valid source had the real cover.

Runtime validation through AI Bridge MCP on OnePlus PKR110:

- Clean installed the fixed debug APK with MCP `install_apk`, launched the app,
  and verified bridge status on `MainActivity`.
- Search `叩问仙道` returned one visible result: title `叩问仙道`, author
  `雨打青石`. Detail showed the same title/author, latest chapter
  `第二千六百九十一章 不同的选择`, and a real cover.
- Tapping `开始阅读` entered `ReadActivity` without returning to SpiderMan.
  The first chapter rendered as `第一章 又一世少年`; a stress tap on the
  change-source area during load no longer produced `AndroidRuntime`,
  `CrashActivity`, or `IndexOutOfBounds`.
- Adding from detail changed the button to `放弃`. Returning to the shelf showed
  exactly one `叩问仙道` row. Opening that shelf row entered `ReadActivity` and
  routed through the stored concrete source-engine book ID.
- Catalog validation for `叩问仙道` logged
  `chapters=2732`, `duplicates=0`, first `第一章 又一世少年`, last
  `第二千六百九十一章 不同的选择`. The visible catalog began with chapters
  1-14 in order, so it did not show the common failure mode where a site's
  latest chapters are inserted at the top of the catalog.
- Duplicate prevention was rechecked by searching `叩问仙道` again after it was
  already collected. The detail page showed collected state (`放弃` /
  `继续阅读`), and the shelf still had one `叩问仙道` row.

Search relevance matrix through AI Bridge MCP:

- 20/20 passed with expected top result and no hot-search overlap:
  `斗破` -> `斗破苍穹`;
  `诡秘` -> `诡秘之主`;
  `叩问仙道` -> `叩问仙道`;
  `凡人` -> `《凡人修仙传》`;
  `遮天` -> `遮天`;
  `完美世界` -> `《完美世界》`;
  `牧神记` -> `牧神记`;
  `大奉` -> `大奉打更人`;
  `剑来` -> `剑来`;
  `雪中` -> `雪中悍刀行`;
  `庆余年` -> `庆余年`;
  `吞噬` -> `《吞噬星空》`;
  `神墓` -> `神墓`;
  `一念永恒` -> `一念永恒`;
  `仙逆` -> `仙逆`;
  `求魔` -> `求魔`;
  `圣墟` -> `圣墟`;
  `将夜` -> `将夜`;
  `全职高手` -> `《全职高手》`;
  `择天记` -> `《择天记》`.

Validation:

- `.\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx3072m" :source-engine:test
  :app:testDebugUnitTest :app:assembleDebug` passed.
- No new confirmed AI Bridge MCP defect was found in this pass. The earlier
  `input_text` keyboard/package-name limitations were already tracked in the
  AI Bridge known-issues document, and the long Node REPL timeout observed while
  batching searches was a harness call-duration limit rather than a bridge
  runtime failure.

## 2026-05-19 Source Engine Alias Search Fix

- Reproduced the user report for `灵源仙路` through AI Bridge MCP. The search
  field accepted the query, but the first formal result before the fix was the
  unrelated reordered-title hit `仙路灵源 / 古群`, while the intended book did
  not appear in the visible result list.
- Root cause: this title is indexed under multiple names across public sources.
  Current external indexes commonly expose the book as
  `灵源仙途：我养的灵兽太懂感恩了 / 春雾煮茶`, while `灵源仙路` appears as an
  earlier/alternate name. The app only searched the raw user query through the
  first 48 prioritized sources, so sources that required the newer `仙途` title
  were missed. The ranker also allowed high character-coverage matches even when
  the title order was wrong, which let `仙路灵源` look more relevant than it was.
- Added deterministic alias handling:
  `SourceEngineReaderContentProvider` now expands title alias searches for this
  class of query, including `仙路 -> 仙途` and the known current full title
  `灵源仙途：我养的灵兽太懂感恩了`.
- Tightened ranking:
  `BookSearchRanker` now normalizes title synonyms (`仙途` and `仙路`) before
  scoring, handles a small traditional-Chinese normalization set, and requires
  ordered character coverage for fuzzy high-coverage matches. This prevents
  reordered titles such as `仙路灵源` from being promoted as strongly related.
- Temporary source probe evidence, using the real device
  `files/source-engine/book-sources.json`:
  6500 imported sources, 1345 compatible sources. Raw `灵源仙路` over the first
  48 sources had `exact=0`; after alias expansion, the first 48 sources returned
  `灵源仙途：我养的灵兽太懂感恩了 / 作者：春雾煮茶 / 新笔趣阁` as the top ranked
  result.
- Added regression coverage in `BookSearchRankerTest` so the `灵源仙路` query
  ranks `灵源仙途：我养的灵兽太懂感恩了 / 春雾煮茶` ahead of and filters out
  `仙路灵源 / 古群`.

Runtime validation through AI Bridge MCP on OnePlus PKR110:

- Installed the new debug APK with MCP `install_apk`.
- Searched `灵源仙路`; the visible result list showed
  `灵源仙途：我养的灵兽太懂感恩了` and author `春雾煮茶` as the first result.
- Opened detail successfully. Detail showed the same title/author, latest
  chapter `第1706章 黄枢来袭`, a real intro, and `追更` / `开始阅读`.
- Tapped `开始阅读`; `ReadActivity` rendered `第1章 青元顾安`正文.
- Opened catalog from the reader menu; the visible catalog began in order with
  `第1章 青元顾安`, `第2章 灵鸡场`, `第3章 灵源现，仙途开`, through
  `第14章 前往林家`, confirming the same no-latest-chapters-at-top expectation
  for this book.

## 2026-05-19 Source Engine Tail Pollution Hardening

- Reworked catalog tail probing so it does not scan backward one chapter at a
  time. `CatalogTailBoundaryLocator` probes the last chapter first, then walks
  backward with exponential steps until it finds a readable anchor, and finally
  binary-searches between that readable anchor and the nearest bad chapter.
  Regression tests cover single-chapter, dozens-of-chapters, hundreds-of-
  chapters, and no-readable-anchor bad-tail cases.
- Source-engine detail and catalog loading now both use the trimmed readable
  catalog. Historical reading progress is clamped when the saved chapter index
  points beyond the trimmed catalog, and shelf `lastChapter` is synced to the
  last readable chapter after catalog load.
- Content belonging checks remain deterministic and replaceable. The current
  checker catches the common pattern where the first 200-300 characters match
  the target chapter but the tail switches to unrelated fiction, while keeping
  coherent xianxia chapters with many names and scene changes. The checker
  contract still allows a later local-model implementation to replace this
  deterministic strategy.
- Catalog fusion now filters real non正文 entries seen in live sources,
  including `兄弟们，请一天`, `请假条`, `新书已发...`, `新书，大道之上...`, and
  `更新计划`, without dropping ordinal chapters.

Live source probe evidence:

- A 20-book public-source probe found all 20 titles. 13 latest chapters were
  directly readable; 7 were rejected as either polluted latest chapters or
  non正文 tail entries. The polluted set included
  `我在修仙界万古长青` `第521章 诚不我欺，翻手为云` and `玄鉴仙族`,
  both rejected with `fragmented-tail-after-valid-prefix`,
  `foreign-content-after-valid-prefix`, and `foreign-domain-tail-marker`.
- The same probe found `灵源仙路` through its alias/current indexed title
  `灵源仙途：我养的灵兽太懂感恩了`.

Runtime validation through AI Bridge MCP on OnePlus PKR110:

- Installed the fixed APK with MCP `install_apk`.
- `斗破` search now shows `斗破苍穹 / 天蚕土豆` as the first result with the
  real cover. Exact `斗破苍穹` detail shows the same real cover, latest
  `第一千六百二十三章 结束，也是开始`, and opens `ReadActivity` at
  `第一章 陨落的天才`. Adding it to the shelf shows one `斗破苍穹` item with
  the correct cover.
- Author search `天蚕土豆` returns only author-relevant top entries in the
  visible list, including `元尊`, `斗破苍穹`, `武动乾坤`, `大主宰`, and
  `万相之王`.
- `我在修仙界万古长青` opens to readable chapter text. Its shelf/detail/catalog
  tail is trimmed to `第520章 威逼仙医，长青道丹`; `第521章` and
  `更新计划` are not visible in the catalog tail.
- `叩问仙道` opens from shelf without crashing even with the old saved reading
  index. The reader clamps to the last readable chapter, and the catalog tail
  stops at `第二千六百九十章 宇宙洪荒，混沌星辰`; the bad
  `第二千六百九十一章` is not visible.
- `灵源仙路` search again returns
  `灵源仙途：我养的灵兽太懂感恩了 / 春雾煮茶` as the first result.
- The shelf state after this pass showed one item per collected title:
  `我在修仙界万古长青`, `斗破苍穹`, `叩问仙道`, and
  `灵源仙途：我养的灵兽太懂感恩了`; no duplicate `诡秘之主` or duplicate
  `斗破苍穹` rows were observed.

Validation:

- `.\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx3072m" :app:testDebugUnitTest
  --tests com.ldp.reader.source.CatalogTailBoundaryLocatorTest --tests
  com.ldp.reader.sourceengine.SourceEngineIsolationContractTest
  :source-engine:test --tests
  com.ldp.reader.sourceengine.catalog.ChapterListFusionTest.dropsAnnouncementEntriesFromLongCatalog
  --tests com.ldp.reader.sourceengine.content.ContentCleanerTest` passed.
- `.\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx3072m" :app:assembleDebug` passed.
- No confirmed AI Bridge MCP defect was found. Multi-device install was handled
  by passing `serial`, and native interactions used MCP `tap`, `input_text`,
  `swipe`, `uia_tree`, and `screenshot`.

## 2026-05-20 Real Device 100-Book Validation

- Started the 100-book real-device validation on OnePlus PKR110 through the
  MCP JSON-RPC `tools/call` path. Device operations are driven by
  `build/tmp/reader-device-100-real.mjs`, which talks to `ai-app-bridge-mcp`
  through standard JSON-RPC instead of direct `adb` calls.
- Found the cause of the earlier "search result empty / no visible requests"
  symptom on device: opening the source-engine path initialized
  `LegadoRuleEvaluator` and crashed on Android 16 ICU regex parsing. The
  stored-variable pattern now escapes the closing brace explicitly.
- Fixed the detail waterfall short-circuit: a direct source with only a raw
  catalog is no longer accepted when its tail/content cannot produce enough
  readable chapters. In that case the provider continues resolving through
  the source waterfall and re-ranks candidates.
- Removed the hard author gate from same-title fallback candidates and replaced
  it with same-title inclusion plus author-consensus sorting. This keeps the
  fallback generic while still preferring the author agreed on by most same-
  title candidates when the data is available.
- Targeted MCP regressions before the full run:
  - `何以笙箫默` passed search, detail, reading, and catalog validation.
  - `后宫甄嬛传` passed search, detail, reading, and catalog validation.
  - `步步惊心` now passes the technical reading/catalog path after waterfall
    fallback, but the title-only validation input cannot prove the intended
    author when multiple exact-title books exist.
  - `长安的荔枝` opened and read successfully, but catalog UI validation still
    needs full-run confirmation.
- Known product-data risk: exact-title generic ranking cannot fully
  disambiguate same-title/different-author books without an author or another
  stable metadata signal in the request/test oracle. This is not solved with a
  hard-coded famous-book whitelist.
- Validation harness note: the first attempted full run exited immediately
  because an empty `READER_DEVICE100_ONLY` value was parsed as index `0`. The
  harness now drops blank tokens before numeric parsing, so an unset ONLY means
  "run all selected books".
- Live full-run progress: #1-#10 passed on device
  (`玄鉴仙族`, `我在修仙界万古长青`, `凡人修仙传`, `仙逆`, `求魔`, `我欲封天`,
  `一念永恒`, `赤心巡天`, `道诡异仙`, `苟在妖武乱世修仙`). No WARN/FAIL in
  the first 10.
- Mid-run repair: #15 `剑来` initially failed because the title group returned
  after the first strong catalog candidate and did not wait for later same-
  title candidates. Search title-group validation now waits for more completed
  same-title candidates before early return. Device rerun for #15 passed with
  `55读书`, 1220 readable chapters, and catalog head `第一章 惊蛰`.
- After reinstalling the repaired APK, the full 100-book run restarted from
  #1. New APK progress: #1-#10 passed again with 0 WARN/FAIL.
- New APK progress: #1-#20 passed through the MCP JSON-RPC real-device run,
  including the repaired #15 `剑来` path. Current count: 20 PASS, 0 WARN,
  0 FAIL.
- Second mid-run repair: #23 `牧神记` showed the same-title validation group
  still returned too early after 3 completed candidates. The early-return gate
  now waits for 5 completed candidates, matching the fast validation set. A
  targeted MCP rerun for #23 passed with `52书库.net`, 3210 readable chapters,
  and catalog head `第1页`.
- Final APK full-run restart: #1-#10 passed on OnePlus PKR110 through MCP
  JSON-RPC, 0 WARN, 0 FAIL. This is the current official 100-book run.
- Final APK progress: #1-#20 PASS, 0 WARN, 0 FAIL.
- #22 `圣墟` exposed a catalog-head quality issue: a technically readable
  direct source started at `第二章`. Detail resolution now prefers candidates
  whose catalog starts at the first chapter when available. Targeted MCP rerun
  passed with catalog head `第一章 沙漠中的彼岸花`.
- Current official full run after the catalog-head fix: #1-#10 PASS, 0 WARN,
  0 FAIL.
- Current official full run after the catalog-head fix: #1-#20 PASS, 0 WARN,
  0 FAIL.
- Current official full run after the catalog-head fix: #1-#30 PASS, 0 WARN,
  0 FAIL.
- #34 `雪中悍刀行` exposed that exact-title search could return only invalid
  audiobook-style candidates. Long Chinese titles now add a generic short-
  prefix query while still ranking against the full requested title. Targeted
  MCP rerun passed with `52书库.net`, 2302 chapters, and catalog head `第1页`.
- Current official full run after short-prefix search expansion: #1-#10 PASS,
  0 WARN, 0 FAIL.
- Current official full run after short-prefix search expansion: #1-#20 PASS,
  0 WARN, 0 FAIL.
- Current official full run after short-prefix search expansion: #1-#30 PASS,
  0 WARN, 0 FAIL.
- Current official full run after short-prefix search expansion: #1-#40 PASS,
  0 WARN, 0 FAIL.
- Current official full run after short-prefix search expansion: #1-#50 PASS,
  0 WARN, 0 FAIL.
- Current official full run after short-prefix search expansion: #1-#60 PASS,
  0 WARN, 0 FAIL.
- Design decision: content belonging checks will use per-book character and
  environment fingerprints learned only from trusted chapters. Final chapters
  are treated as untrusted for fingerprint extraction, and the final dozens of
  chapters are near-untrusted unless they have already passed strict continuity
  checks.
- Source-engine concurrency and timeout tuning: search fan-out now allows 64
  concurrent source jobs. Detail fallback search/probe concurrency was raised
  to 48/32, content fallback probes to 16, and cover fallback probes to 16.
  Timeout tuning was revised after real source behavior showed many novel sites
  need longer waits: true network request timeouts must not be below 10s.
  Search fetches are now 10s connect / 15s read, normal source fetches 10s /
  20s, and detail probes 10s / 15s. The waterfall stays fast through concurrent
  fan-out and incremental result merging, not by killing slow-but-good sources
  after 1-3 seconds.
- Request-lifecycle checkpoint: source-engine OkHttp calls now register under
  per-operation request scopes. Search, detail, catalog, content, cover
  refresh, and detached timeout probes cancel their scoped calls when the page
  job finishes, times out, or is cancelled. Search/detail/read ViewModels also
  cancel their active jobs in `onCleared()` so leaving a page releases in-flight
  source requests quickly.
- Content fingerprint implementation checkpoint: the cleaner now accepts a
  per-book weighted character/environment fingerprint. The provider builds it
  only from trusted early and middle catalog samples, explicitly excluding the
  final chapters and the final dozens of chapters from learning. Tail/content
  checks pass the fingerprint into `getCleanContent`.
- Fingerprint detection is no longer gated only by an unpunctuated newline. It
  evaluates a bounded set of candidate breakpoints from unpunctuated hard
  line-breaks, punctuation-density shifts, paragraph-shape shifts, and sampled
  paragraph/window boundaries. Only those candidates are scored against the
  weighted character/environment fingerprint, keeping the check general and
  bounded. Unpunctuated line-breaks inside the high-risk offset band remain a
  high-weight marker.
- Fingerprints are now mutable bounded profiles instead of one-shot static
  snapshots. The initial profile is built from trusted early/middle chapter
  samples that pass the existing readability checks, then successful readable
  chapters can update the same per-book profile. Tail chapters stay excluded
  from learning through the trusted chapter upper bound, so suspected bad
  endings do not poison the book profile. This is the migration path toward
  lowering legacy heuristic weight after enough real-device evidence shows the
  fingerprint profile is stable.
- Real-device `青山` recheck found a true polluted chapter in `672、取剑` from
  `55读书`: the source text keeps a valid opening, then hard-breaks after
  `老耳朵依旧看着大海` without sentence punctuation and switches into fragments
  from unrelated books. Content belonging now has a generic hard-break
  fragmented-tail detector: after a valid prefix, an unpunctuated line break in
  the high-risk offset band plus low-overlap, multi-paragraph character /
  environment churn is rejected without relying on a book/source whitelist.
- Fingerprint profile creation now caches an empty mutable profile even when
  initial trusted sampling times out or yields too few readable samples. Later
  trusted non-tail chapters can still update the same per-book profile, so
  transient device-network slowness does not permanently disable adaptive
  fingerprint learning for that book.
- Fingerprint learning windows were widened for real source latency. Initial
  trusted sampling can use up to 16 chapters and the mutable per-book profile
  can retain up to 64 trusted chapter bodies. Fingerprint/content/tail probes
  now use 15s-class per-request budgets, with larger total windows, so slow
  novel sites do not fail fingerprint learning simply because they need
  5-20 seconds to respond.
