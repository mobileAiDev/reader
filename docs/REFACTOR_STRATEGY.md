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

- Source shape: 0 Java files, 142 Kotlin files, and 42 layout XML files under
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
  No MVP presenter, contract, or MVP base layer remains. The global `RxBus` /
  `BookSyncEvent` path has been removed; bookshelf sync requests are owned by
  Activity result contracts and direct `MainActivity` -> `BookShelfFragment`
  calls. Search, login, book detail, and bookshelf are now ViewModel + LiveData
  screens. The read page is also ViewModel + LiveData at the Activity boundary.
  RxJava has been removed from main sources and Gradle dependencies. Retrofit
  APIs now return `Call<T>`, `RemoteRepository` exposes suspending methods, and
  ViewModels/PageLoaders use coroutine jobs for async work.

## Refactor Order

### Local Source Engine Track

The Legado-compatible local source-engine work is tracked separately in
`docs/SOURCE_ENGINE_ITERATION_PLAN.md`. Its first rule is isolation: add a new
engine module and lab entry without changing the current backend search,
bookshelf, catalog, or reading defaults.

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
   - First done batch: `BaseBean`, `BookIdBean`, `ChapterBean`,
     `ContentBean`, `HotWordPackage`, `KeyWordPackage`, and the local `Void`
     marker. The earlier `BookSyncEvent` item from this batch was later removed
     in architecture batch 34.
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
     interfaces. The earlier `SearchContract`, `LoginContract`,
     `BookDetailContract`, `BookShelfContract`, and `ReadContract` items were
     later removed in architecture batches 35, 36, 37, 38, and 39.
   - Eighth done batch: thin constants/progress/network/Rx/MD5/FileStack
     utilities plus small category/page-style/keyword adapter classes. The
     earlier `RxUtils` utility was later removed in architecture batch 40.
   - Ninth done batch: remaining bookshelf/search/category/file list adapters
     and holder classes.
   - Tenth done batch: base RecyclerView/ListView adapter abstractions and
     load-more adapter widgets.
   - Eleventh done batch: ObjectBox entities, stores, and app-backed helper.
   - Twelfth done batch: app entrypoint, MMKV storage wrapper, and the legacy
     `RxBus`, which was later removed in architecture batch 34.
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
     The earlier `RxPresenter` base was later removed in architecture batch 39.
   - Twenty-second done batch: shared file utility helpers.
   - Twenty-third done batch: legacy book cache manager.
   - Twenty-fourth done batch: search and login presenters. The earlier
     `SearchPresenter` and `LoginPresenter` items were later removed in
     architecture batches 35 and 36.
   - Twenty-fifth done batch: read and book-detail presenters. The earlier
     `BookDetailPresenter` and `ReadPresenter` items were later removed in
     architecture batches 37 and 39.
   - Twenty-sixth done batch: bookshelf presenter. The earlier
     `BookShelfPresenter` item was later removed in architecture batch 38.
   - Twenty-seventh done batch: ObjectBox-backed `BookRepository`.
   - Twenty-eighth done batch: reading page animation stack.
   - Twenty-ninth done batch: reading `PageView` touch/animation host.
   - Thirtieth done batch: remove unused Java-only `EncryptUtils`.
   - Thirty-first done batch: network reading `NetPageLoader`.
   - Thirty-second done batch: local TXT reading `LocalPageLoader`.
   - Thirty-third done batch: reading `PageLoader` base.
   - Keep model/API shape unchanged unless a test pins the behavior being
     changed.

5. Move MVP/RxJava flows toward MVVM + LiveData.
   - Start from one vertical feature, not a global base-class rewrite.
   - First done slice: `ReadingStatsActivity` now observes
     `LiveData<ReadingStatsUiState>` from `ReadingStatsViewModel`.
   - Second done slice: `RxBus` and `BookSyncEvent` are removed. Login and
     settings return an explicit `BookshelfSyncRequest` Activity result, while
     home-menu sync directly calls the current `BookShelfFragment`.
   - Third done slice: `SearchActivity` now observes
     `LiveData` from `SearchViewModel`; `SearchPresenter` and `SearchContract`
     are removed from the running architecture.
   - Fourth done slice: `LoginActivity` now observes
     `LiveData` from `LoginViewModel`; `LoginPresenter` and `LoginContract` are
     removed from the running architecture.
   - Fifth done slice: `BookDetailActivity` now observes
     `LiveData` from `BookDetailViewModel`; `BookDetailPresenter` and
     `BookDetailContract` are removed from the running architecture.
   - Sixth done slice: `BookShelfFragment` now observes
     `LiveData` from `BookShelfViewModel`; `BookShelfPresenter`,
     `BookShelfContract`, and the now-unused `BaseMVPFragment` are removed from
     the running architecture.
   - Seventh done slice: `ReadActivity` now observes `LiveData` from
     `ReadViewModel`; `ReadPresenter`, `ReadContract`, `BaseMVPActivity`,
     `BaseContract`, and the now-unused `RxPresenter` are removed from the
     running architecture.
   - Eighth done slice: RxJava is removed from app main sources and Gradle
     dependencies. Retrofit API interfaces now return `Call<T>`,
     `RemoteRepository` runs calls through suspending IO execution, ViewModels
     use `viewModelScope`, and page loaders use coroutine `Job` cancellation.
   - `RxBus` replacement is complete. Keep the remaining migrations on explicit
     state or feature-owned callbacks rather than adding a new global event-bus
     clone.

6. Keep the local source-engine migration explicit.
   - Done slice: `:source-engine` is now available from a formal bookshelf-home
     entry (`书源`) through `SourceEngineActivity`.
   - The old reading/search flow remains backend-owned. `ReadViewModel` and
     `SearchViewModel` must not route to `sourceengine` until a later explicit
     provider migration is implemented.
   - The current source-engine surface is for import, diagnostics, catalog
     fusion, content cleaning, and real-source verification. It is not yet the
     default content provider for ordinary bookshelf books.
   - Future migration should introduce a `BookContentProvider` boundary before
     any reader-flow switch, with the backend provider remaining default until
     runtime evidence says otherwise.

## External Docs Checked

- MMKV official README/wiki: Maven dependency, `MMKV.initialize(this)`, direct
  encode/decode usage, immediate persistence, and v2.x API/ABI floor.
- ObjectBox official docs: Gradle plugin setup, generated model metadata,
  app-level Store initialization, async transactions, Kotlin entity ID rules,
  and Kotlin/Flow/coroutine support.
