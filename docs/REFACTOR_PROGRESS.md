# Reader Refactor Progress

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
