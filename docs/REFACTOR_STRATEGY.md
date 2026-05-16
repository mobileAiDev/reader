# Reader Refactor Strategy

## Working Rules

- Scope rule: `C:\AGENTS.md` applies to this repository. There is no deeper
  `AGENTS.md` under `C:\project\reader` at this checkpoint.
- Implementation rule: keep code direct and contract-driven. Do not add
  fallback, compatibility mapping, substitute mapping, or backup logic unless a
  requirement or API contract explicitly requires it.
- Migration rule for this round: old `SharedPreferences` data is intentionally
  not migrated. The only user can log in again and regenerate local settings.

## Verification Loop

Each small refactor slice should update this document or
`docs/REFACTOR_PROGRESS.md`, then pass:

1. A focused unit or source-contract test for the changed contract.
2. `:app:testDebugUnitTest`.
3. `:app:assembleDebug`.
4. Install/run validation with ai-app-bridge: status, visible view tree, key
   navigation, network/logcat evidence where the slice touches runtime behavior.

ai-app-bridge is used as runtime evidence for UI, activity, view-tree, network,
and logcat state. It is not treated as a direct reader for MMKV, GreenDAO, or a
future ObjectBox database.

## Current Baseline

- Source shape: 25 Java files, 129 Kotlin files, and 42 layout XML files under
  `app/src/main`.
- Toolchain after the first MMKV slice: AGP 8.2.1, Gradle 8.2, JDK 17, Java
  and Kotlin bytecode target 17.
- Storage: `SharedPreUtils` was the single SharedPreferences wrapper; first
  slice replaces it with MMKV.
- Database: GreenDAO has been removed from production code. Bookshelf,
  chapter-list, and reading-record storage now use ObjectBox through
  `ObjectBoxBookStore` and `ObjectBoxBookRecordStore`. The app keeps business
  IDs (`bookId`, `_id`, and chapter `id`) separate from ObjectBox's long storage
  IDs.
- UI binding: `viewBinding true` is enabled. Active ButterKnife usage and
  `findViewById` calls are gone from `app/src/main`; a source-contract test now
  fails if either token returns.
- Architecture: `ReadingStatsActivity` is the first MVVM/LiveData slice.
  MVP presenters still own most business flow. RxJava remains in Retrofit,
  repositories, presenters, `RxBus`, file scanning, and page loading.

## Refactor Order

1. Replace `SharedPreferences` with MMKV.
   - Done as a wrapper-level change: callers keep using `SharedPreUtils`.
   - No old-data migration and no SharedPreferences fallback.
   - Runtime expectation: existing login state and local settings reset after
     reinstall or after switching storage.

2. Evaluate ObjectBox before replacing GreenDAO.
   - Current database footprint is small, but the stored objects are central to
     bookshelf, chapter list, and reading progress.
   - The hard part is not entity count; it is preserving relation behavior,
     chapter replacement transactions, generated-code lifecycle, and tests.
   - Official ObjectBox docs say Android integration uses a Gradle plugin,
     generated model metadata, a single app-level store, and committed model
     files. They also call out Kotlin entity rules such as mutable `Long` IDs.
   - This repo still uses Groovy Gradle files, while current ObjectBox docs lead
     with AGP 9/TOML examples. Treat ObjectBox as a spike first: verify plugin
     compatibility, generated files, unit-test support, APK size, and equivalent
     repository queries before choosing migration.
   - First spike result: ObjectBox 5.4.1 works with the current AGP 8.2.1 /
     Gradle 8.2 / Kotlin kapt setup when `io.objectbox` artifacts are resolved
     from Maven Central before the Aliyun mirror. `app/objectbox-models/default.json`
     must be committed with schema changes.
   - The `BookRecord` production slice uses ObjectBox's required long object ID
     and keeps the app's string `bookId` as an indexed business key.
     `BookRepository.saveBookRecord`, `getBookRecord`, and `deleteBookRecord`
     now delegate to `ObjectBoxBookRecordStore`.
   - The full database slice is now complete: `CollBookBean`,
     `BookChapterBean`, and `BookRecordBean` are plain models; GreenDAO plugin,
     generated DAOs, helper classes, and migration helpers are gone; repository
     behavior is covered by source-contract and real ObjectBox JVM tests.

3. Remove manual view lookup by migrating screens/widgets to ViewBinding.
   - Done for main sources. `BaseActivity` gets toolbar ownership from concrete
     Activity bindings; dialogs, fragments, custom views, and adapter holders use
     generated binding classes.
   - The remaining view-related work is cleanup/migration, not removing active
     `findViewById` calls.

4. Continue Java to Kotlin migration.
   - Prefer files that already sit next to Kotlin call sites or are low-risk
     model/event classes with JavaBean-compatible getter/setter shape.
   - There is no generated GreenDAO code left. Generated ObjectBox sources stay
     generated; app-owned models and stores can be migrated by feature slice.
   - First done batch: `BookSyncEvent`, `BaseBean`, `BookIdBean`,
     `ChapterBean`, `ContentBean`, `HotWordPackage`, `KeyWordPackage`, and the
     local `Void` marker.
   - Second done batch: login and shelf-sync response/request models:
     `DirectLoginResultBean`, `LoginResultBean`, `SmsLoginBean`,
     `SyncBookShelfBean`, and `DirectSycBookShelfBean`.
   - Third done batch: ObjectBox-facing storage models `BookRecordBean` and
     `BookChapterBean`.
   - Fourth done batch: remote book search/detail models `BookSearchResult` and
     `BookDetailBeanInOwn`.
   - Fifth done batch: Parcelable/ObjectBox-facing shelf model `CollBookBean`.
   - Sixth done batch: low-level utility, page enum, tab, base adapter, and thin
     search-keyword/search-book adapter classes.
   - Seventh done batch: Retrofit API interfaces and active Presenter contract
     interfaces.
   - Eighth done batch: thin constants/progress/network/Rx/MD5/FileStack
     utilities plus small category/page-style/keyword adapter classes.
   - Ninth done batch: remaining bookshelf/search/category/file list adapters
     and holder classes.
   - Tenth done batch: base RecyclerView/ListView adapter abstractions and
     load-more adapter widgets.
   - Eleventh done batch: ObjectBox entities, stores, and app-backed helper.
   - Twelfth done batch: app entrypoint, MMKV storage wrapper, and RxBus.
   - Thirteenth done batch: static cache, brightness, similarity, and media
     store helper utilities.
   - Fourteenth done batch: local file import fragments for the smart import
     and phone-directory tabs.
   - Fifteenth done batch: isolated custom views, item decorations, Glide
     transform, and Bezier evaluator utilities.
   - Sixteenth done batch: logging, system-bar, screen, reading-stats, and text
     formatting/conversion utilities.
   - Seventeenth done batch: tab widgets, selector widget, rating widget, and
     the `TxtChapter` page model.
   - Eighteenth done batch: root refresh state containers and bookshelf
     scroll-refresh RecyclerView wrappers.
   - Nineteenth done batch: Retrofit helper/repository wiring and lenient Gson
     converter factory.
   - Twentieth done batch: read-setting persistence manager and MediaStore local
     file loader.
   - Twenty-first done batch: RxPresenter base class and read-setting dialog.
   - Twenty-second done batch: shared file utility helpers.
   - Twenty-third done batch: legacy book cache manager.
   - Keep model/API shape unchanged unless a test pins the behavior being
     changed.

5. Move MVP/RxJava flows toward MVVM + LiveData.
   - Start from one vertical feature, not a global base-class rewrite.
   - First done slice: `ReadingStatsActivity` now observes
     `LiveData<ReadingStatsUiState>` from `ReadingStatsViewModel`.
   - Candidate order: settings/login state, bookshelf list state, search, book
     detail, read page.
   - Replace RxJava at feature boundaries only after Retrofit/repository return
     contracts have a tested coroutine or LiveData equivalent.
   - `RxBus` should be replaced with explicit state or event ownership during
     the relevant feature migration, not by a global event-bus clone.

## External Docs Checked

- MMKV official README/wiki: Maven dependency, `MMKV.initialize(this)`, direct
  encode/decode usage, immediate persistence, and v2.x API/ABI floor.
- ObjectBox official docs: Gradle plugin setup, generated model metadata,
  app-level Store initialization, async transactions, Kotlin entity ID rules,
  and Kotlin/Flow/coroutine support.
