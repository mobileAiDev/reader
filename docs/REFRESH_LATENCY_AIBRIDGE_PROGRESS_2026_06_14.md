# AI Bridge Refresh Latency Progress - 2026-06-14

## Status

This pass followed the no-code-change boundary. I used AI App Bridge against package `com.ldp.reader`, collected user-facing screenshots and debug-flow screenshots, reviewed the refresh code paths, and attempted targeted Gradle tests. Only documentation files were added in this pass.

Important clarification: `SourceEngineActivity` and `MediaProbeActivity` are debug/internal test surfaces. They are useful for stress and trace evidence, but they are not user-facing conclusions. User-facing conclusions below come from `MainActivity` bookshelf and `ReadActivity`.

## Device Evidence

| Area | Evidence | Result |
|---|---|---|
| Bookshelf baseline | `build/ai_app_bridge_artifacts/refresh_audit_home.png`, `refresh_audit_shelf_second.png`, and later screenshot `ai_app_bridge_screenshot-20260613-230346-226-11299-gyg0lc.png` | 10 novel samples were visible/collected from real bookshelf UI. Most showed updates from 6 days ago. |
| Novel user path | `ai_app_bridge_screenshot-20260613-230441-270-11635-0mc1ci.png` | Opening `灵源仙路` from the shelf reached real `ReadActivity`; bottom chapter remained `第1816章 界源，苦泉`. |
| Read refresh | `ai_app_bridge_screenshot-20260613-230515-498-12229-jbmlfz.png`, `ai_app_bridge_screenshot-20260613-230538-722-12540-wwmhy0.png` | After tapping top `刷新`, a 20s window did not show a newer chapter/catalog. Content also displayed raw `br` text, which is a separate content-cleaning issue. |
| Catalog drawer | `ai_app_bridge_screenshot-20260613-230557-467-12827-92x824.png` | Catalog drawer still ended at `第1816章 界源，苦泉`; no newer chapter appeared after the refresh attempt. |
| Source engine internal probe | `SourceEngineActivity` search-trigger with 10 samples | Debug-only run reached sample 2/10 and showed long-tail behavior; sample 1 emitted a first accepted candidate that was not an exact title match (`仙路灵源/古群`). A 120s wait did not finish the run. |
| Comic debug flow | `ai_app_bridge_screenshot-20260613-231001-440-14988-ooap65.png` | `MediaProbeActivity mode=flow kind=comic query=斗破苍穹 maxSources=8 maxChapters=3` completed: 1/1 pass, 34.874s, selected `我的漫神（优+）`, 1079 chapters, first/middle/tail/previous/next checks passed. |
| Audio debug flow | `ai_app_bridge_screenshot-20260613-231205-530-15466-x1u9fy.png` | `MediaProbeActivity mode=flow kind=audio query=三体 maxSources=8 maxChapters=3` completed: 1/1 pass, 13.376s, selected `懒人听书（优+++）`, 3 sampled episodes, first/middle/tail/previous/next checks passed. |

## Novel Sample Coverage

| # | Book | Coverage this pass |
|---|---|---|
| 1 | 灵源仙路 | Full user path: shelf, reader, refresh, catalog drawer. Internal source probe also started here. |
| 2 | 叩问仙道 | Shelf sample captured. Internal source probe reached this sample and became long-running. |
| 3 | 我在修仙界万古长青 | Shelf sample captured. |
| 4 | 苟在两界修仙 | Shelf sample captured. |
| 5 | 琼明神女录 | Shelf sample captured. |
| 6 | 清光宝鉴 | Shelf sample captured. |
| 7 | 元始法则 | Shelf sample captured from second shelf position. |
| 8 | 玄鉴仙族 | Shelf sample captured from second shelf position. |
| 9 | 仙都 | Shelf sample captured from second shelf position. |
| 10 | 方寸道主 | Shelf sample captured from second shelf position. |

## Findings

### F1 - Reading catalog promotion ignores same-size freshness

`ReadViewModel.promoteCatalogAfterTierAttempt()` only promotes a refreshed catalog when `refreshed.size > currentSize`:

- `app/src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt:516`
- `app/src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt:529`

If a source returns the same number of chapters with a newer tail title, corrected tail, different source quality, or cleaned chapter title, the UI does not promote it. This directly matches the complaint pattern: new data can exist, but the displayed catalog remains stale until some later path returns a strictly larger chapter count.

### F2 - Existing larger catalogs are retained over incoming catalogs

`shouldRetainExistingSourceEngineCatalog()` keeps the existing list whenever it is larger than the incoming list:

- `app/src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt:551`
- `app/src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt:557`
- `app/src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt:559`

That protects users from source regressions, but it can also block a fresher source that is shorter because of deduping, trimming, source selection, or a corrected tail boundary.

### F3 - User-facing refresh can remain on cached/current catalog for at least 20s

The `灵源仙路` user path stayed at `第1816章 界源，苦泉` after tapping `刷新` and waiting 20s. The catalog drawer also ended at `第1816章`. This is not just the internal test panel: it is the real reading screen.

### F4 - Background tier refresh can be delayed by priority gating and retry

The reading path starts background content-tier work, then retries with exponential backoff:

- `app/src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt:421`
- `app/src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt:464`
- `app/src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt:478`
- `app/src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt:504`
- `app/src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt:505`

The provider also makes background requests wait behind higher-priority network work:

- `app/src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt:148`
- `app/src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt:151`
- `app/src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt:234`
- `app/src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt:240`

This explains why a refresh can feel like a 2-3 minute wait under source-engine load.

### F5 - Fast catalog path can display an anchor catalog before verification

`SourceEngineReaderContentProvider.getBookFolder()` first tries a fast anchor catalog, then only falls back to verified catalog when needed:

- `app/src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt:5018`
- `app/src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt:5033`
- `app/src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt:5038`
- `app/src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt:5059`
- `app/src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt:5120`

This is good for perceived startup, but it means the first visible catalog can be stale or from a weaker anchor. The later verified/tier path must be able to promote freshness, and F1/F2 can prevent that.

### F6 - Shelf/detail can save before async catalog refresh is done

Adding to shelf saves the book first, then asynchronously fetches and persists the catalog:

- `app/src/main/java/com/ldp/reader/ui/activity/BookDetailViewModel.kt:61`
- `app/src/main/java/com/ldp/reader/ui/activity/BookDetailViewModel.kt:78`
- `app/src/main/java/com/ldp/reader/ui/activity/BookDetailViewModel.kt:85`
- `app/src/main/java/com/ldp/reader/ui/activity/BookDetailViewModel.kt:88`

Detail refresh can update an existing shelf item after detail data arrives:

- `app/src/main/java/com/ldp/reader/ui/activity/BookDetailActivity.kt:204`
- `app/src/main/java/com/ldp/reader/ui/activity/BookDetailActivity.kt:213`
- `app/src/main/java/com/ldp/reader/ui/activity/BookDetailActivity.kt:248`
- `app/src/main/java/com/ldp/reader/ui/activity/BookDetailActivity.kt:254`
- `app/src/main/java/com/ldp/reader/ui/activity/BookDetailActivity.kt:260`

This creates a window where the shelf can show old latest chapter text until the async catalog path completes.

### F7 - Comic/audio catalog caching can also go stale

`MediaSourceRepository` has an in-memory route chapter cache and returns registered route chapters immediately when more than one is registered:

- `app/src/main/java/com/ldp/reader/media/MediaSourceRepository.kt:36`
- `app/src/main/java/com/ldp/reader/media/MediaSourceRepository.kt:743`
- `app/src/main/java/com/ldp/reader/media/MediaSourceRepository.kt:749`
- `app/src/main/java/com/ldp/reader/media/MediaSourceRepository.kt:750`
- `app/src/main/java/com/ldp/reader/media/MediaSourceRepository.kt:771`
- `app/src/main/java/com/ldp/reader/media/MediaSourceRepository.kt:772`

There is no freshness check in this path. If comic/audio route chapters were registered earlier, catalog pages may reuse them instead of forcing a network refresh.

## Build/Test Attempts

| Command | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest --tests "com.ldp.reader.model.local.BookRepositoryStorageContractTest" --tests "com.ldp.reader.widget.page.PageLoaderLayoutTest" --offline --no-daemon --stacktrace` | Blocked before test execution. Offline cache is missing Android Gradle plugin, Kotlin Gradle plugin, MobSDK, and many transitive jars. |
| `./gradlew :app:testDebugUnitTest --tests "com.ldp.reader.model.local.BookRepositoryStorageContractTest" --tests "com.ldp.reader.widget.page.PageLoaderLayoutTest" --no-daemon --stacktrace` | Blocked before test execution. Maven mirror `https://maven.aliyun.com/repository/gradle-plugin/.../kotlin-gradle-plugin-api-2.2.21.pom` returned HTTP 502. |

These are dependency resolution failures, not app test failures.

## Current Conclusion

The slow refresh report is credible. The strongest code-level cause is that catalog freshness is judged mostly by chapter count, while the user cares about the latest visible chapter. The real user path confirmed that `灵源仙路` stayed on `第1816章` after refresh and after opening the catalog drawer. Background tier/V8 work and source priority gating can explain the 2-3 minute delay. Comics and audiobooks passed debug flow samples, but their repository has a similar stale-cache risk because registered/route cached chapters can bypass a fresh chapter fetch.

## Recommended Next Fixes

- Compare catalog freshness by tail identity/title/URL/update signal, not only list size.
- When retaining an existing larger catalog, still allow promotion if the incoming tail is newer or has stronger source confidence.
- Emit a trace whenever a refreshed catalog is not promoted, including current tail and incoming tail.
- Add a visible or logged catalog refresh state for user-triggered `刷新`.
- Add TTL or explicit refresh semantics to `MediaSourceRepository.chapters(routeId)` for comic/audio.
- Add an E2E smoke covering shelf latest -> reader refresh -> catalog drawer latest for at least one source-engine novel.
