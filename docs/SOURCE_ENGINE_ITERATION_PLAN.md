# Local Source Engine Iteration Plan

## Current Status - 2026-05-19

The main reader flow has now been migrated to `source-engine` and verified on
device with expanded search, detail, catalog, and content checks. The earlier
plan below remains useful as architectural history, but the default behavior is
no longer "backend first":

- Normal search/detail/catalog/content routes through source-engine.
- Search ranking uses title-group source consensus first, then validation,
  catalog size, cover availability, and source order as tie-breakers.
- The selected reading source and selected cover source can differ inside the
  same title group, so a multi-source book can keep the most complete catalog
  while still showing a verified cover.
- Expanded MCP validation covered 20 title searches and 8 author searches. The
  first result was correct for the sampled popular works and author queries,
  including partial-title searches such as `斗破`, `诡秘`, `凡人`, `大奉`,
  `雪中`, and `鬼吹灯`.
- Catalog fusion now keeps volume restart catalogs instead of collapsing repeated
  `第一章` entries, removes announcement rows, and trims small restarted
  side-story tails after terminal mainline endings. Runtime samples included
  `诡秘之主` at 1396 chapters, `斗破苍穹` at 1646 chapters, `凡人修仙传`
  at 2476 chapters, `庆余年` at 716 chapters, `剑来` at 1293 chapters,
  and `全职高手` at 1762 chapters.
- Chapter content is cleaned and scored by deterministic rules plus a
  replaceable `ContentBelongingChecker` interface for future local model-backed
  checks. Device samples for the tested books reported no cross-book pollution
  markers.
- Full device validation used AI Bridge MCP. Do not use the old note below about
  falling back to the ai-app-bridge CLI for this thread; MCP is the expected
  validation path, with only app launch done outside MCP when the MCP server has
  no generic business-activity launch tool.

## Current Update - 2026-05-20

The next iteration is source-quality routing, because the engine now needs a
stable cold-start order instead of repeatedly scanning a very large source set.

- Global source seed lives in
  `app/src/main/assets/source-quality-seed-v1.tsv`.
- Runtime MMKV stores only local deltas. Updating the app can replace the seed
  with a better global profile without discarding this user's local learning.
- Per-book source scores are not shipped as seed data. They inherit global
  source score first and then evolve only from actual local evidence.
- Routing is a tiered waterfall: tier 1 for fast/broad/fresh/high-quality
  sources, tier 2 for breadth and category coverage, tier 3 for broad fallback.
  Buckets are interleaved inside each tier to avoid overfitting to only one
  site category.
- Adult sources are not specially blocked. Fanfic/tongren remains demoted
  because it usually means a different target book.
- Tail pollution is scored as a small bad-tail penalty plus verified-good
  chapter gain. It should not push generally fresh sources below stale sources
  simply because the latest few chapters are polluted.
- Search/detail/reading timing is split:
  search returns from multi-source title consensus and source scores without
  synchronously cleaning catalogs or chapter content; detail loads direct
  metadata/catalog first; reading, catalog opening, and shelf-intent paths run
  the heavier tail/content validation and update per-book source scores.
- Covers must still appear in the UI. The first search result list may use an
  existing real cover or generated title cover quickly, but later source hits
  must continue trying to fill a real cover and cache it for search/detail/shelf.
- Search ranking must stay generic. It should not depend on a hard-coded
  "known book" or "known author" rescue table; exact-title matches and
  multi-source consensus are enough to let later correct results replace early
  derivative/fanfic-like hits.

Detailed rules are in `docs/SOURCE_QUALITY_ROUTING_DESIGN.md`.

## Pause Checkpoint - 2026-05-21

This checkpoint records the current source-engine tier redesign before the task
is paused. It is not a completed validation report.

- Search, detail, and reading now share the same core model: fill a per-book
  trusted content tier from the source waterfall.
- First display is allowed before the tier is full, but display does not stop
  background filling. Jobs stop only when the user enters another page/book,
  exits the page, or the owning ViewModel is cleared.
- Search and detail fill only an in-memory per-book tier. They must not persist
  tiers, so repeated searches for many names do not create many on-disk tier
  files.
- True reading is the only flow that persists the dedicated tier. Verified
  route IDs are stored in `.source_engine_content_tier` under the current
  book cache folder.
- First display thresholds are now stricter:
  - Search/detail can show a book group after two trusted catalog/content-backed
    sources are available.
  - Reading can render the current chapter only after two distinct trusted
    current-chapter bodies pass fingerprint and quality checks.
  - At least one trusted source must provide a usable cover. Every trusted
    source does not need a cover.
  - A trusted catalog does not mean every raw source catalog row is correct; it
    means fingerprint/tail trimming leaves a normal usable catalog.
- Current chapter work has priority over prefetch. Prefetch remains low
  priority and should be bounded, with the default forward prefetch target at
  five chapters.
- A current chapter should not become permanently stuck in `STATUS_ERROR` while
  a cached body or more source waterfall candidates exist. The source-engine
  path should keep loading/retrying until a trusted result is found or the
  owning job is canceled.

Implementation state at pause:

- `ReadActivity`, `PageLoader`, `NetPageLoader`, `ReadViewModel`,
  `SearchViewModel`, `BookDetailViewModel`, `BookContentProviderRouter`, and
  `SourceEngineReaderContentProvider` have been partially updated for the tier
  model.
- `.\gradlew.bat :app:compileDebugKotlin` passed after these edits.
- Targeted unit tests still need contract updates. Current failing tests are
  old one-source assumptions around search/detail display and provider
  isolation.
- No device install/runtime validation has been run after the latest tier
  changes. Do not treat the new tier implementation as delivered until the
  tests, APK/device run, and manual text checks below pass.

Resume order:

1. Finish updating the old-contract unit tests to the two-trusted-source tier
   model.
2. Run targeted unit tests, then `assembleDebug`.
3. Install the APK and validate search, detail, catalog, reading, cache, and
   cancellation behavior on device.
4. Manually read and compare the target books' final chapters and surrounding
   chapters. The fingerprint result is not enough by itself.
5. Confirm that correct chapters are not trimmed and polluted chapters are not
   kept before expanding from one or two books to five, then ten.

## Session Handoff Contract - 2026-05-21

The next design correction is to stop wasting validated search evidence when
the user opens detail or reading. Search, detail, and reading should be three
owners of the same per-book discovery session, not three independent attempts
that restart the waterfall from zero.

Search page:

- A book group can first display when it has two trusted sources for the same
  chosen book. Same title with different authors remains separate so the user
  can pick the intended book.
- The two trusted sources must agree on normalized author, both have a usable
  intro, both have a usable catalog, and both have actual tail chapter body
  evidence that passes the search-stage quality/readability gate.
- At least one of the trusted sources must have a usable cover.
- Search is one progressive waterfall session. It can publish the first visible
  batch as soon as result groups satisfy the two-trusted-source display gate,
  then continues the same source requests and keeps publishing updated result
  batches as more multi-source groups mature.
- Search result discovery and per-book tier fill are separate jobs under the
  same page lifecycle: discovery keeps finding/verifying groups, while tier fill
  follows only already visible books.
- The query scope is bounded:
  - maximum search-waterfall lifetime: 3 minutes;
  - maximum visible result groups: 30;
  - expensive catalog/content/tier validation continues only for the top 30
    result groups that have multi-source evidence;
  - all in-flight source requests are canceled when the user leaves the search
    page or starts a new query.
- Search stores only in-memory session state. It must not write
  `.source_engine_content_tier`.

Detail page:

- Opening detail from a search result transfers the selected book session or a
  compact immutable snapshot into the detail scope.
- Entering detail cancels the search page's network requests, not just UI
  subscription. Already verified evidence remains available to the detail page.
- Detail should render immediately from the transferred evidence when title,
  author, intro, cover, and trimmed catalog are already present.
- Detail then owns the same book session and continues filling its trusted tier
  up to the target size, default 5. This work is still memory-only unless the
  user enters true reading.

Reading page:

- Opening reading from detail transfers the selected book session again and
  cancels detail-owned in-flight requests.
- Reading persists the dedicated tier for the selected book, then loads
  chapters from the verified tier first.
- If the exact chapter being opened already has two trusted cached bodies, the
  reader can render immediately. If only source/catalog/fingerprint evidence is
  available, reading must stay loading and fetch that exact chapter through the
  trusted tier before rendering.
- If the trusted tier cannot satisfy a chapter, reading continues the source
  waterfall to add more verified routes to the tier. It should not move to a
  permanent `STATUS_ERROR` while more candidates or cached evidence exist.
- Prefetch uses the same rule: only trusted chapter bodies can be cached as
  usable reading content.

Open implementation questions to resolve before coding:

1. Define the exact session key. It should separate same-title different-author
   books and should survive search -> detail -> reading without collapsing the
   user's chosen result into another same-title group.
2. Define the compact evidence payload. It needs route IDs, source metadata,
   trusted catalog, cover/intro evidence, fingerprint profile/evidence, and any
   cached target-chapter bodies, without keeping unbounded raw chapter text in
   memory.
3. Define which chapter search/detail preloads for instant reading. New books
   likely need the first readable chapter; shelf/deep-link reads need the
   saved/current chapter.

## Core Decision

Reader will build a local source engine that is compatible with the Legado
book-source JSON format, but it will not copy Legado business code and will not
depend on a running Legado app.

The first implementation track is independent from the current reader flow:

- Current search, bookshelf, detail, catalog, and reading paths keep using the
  existing backend APIs.
- New source-engine work lives in a separate module and is exercised through a
  separate lab entry.
- The default app behavior must remain fully runnable before, during, and after
  each source-engine slice.
- Switching the main reading flow to the new engine is a later explicit
  migration step, not an implicit fallback.

This also matches the repository rule from `C:\AGENTS.md`: do not add fallback,
substitute mapping, or compatibility behavior unless the requirement or contract
explicitly defines it. Multi-source selection inside the new engine is allowed
only because it is part of the new engine contract and must produce traceable
evidence.

## Current Reader Baseline

- The repo currently has a single Gradle module: `:app`.
- The active remote path is owned by `RemoteRepository` and `BookApiOwn`.
- The current backend chapter/content APIs are:
  - `/getBookFolder`
  - `/getBookContent`
- `ReadViewModel` directly calls `RemoteRepository.getBookFolder()` and
  `RemoteRepository.getBookContent()` for network books.
- Local TXT reading is already separate and must not be affected by this track.
- ai-app-bridge is available in this repo as a debug validation tool:
  - Gradle plugin: `io.github.mobileaidev.aiappbridge.android`
  - Debug runtime dependency:
    `com.github.mobileAiDev.ai-app-bridge:ai-app-bridge-android:0.2.1`
  - CLI commands available on this machine include `status`, `tree`,
    `uia-tree`, `network`, `logcat`, `tap-text`, `wait-text`, and screenshot
    helpers.

Current validation note: use AI Bridge MCP for device interaction. In this
thread the MCP server is called through the Node REPL JSON-RPC bridge helper.
Avoid the ai-app-bridge CLI for runtime testing unless the MCP server itself is
being debugged.

## Non-Negotiable Constraints

1. Existing app behavior stays default.
   - No first-slice change to normal search, bookshelf, detail, catalog, or
     reading entry points.
   - No silent routing from the old backend path to the new source engine.

2. The new engine is isolated.
   - Add a new module such as `:source-engine`.
   - The module must not depend on `:app`.
   - `:app` may depend on `:source-engine` only when the lab entry needs to call
     it.

3. The lab entry is separate.
   - Add a `SourceEngineLabActivity` or equivalent debug/lab screen.
   - Prefer a debug-only manifest entry or a clearly separated route.
   - The lab entry can import sources, search, parse catalog, fetch content, and
     preview fusion/cleaning results without touching the normal reader path.

4. Legado compatibility is format compatibility, not code reuse.
   - Reader imports and stores its own copy of source JSON.
   - Runtime behavior must not require Legado to be installed or running.
   - Unsupported Legado rule features should fail as explicit unsupported-rule
     results with source/rule evidence.

5. No hidden recovery behavior.
   - If a rule is unsupported, parsing fails with a clear engine error.
   - If a source returns malformed data, the source result is marked bad.
   - Cross-source selection is part of the new engine's explicit scoring
     contract, not a hidden fallback for the old backend.

## Proposed Module Boundary

Start with one independent module:

```text
:source-engine
    model/
        BookSource
        SourceBookCandidate
        SourceChapter
        CanonicalBook
        CanonicalChapter
        CanonicalChapterList
        ContentCandidate
        ContentQualityReport
    legado/
        LegadoSourceImporter
        LegadoRuleSet
        UnsupportedRuleFeature
    rule/
        RuleEvaluator
        CssRuleEvaluator
        XPathRuleEvaluator
        JsonPathRuleEvaluator
    search/
        SourceSearchService
        CandidateMerger
    catalog/
        CatalogFetcher
        ChapterNormalizer
        ChapterListFusion
    content/
        ContentFetcher
        ContentCleaner
        ContentQualityScorer
    cache/
        SourceCache
        SourceScoreStore
```

Keep network, parsing, and cache dependencies declared inside `:source-engine`.
The app should see only narrow service interfaces and result models.

## Lab Entry Workflow

The independent lab should support these steps before any main-flow migration:

```text
Import Legado source JSON
    -> Source list and compatibility diagnostics
    -> Search by book name
    -> Candidate book merge preview
    -> Detail and catalog parsing
    -> Catalog normalization/fusion preview
    -> Chapter content candidate fetch
    -> Cleaning and pollution report
    -> Final content preview
```

The lab can expose technical evidence because it is not the consumer reading
experience. The normal product experience should remain simple:

- Search a book.
- Add it to bookshelf.
- Open catalog.
- Read content.

Only after the engine is stable should the product layer hide multi-source
details behind automatic selection and show "change source" or "repair catalog"
only for failure cases.

## Source Engine Data Contracts

The first stable contracts should be explicit and testable:

- `BookSource`
  - source id, name, enabled state, priority, base URL, headers, rule blocks,
    and compatibility diagnostics.

- `SourceBookCandidate`
  - source id, source book id or detail URL, name, author, latest chapter,
    cover URL, intro, and raw score evidence.

- `SourceChapter`
  - source id, source chapter id or URL, raw title, normalized title, inferred
    ordinal, source order, and volume/group marker when available.

- `CanonicalChapterList`
  - merged chapter list for one book, with source coverage and conflict records.

- `ContentCandidate`
  - source id, chapter key, raw content, cleaned content, quality score,
    pollution markers, and failure reason if unusable.

- `EngineResult`
  - success value or typed failure:
    `NetworkError`, `ParseError`, `UnsupportedRule`, `LowQualityContent`,
    `SourceDisabled`, or `ContractViolation`.

## Compatibility Scope

First version target: high-frequency source features, not 100% Legado
compatibility.

Initial support:

- Legado source JSON import.
- Basic HTTP GET/POST rules.
- CSS selector extraction.
- XPath extraction.
- JSONPath extraction.
- Basic URL joining.
- Basic header and user-agent support.
- Search, detail, catalog, and content rule groups.
- Chapter title normalization.
- Simple content cleaning.

Deferred until real sources require them:

- Complex JavaScript execution.
- Login-only sources.
- Cookie lifecycle automation.
- WebView-only dynamic pages.
- Heavy anti-crawler bypass.
- ML-assisted chapter/content classification.

Unsupported features should be recorded in diagnostics instead of being guessed.

## Chapter Fusion And Content Quality

Start deterministic before adding any on-device model:

- Title normalization.
- Chinese numeral and Arabic numeral chapter index extraction.
- Volume/title splitting.
- n-gram or SimHash title similarity.
- Source coverage scoring.
- Missing chapter detection.
- Duplicate chapter detection.
- Content length range checks.
- Paragraph density checks.
- Boilerplate/pollution prefix and suffix detection.
- Cross-source content similarity.
- Previous/next chapter keyword continuity.

On-device models can be evaluated later for "does this content belong to this
book/chapter" as an auxiliary score only. They should not own the main logic in
the first versions.

## Migration Path To Main Reader

Do not replace `RemoteRepository` directly in the first engine slices.

Later, introduce an app-layer abstraction:

```text
BookContentProvider
    BackendBookContentProvider
        -> current RemoteRepository / backend spider
    LocalSourceBookContentProvider
        -> source-engine
```

Migration order:

1. Keep `BackendBookContentProvider` as the only provider used by normal reader.
2. Add `LocalSourceBookContentProvider` only for the lab path.
3. Add side-by-side comparison for the same book/chapter.
4. Add explicit debug switch for internal validation.
5. After enough runtime evidence, make local source engine the default for new
   source-engine books.
6. Keep backend for bookshelf, reading progress, account sync, and source
   configuration sync unless a later contract says otherwise.

## Iteration Milestones

### M0 - Planning Document

- Add this document.
- No production code change.
- No runtime behavior change.

### M1 - Engine Skeleton

- Add `:source-engine` as an isolated module.
- Add core models and typed `EngineResult`.
- Add Legado JSON import for a minimal fixture.
- Add unit tests for supported and unsupported source fields.
- Do not wire the module into normal app flows.

Validation:

- `:source-engine:test`
- `:app:testDebugUnitTest`
- `:app:assembleDebug`

### M2 - Lab Entry

- Add a separate lab screen or debug-only activity.
- Show source import and compatibility diagnostics.
- The normal home/search/read path must remain unchanged.

Validation:

- Launch normal app and verify existing home/read flow with ai-app-bridge.
- Launch lab entry separately and verify it does not alter bookshelf state.
- Check app logcat for `FATAL EXCEPTION` and `AndroidRuntime`.

### M3 - Search Path

- Implement source search for the supported rule subset.
- Add candidate merge preview.
- Store raw source evidence for every candidate.

Validation:

- Unit tests for rule parsing and candidate normalization.
- Lab search against local test sources.
- Existing app search still hits the current backend path.

### M4 - Catalog Path

- Implement detail and catalog parsing.
- Add chapter normalization and first chapter-list fusion.
- Show source coverage and conflicts in the lab.

Validation:

- Unit tests for title normalization and fusion.
- Lab catalog preview for multiple sources.
- Existing `ReadViewModel` path remains backend-owned.

### M5 - Content Path

- Implement content fetching.
- Add deterministic cleaning and quality report.
- Preview raw, cleaned, and rejected content candidates.

Validation:

- Unit tests for cleaning and pollution detection.
- Lab content preview for selected canonical chapter.
- No change to current reader content loading.

### M6 - Cache And Scoring

- Add local source/cache metadata.
- Persist source health and content quality scores.
- Keep cache isolated from current ObjectBox bookshelf/chapter/read-record
  storage unless a later migration contract requires integration.

Validation:

- Cache read/write tests.
- Lab repeated fetch should show cache hit evidence.
- Existing bookshelf/read-record storage tests still pass.

### M7 - Main Flow Adapter

- Add `BookContentProvider` at the app boundary.
- Keep backend provider as default.
- Use the source-engine provider only behind an explicit debug/internal switch
  or for source-engine-originated books.

Validation:

- Side-by-side backend vs local-source evidence.
- ai-app-bridge network evidence proving which path was used.
- Regression checks for backend books and local TXT books.

## Runtime Verification Standard

For slices that touch app runtime behavior:

1. Build and install:

```powershell
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:installDebug -Dorg.gradle.jvmargs=-Xmx3072m
```

2. Verify normal app still runs:

```powershell
ai-app-bridge status --package-name com.ldp.reader
ai-app-bridge tree --package-name com.ldp.reader --visible-only --max-nodes 80
ai-app-bridge logcat --package-name com.ldp.reader --app-pid --level E --lines 200
```

3. Verify lab path separately:

```powershell
ai-app-bridge wait-text --package-name com.ldp.reader --target-text "Source Engine"
ai-app-bridge network --package-name com.ldp.reader --limit 50
```

4. Evidence boundary:
   - Normal reader path should still show current backend requests until M7.
   - Lab path may show source-engine network requests.
   - No `FATAL EXCEPTION` or `AndroidRuntime` errors.

## First Code Slice Recommendation

Start with M1 only:

- Add the `:source-engine` module.
- Add minimal source/import models.
- Parse one tiny Legado-style JSON fixture.
- Return typed unsupported-rule diagnostics for fields not yet implemented.
- Add tests proving supported fields are imported and unsupported fields are not
  silently ignored.

Do not add the lab Activity in the same slice. The first slice should prove the
engine can exist independently while the current app still builds and runs.

## 2026-05-17 Implementation Checkpoint

Current completed scope:

- `:source-engine` module exists and is independent from `:app`.
- The first Legado JSON importer parses supported source metadata, headers, and
  `ruleSearch` / `ruleBookInfo` / `ruleToc` / `ruleContent` rule objects.
- Unsupported or unknown source fields produce explicit diagnostics instead of
  being silently accepted.
- Sources missing required `bookSourceName` or `bookSourceUrl` are rejected with
  typed contract failures.
- A debug-only `SourceEngineLabActivity` imports a local sample source and shows
  source count, rejected count, diagnostic count, and raw diagnostic evidence.
- The debug lab now also reads a real JSON file from app-private storage:
  `files/source-engine/book-sources.json`.
- The lab Activity is registered only under `app/src/debug` and is not reachable
  from the normal app manifest or default reader flow.

Verified boundary:

- Normal reader flow still uses `RemoteRepository` and the existing backend
  `/getBookFolder` / `/getBookContent` path.
- The source-engine storage import is local-only and does not trigger network
  requests while importing source JSON.

## 2026-05-18 Runtime Checkpoint

Completed scope beyond M1/M2:

- The reader app-private source file now holds a real Legado source dump, not
  the old 871-byte fixture. Verified size on device:
  `files/source-engine/book-sources.json` = 22,754,277 bytes.
- The lab imports the real dump as reader-owned JSON and parses `6500` accepted
  sources with `1` rejected source.
- The first real-source execution path is implemented:
  search -> detail -> catalog -> content preview.
- The supported first-rule subset now includes CSS/HTML extraction, basic
  GET/POST URL rules, `@JSon:`/`@JSON:` JSON rules, JSONPath wildcard arrays,
  `&&` combined fragments, multi-index exclusions, HTML meta charset decoding,
  and typed attempt diagnostics.
- Unsupported JS/WebView/login/anti-crawler sources remain explicit failures.
  They are not guessed or silently substituted.
- The default lab chain scans only engine-compatible general sources and records
  every attempt in the visible report.

Verified device evidence:

- `SourceEngineLabActivity` imported the real storage file and showed
  `sources=6500`, `rejected=1`.
- `Run chain` completed with `Full chain verified` for keyword `斗破苍穹`.
- Final evidence from the page:
  source `👍 悠久小说`, book `斗破苍穹`, author `天蚕土豆`, catalog `1644`
  chapters, first chapter `第一章 陨落的天才`, and `contentChars=1327`.
- Launching the normal `SplashActivity` still reached `MainActivity`; the source
  lab is not part of the normal reader route.

Validation commands:

```powershell
./gradlew :source-engine:test :app:assembleDebug -Dorg.gradle.jvmargs=-Xmx3072m
./gradlew :source-engine:test :app:assembleDebug :app:installDebug -Dorg.gradle.jvmargs=-Xmx3072m
./gradlew :app:testDebugUnitTest -Dorg.gradle.jvmargs=-Xmx3072m
adb -s b46093e6 shell run-as com.ldp.reader wc -c files/source-engine/book-sources.json
adb -s b46093e6 shell am start -n com.ldp.reader/.debug.SourceEngineLabActivity
adb -s b46093e6 shell am start -n com.ldp.reader/.ui.activity.SplashActivity
```

## 2026-05-18 Formal Entry Checkpoint

Completed scope:

- The source-engine screen is now a formal, explicit home entry instead of a
  debug-only Activity. The bookshelf home page exposes `书源`, and tapping it
  opens `SourceEngineActivity`.
- The current reader flow is still not silently replaced:
  `ReadViewModel` and `SearchViewModel` continue to use the existing backend
  path and do not import `sourceengine`.
- `:app` now uses `implementation project(':source-engine')` so the formal
  Activity can call the engine, but this dependency is only reached from the
  explicit source-engine entry.
- The engine now includes deterministic chapter and content algorithms:
  `ChapterNormalizer`, `ChapterListFusion`, `ContentCleaner`, and
  `ContentQualityScorer`.
- The formal entry reports canonical catalog size, duplicate count, missing
  ordinal ranges, content quality score, removed lines, pollution markers, and
  cleaned content preview.

Verified device evidence:

- Real reader-owned source JSON remained present after reinstall:
  `files/source-engine/book-sources.json` = `22,754,277` bytes.
- Formal home path was verified with ai-app-bridge:
  `SplashActivity` -> `MainActivity` -> visible `书源` -> `SourceEngineActivity`.
- `SourceEngineActivity` imported the real dump and showed `sources=6500`,
  `rejected=1`.
- `完整验证` completed with `Full chain verified`.
- Final visible evidence from the formal entry:
  keyword `斗破苍穹`, `testedSources=77`, source `👍 55读书`, book `斗破苍穹`,
  author `作者：天蚕土豆`, `canonicalChapters=1619`,
  `duplicateChapters=41`, and `contentQualityScore=100`.
- App-pid error logcat after the full run was empty.

Validation commands:

```powershell
./gradlew :source-engine:test -Dorg.gradle.jvmargs=-Xmx3072m
./gradlew :app:testDebugUnitTest :app:assembleDebug -Dorg.gradle.jvmargs=-Xmx3072m
./gradlew :app:assembleDebug :app:installDebug -Dorg.gradle.jvmargs=-Xmx3072m
adb -s b46093e6 shell run-as com.ldp.reader wc -c files/source-engine/book-sources.json
ai-app-bridge wait-text --package-name com.ldp.reader --target-text "书源" --require-activity MainActivity --timeout-sec 30
ai-app-bridge wait-text --package-name com.ldp.reader --target-text "6500" --require-activity SourceEngineActivity --timeout-sec 30
ai-app-bridge wait-text --package-name com.ldp.reader --target-text "Full chain verified" --require-activity SourceEngineActivity --timeout-sec 180
```

## 2026-05-18 Switchable Main Reader Path

Completed scope:

- Introduced `ReaderContentProvider` and `BookContentProviderRouter` as the
  single routing point for search, detail, catalog, and chapter content.
- Added `BackendReaderContentProvider` for the existing backend route and
  `SourceEngineReaderContentProvider` for the local source-engine route.
- Added `SourceEngineSwitch` and formal UI controls to toggle between
  `书源引擎` and `后端`.
- Routed SearchActivity hot words, keyword suggestions, search results,
  BookDetailActivity detail/catalog loading, and ReadViewModel catalog/content
  loading through the provider boundary.
- Source-engine books and chapters use explicit route IDs, so already-created
  source-engine entries remain identifiable without guessing.
- Added content quality gates and a bounded concurrent source search so the
  formal search page does not block indefinitely on bad sources.

Verified evidence:

- Tests/build/install:

```powershell
./gradlew :source-engine:test :app:testDebugUnitTest -Dorg.gradle.jvmargs=-Xmx3072m
./gradlew :source-engine:test :app:assembleDebug :app:installDebug -Dorg.gradle.jvmargs=-Xmx3072m
```

- Runtime:

```text
source JSON size: 22,754,277 bytes
SourceEngineActivity: sources=6500, searchable=3223
Full chain verified: keyword=斗破苍穹, source=👍 悠久小说,
canonicalChapters=1595, duplicateChapters=49, contentQualityScore=92
```

- Switch-on logs:

```text
operation=hotWords provider=source-engine sourceEngineEnabled=true
operation=keyWords provider=source-engine sourceEngineEnabled=true
operation=search provider=source-engine sourceEngineEnabled=true
operation=searchCompleted provider=source-engine key=doupocangqiong count=30 durationMs=30451
operation=detail provider=source-engine sourceEngineEnabled=true
```

- Rollback logs:

```text
当前阅读链路：后端
operation=hotWords provider=backend sourceEngineEnabled=false
```

Known validation boundary:

- The device ADB text command cannot input Chinese into this Android 16 build,
  so the formal SearchActivity route was exercised with an adb-entered pinyin
  query to prove routing, timeout, result rendering, and detail dispatch.
- Real Chinese keyword coverage was verified through the formal source-engine
  full-chain entry using `斗破苍穹`.
