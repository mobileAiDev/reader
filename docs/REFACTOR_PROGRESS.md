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
