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

- Source shape: 131 Java files, 19 Kotlin files, and 42 layout XML files under
  `app/src/main`.
- Toolchain after the first MMKV slice: AGP 8.2.1, Gradle 8.2, JDK 17, Java
  and Kotlin bytecode target 17.
- Storage: `SharedPreUtils` was the single SharedPreferences wrapper; first
  slice replaces it with MMKV.
- Database: GreenDAO remains active with three persisted entities:
  `CollBookBean`, `BookChapterBean`, and `BookRecordBean`. Generated DAO files
  are still checked in under `model/gen`. ObjectBox is present only as a
  non-production `BookRecord` spike and is not wired into `BookRepository`.
- UI binding: `viewBinding true` is already enabled. Active ButterKnife usage is
  gone. The first Activity-level cleanup removed Search/Main/FileSystem residual
  lookups; remaining `findViewById` calls are mostly widgets, dialogs, base
  toolbar lookup, adapter holders, and local dialog content views.
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
   - The `BookRecord` spike uses ObjectBox's required long object ID and keeps
     the app's string `bookId` as an indexed business key. The spike store is
     intentionally not connected to production reads/writes yet.
   - Next database step: extract a storage interface around current
     `BookRepository` behavior, then move `BookRecordBean` first. Do not migrate
     `CollBookBean` and chapter relations until chapter replacement and ordering
     have implementation-independent tests.

3. Remove manual view lookup by migrating screens/widgets to ViewBinding.
   - Start with Activity/Dialog/Fragment classes, because they have generated
     binding classes and clear lifecycle ownership.
   - Custom views and adapter holders can follow once their ownership boundary is
     clear. Do not invent generic fallback lookup helpers.
   - Remove stale ButterKnife properties/comments in the same local slice where
     the file is migrated.

4. Continue Java to Kotlin migration.
   - Prefer files that already sit next to Kotlin call sites or are being touched
     for ViewBinding/MVVM.
   - Leave generated GreenDAO code as Java until the database decision is made.
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
