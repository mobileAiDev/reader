# Source Search Tier Progress

Date: 2026-06-05

## Current Status

Implemented, locally tested, installed, and smoke-verified on a real device.

## Completed

- Confirmed that the search list path and the per-book content tier are separate
  surfaces.
- Confirmed that the per-book content tier was previously targeting 5 verified
  sources.
- Decided to expand the per-book content tier target to 32 verified sources.
- Kept the first-display trusted-source threshold at 2.

## Implemented In This Change

- `BOOK_CONTENT_TIER_TARGET_SIZE` is changed from 5 to 32.
- The isolation contract test now asserts the 32-source content-tier target.
- Seed tier now wins over score-derived tier, so a demoted seed source cannot be
  raised into tier 1 only because its score is high.
- Global source waterfall order is strict by tier: tier 1, then tier 2, then
  tier 3. Bucket interleaving still applies inside a tier.
- Search source requests now start in tier waves instead of launching every
  selected source at once.
- Progress validation now emits a provisional merge first, then waits for more
  candidates in the current validation batch or a short grace before returning.
- Search validation no longer derives expected catalog length from
  `lastChapter` or kind metadata.
- Reading-candidate selection now uses actual catalog evidence only: if any
  same-book candidate has at least 100 chapters, readable long candidates can
  publish through normal two-source consensus; if every candidate is under 100
  chapters, publication requires four readable sources with a roughly matching
  catalog prefix. Chapter counts do not need to be identical.
- Completed progressive ranking now uses an 8s rank timeout instead of waiting
  for the older long progressive rank window, so completed-stage ranking no
  longer blocks for roughly 40s on large raw result sets.
- Progressive merge now keeps the latest rank order first and appends earlier
  visible extras, preventing an old 50-chapter candidate from staying above a
  later long-catalog same-book candidate.
- Exact-title grouping no longer suppresses related independent books when a
  non-exact group has its own two-source consensus. This covers the
  `灵源仙路` / `源仙路` replacement regression.
- Query fan-out is exact-only for source search. Title aliases and short-prefix
  rescue queries were removed, so one user query no longer doubles into a
  700-request run.
- Detail-to-read now passes a lightweight `CollBookBean` payload through
  `Intent`; the multi-thousand-chapter catalog is read from the source-engine
  session cache inside the same process.
- Uncollected search-read opens use `persistToShelf=false`, and `NetPageLoader`
  no longer creates a shelf entry as a side effect of reading.
- The full source-quality tier set was regenerated and embedded. Runtime
  selected 273 compatible sources from 284 embedded selections; the two
  `m.bbiquge*` built-in penalty sources are demoted into low tier and cannot
  win over normal long-catalog candidates in the verified path.
- Source-quality sample books are now all treated as ordinary content probes.
  The probe reports metrics only; final tier selection no longer accepts
  `METADATA_AVAILABLE` as readable evidence.
- Tier 1 now requires at least 8 readable samples and at least 50% readable
  success across probed samples. Low-success sources can remain in tier 2/3 for
  breadth, but they no longer lead the first waterfall.
- Source-quality probing records short-catalog samples without turning an
  otherwise readable sample into a failure. Final selection uses that evidence:
  tier 1 requires zero short-catalog samples, and sources with two or more
  short-catalog samples are forced to tier 3.

## Current Tier Evidence

Latest embedded selection after the quality-gate refresh:

- Probe files: `163`.
- Real `AVAILABLE` candidates: `316`.
- Embedded selections: `362`.
- Tier counts: `tier1=26`, `tier2=260`, `tier3=76`.
- Short-catalog tier rule: `shortCatalogSampleCount >= 2` is forced to tier 3;
  tier 1 requires `shortCatalogSampleCount=0`.
- Built-in `m.bbiquge.net` and `m.bbiquge8.net` remain tier 3 with score `500`.
- Real readable coverage in the selected set:
  `琼明神女录=27`, `逍遥小散仙=7`, `仙都=126`, `灵源仙路=1`,
  `叩问仙道=140`.

## Live Device Evidence

Latest `叩问仙道` regression smoke on OnePlus PKR110 / Android 36:

- Runtime source window: `selected=273`, tier window
  `tier1=54`, `tier2=205`, `tier3=14`.
- First provider publish: `3709ms`, `raw=49`, `output=1`,
  `requestsStarted=54`, `completed=35`.
- First two-result UI publish: around `12.4s`; final UI state:
  `count=2`, top author `雨打青石`, second independent same-title result kept.
- Full completed search: `raw=821`, `count=2`, `durationMs=33235`.
  Completed ranking itself was about `3.2s`; the remaining time is the full
  273-source waterfall and slow tail sources.
- Search latency summary showed fast valid responses in `251-480ms`, but slow
  no-result or weak sources still took `8.5-18.2s`.
- Detail render: `coverUsable=true`, `coverCandidates=2`, `chapters=2734`.
- Detail preview: session-cached catalog resolved in `523ms`.
- Reader open: session catalog cache hit `2734` chapters in `3ms`,
  reader session catalog loaded in `112ms`, first page parsed/opened in
  `38/53ms`.
- Exiting the uncollected reader showed the existing "加入书架" confirmation;
  after tapping cancel, the main bookshelf stayed empty and `叩问仙道` matched
  zero visible shelf nodes.

Open performance work:

- The first visible search result is now in the 3-4s range for the regression
  sample, but first two-result publish can still be around 12s because ranking
  waits for enough validation/search progress.
- Full completion still waits for all selected compatible sources and can take
  tens of seconds. This is acceptable only if it stays background-isolated and
  does not delay the next search or reader entry.
- Background content-tier/V8 work after reader open can still run for a long
  time. The reader first page is fast, but later tier persistence should remain
  preemptible.

## Verification

Passed:

```text
node tools\source-quality\select-final-sources.test.mjs
node tools\source-quality\select-final-sources.mjs --apply true
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ldp.reader.source.SourceEngineReaderContentProviderTest.searchUsesOnlyExactUserQuery" --tests "com.ldp.reader.source.SourceEngineReaderContentProviderTest.progressiveSearchDoesNotPublishTwoSourceShortCatalogConsensus" --tests "com.ldp.reader.source.SourceEngineReaderContentProviderTest.progressiveSearchPublishesTwoSourceConsensusWhenOneCatalogIsLong" --tests "com.ldp.reader.source.SourceEngineReaderContentProviderTest.progressiveSearchPublishesFourSourceRoughlySimilarShortCatalogConsensus" --tests "com.ldp.reader.source.SourceQualityLabRunnerTest.shortCatalogSampleIsReportedWithoutBlockingAvailability" --tests "com.ldp.reader.source.SourceQualityLabRunnerTest.freshnessHintDoesNotParseLastChapterOrKindMetadata"
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ldp.reader.source.SourceEngineReaderContentProviderTest" --tests "com.ldp.reader.source.SourceQualityLabRunnerTest" --tests "com.ldp.reader.ui.activity.SearchViewModelTest" --tests "com.ldp.reader.sourceengine.SourceEngineIsolationContractTest"
.\gradlew.bat --no-daemon :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest --tests "com.ldp.reader.sourceengine.SourceEngineIsolationContractTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.ldp.reader.source.SourceQualityRouterTest" --tests "com.ldp.reader.sourceengine.SourceEngineIsolationContractTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.ldp.reader.source.SourceEngineReaderContentProviderTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.ldp.reader.widget.page.PageLoaderLayoutTest" --tests "com.ldp.reader.source.SourceEngineReaderContentProviderTest.progressiveSearchPublishesTrustedShortCatalogBeforeSlowSourcesFinish" --tests "com.ldp.reader.source.SourceEngineReaderContentProviderTest.progressiveSearchKeepsEarlierVisibleBookWhenContainedTitleGroupPublishesLater" --tests "com.ldp.reader.source.SourceEngineReaderContentProviderTest.progressiveMergeUsesLatestRankOrderAndKeepsPreviousExtras" --tests "com.ldp.reader.source.SourceEngineReaderContentProviderTest.progressiveLongExactTitleWaitsForHigherConsensusAuthorGroup" --tests "com.ldp.reader.source.SourceEngineReaderContentProviderTest.progressiveSearchPublishesEarlyResultAndEventuallyPromotesReadableTailCandidate" --tests "com.ldp.reader.utils.BookCoverUrlTest"
.\gradlew.bat :app:assembleDebug
```
