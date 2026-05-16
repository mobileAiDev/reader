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
