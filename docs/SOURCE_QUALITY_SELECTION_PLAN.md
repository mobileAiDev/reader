# Source Quality Selection Plan

Date: 2026-05-31

## Objective

Build a smaller embedded source JSON from the current app sources plus external
Legado source JSON packages. The final result should be:

```text
fast for mainstream Qidian/Fanqie-style searches
high quality for detail/catalog/content extraction
wide enough to keep sources that uniquely cover niche books
small enough for predictable app startup and search routing
```

Target size:

```text
evidence set, not a fixed quota
2026-05-31 generated app asset: 319 sources
272 sources have at least one exact-match sample fully readable in the 40-book probe
47 additional sources are preserved from real bookshelf/runtime evidence
```

The final embedded JSON is not a raw merge of every upstream package. Most
homogeneous duplicate sites should be dropped.

## Inputs

Source inputs:

```text
app/src/main/assets/source-engine/book-sources.json
C:/Users/ldp/Downloads/shuyuan.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1128.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1011.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1104.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1098.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1129.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1127.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1126.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1125.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1123.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1122.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1121.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1118.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1117.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1115.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1114.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1113.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1112.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1111.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1109.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1110.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1047.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1108.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1087.json
https://www.yckceo.com/yuedu/shuyuans/json/id/835.json
https://www.yckceo.com/yuedu/shuyuans/json/id/721.json
https://www.yckceo.com/yuedu/shuyuans/json/id/885.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1013.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1052.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1096.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1075.json
https://www.yckceo.com/yuedu/shuyuans/json/id/1034.json
```

Raw upstream downloads are temporary build artifacts:

```text
build/source-quality/raw/
```

Only the unified, base-URL-deduplicated candidate pool should be kept in git:

```text
tools/source-quality/candidates/source-candidates-deduped.json
```

This keeps the repository from accumulating many large upstream snapshots while
still preserving one repeatable input pool for device probing and final
selection.

## Phase 1: Candidate Merge

Command:

```text
node tools/source-quality/fetch-source-candidates.mjs
node tools/source-quality/merge-source-candidates.mjs
```

The merge step is desktop-safe because it only downloads/parses source JSON and
does not execute live source-site probes.

Deduplication key:

```text
normalized bookSourceUrl base URL
```

The normalizer strips `##`, `#`, query strings, fragments, and trailing slashes.
For valid URLs it compares `hostname + pathname`.

When duplicates share the same base URL, keep the candidate with the stronger
structural score:

```text
enabled
complete search/detail/catalog/content rule sets
less unsupported critical JS
lower historical respondTime
newer lastUpdateTime
embedded > local shuyuan > remote package on ties
```

Outputs:

```text
tools/source-quality/candidates/source-candidates-deduped.json
build/source-quality/source-candidates-report.tsv
```

Current generated candidate pool on 2026-05-31:

```text
input files: 34
source rows: 31431
unique normalized base URLs: 6552
duplicate normalized base URLs after merge: 24865
```

Acceptance checks:

```text
deduped JSON parses as an array
no duplicate normalized base URL remains
disabled/incompatible rows are retained in the candidate pool for traceability, not promoted blindly
names/URLs explicitly marked 已失效, 失效, or 禁用 are excluded from final app assets
```

Fetch notes:

```text
fetch-source-candidates.mjs only downloads source JSON packages
it does not execute search/detail/catalog/content probes
raw downloads stay under build/source-quality/raw/
```

## Phase 2: Probe Sample Design

Samples live in:

```text
tools/source-quality/source-quality-sample-books.json
```

There are two sample sets:

```text
smokeSample: 10 titles for quick device checks
selectionSample: wider title set for final source selection
```

Selection samples must cover:

```text
classic web novels
Qidian-like mainstream books
Fanqie-like mainstream books
rare/breadth books
traditional published works
other category representatives
```

Important rule:

```text
only exact title matches count as search hits
```

The probe scans the whole returned search list, not only the first result. If
any returned result exactly matches the sample title after normalization, that
source is treated as a search hit and the exact matched result is used for
detail/catalog/content probing. Extra non-exact results, such as fan works with
one or two extra words in the title, do not invalidate the hit.

If a site returns unrelated books and no exact title exists anywhere in the
result list, the probe records `SEARCH_MISMATCH`. It must not be treated as
available just because the search result list is non-empty.

Empty search is different from bad quality:

```text
SEARCH_EMPTY = this source may not cover that book
SEARCH_MISMATCH = this source returned unrelated results
```

Low sample coverage is not a direct penalty. A source that can search, catalog,
and read a hard-to-find book should survive as a breadth source even when many
mainstream sources cannot find that book.

## Phase 3: Device Probe

Live probing must run inside the Android app process. Desktop tools may prepare
JSON and trigger the hidden page, but must not instantiate the live source probe
engine against source sites.

Runtime entry:

```text
SourceEngineActivity hidden source lab page
SourceQualityLabRunner
debug manifest export for bridge/shell launch
```

Debug intent extras:

```text
sourceQualityAutoRun=true
sourceQualityMode=lab | asset | runtime-preserve
sourceQualitySourceOffset=<compatible-source offset>
sourceQualityMaxSources=<batch size>
sourceQualityMaxConcurrentSources=<source-level concurrency>
sourceQualitySampleKeywords=<comma/Chinese-comma/semicolon/newline separated titles>
sourceQualityMaxBooksPerSource=<default 1>
sourceQualityMaxContentSamples=<default 3>
```

For Chinese title lists, prefer an app-private file instead of a long shell
extra:

```text
/data/user/0/com.ldp.reader/files/source-engine-lab/sample-keywords.txt
```

The file may use comma, Chinese comma, semicolon, Chinese semicolon, `、`, or
newline separators. If it exists, the hidden lab page uses it when
`sourceQualitySampleKeywords` is empty.

Probe configuration:

```text
input JSON: tools/source-quality/candidates/source-candidates-deduped.json
sample titles: selectionSample
source batches: range-based batches until the whole candidate pool is covered
stopSourceAfterSearchFailure: true
requireExactSearchMatch: true
content samples per catalog: first, middle, last
```

Example batch launch after bridge/debug install and after pushing the deduped
JSON to the app-private lab file:

```text
adb shell am start -n com.ldp.reader/.ui.activity.SourceEngineActivity \
  --ez sourceQualityAutoRun true \
  --es sourceQualityMode lab \
  --ei sourceQualitySourceOffset 0 \
  --ei sourceQualityMaxSources 120 \
  --ei sourceQualityMaxConcurrentSources 36
```

Increase `sourceQualitySourceOffset` by the batch size until all compatible
candidate sources have been covered.

Batching is required because thousands of sources cannot be probed in one UI
session reliably. Each batch should write app-private reports and then pull them
to:

```text
build/source-quality/probe-runs/<run-id>/
```

Per source/sample capture:

```text
search status and latency
exact-match search count
detail status and latency
catalog status, chapter count, duplicate count, missing ordinal ranges
content status, cleaned length, quality score, coherence score
rareReadable flags after aggregation
```

The probe must not write production user data:

```text
no bookshelf writes
no production source-engine/book-sources.json writes
no MMKV source-quality delta writes
no chapter cache writes beyond normal temp reads required by the engine
```

Lab storage stays under:

```text
source-engine-lab/
```

## Phase 3.5: Runtime Preservation Export

Before final selection, export sources that the running app has already learned
from real bookshelf usage. This preservation set is not the old embedded source
JSON and is not the full old seed file.

The export reads:

```text
current bookshelf titles
source_quality_score_v1 MMKV book-source score records
current SourceQualityRouter waterfall order for each bookshelf title
```

It preserves:

```text
sources that qualify for a book's personal tier
sources with runtime score evidence for a bookshelf title
the first runtime waterfall batch for each bookshelf title
```

Sources are still deduped by normalized `bookSourceUrl`. If a preserved source
exists in the deduped candidate pool, final selection adds it unconditionally
before filling the remaining slots with probe-selected sources. The final tier
comes from the router's current tier for that source; a preserved niche source
does not automatically become tier 1.

Command after installing the current debug build through Bridge/ADB:

```text
node tools/source-quality/export-runtime-preserved-sources.mjs
```

Output:

```text
build/source-quality/runtime-preserved-sources.tsv
```

The hidden activity mode writes only under app-private `source-engine-lab/` and
does not modify bookshelf data, production source JSON, or chapter cache files.

## Phase 4: Evidence Aggregation

Merge every batch TSV into one evidence table:

```text
build/source-quality/probe-runs/<run-id>/source-quality-evidence.tsv
```

Aggregate by normalized base URL and source ID:

```text
successful exact-readable samples
failed-after-hit samples
empty-search samples
search-mismatch samples
median/p95 latency for successful stages
catalog duplicate and missing ordinal rates
content quality/coherence scores
rareReadableKeywords
readableSourceCountForSample
```

Scoring separates quality from coverage.

Quality penalties:

```text
search transport/rule failure
SEARCH_MISMATCH
detail failure after exact search hit
catalog failure or empty catalog after exact search hit
content fetch failure
low cleaned length
low content quality/coherence
duplicate or polluted catalog
slow median or bad p95 latency for successful samples
```

Not quality penalties:

```text
SEARCH_EMPTY
low total sample coverage by itself
specialized/niche catalog scope
```

Breadth boosts:

```text
rareReadableKeywords
titles not covered by selected tier 1 candidates
category buckets under-covered by faster homogeneous sites
```

## Phase 5: Tier Selection

Selection happens in three passes.

Tier 1:

```text
fast
stable
high extraction quality
good mainstream Qidian/Fanqie/classic coverage
allow multiple homogeneous fast mainstream sources
limit only excessive concentration from one normalized base URL or site family
```

Tier 2:

```text
quality acceptable
adds category or platform breadth
has rareReadableKeywords
covers useful books missed by tier 1
does not slow down the normal first search wave
```

Tier 3:

```text
fallback breadth
slower or less stable but still reads at least one useful exact-match sample
kept only when it adds coverage not already covered by tier 1/2
```

Manual exclusions:

```text
disabled sources
import-rejected sources
engine-incompatible sources
sources with only SEARCH_MISMATCH hits
sources that hit exact titles but repeatedly fail detail/catalog/content
```

Manual exceptions require a note in the selection report.

Runtime preservation:

```text
build/source-quality/runtime-preserved-sources.tsv is the preservation set
if a preserved source exists in the deduped candidate pool, it is added back unconditionally
preserved sources may enter even if this 40-book sample did not cover them
preserved sources that are engine-INCOMPATIBLE are kept but forced to tier 3 with low score
explicit 已失效/失效/禁用 labels are still excluded from final app assets
```

This protects sources that the app has already learned as useful for bookshelf
books from being removed only because the new probe sample does not exercise
their niche. The old embedded JSON is not used as a blanket preservation set.

## Phase 6: Generate App Assets

Generate the final embedded source JSON from selected evidence:

```text
app/src/main/assets/source-engine/book-sources.json
```

Generate/update the global routing seed:

```text
app/src/main/assets/source-quality-seed-v1.tsv
```

Sort order inside the embedded JSON:

```text
tier asc
quality score desc
speed score desc
rare breadth desc
source name asc
```

The source JSON itself should remain a valid Legado source array. Tier and score
metadata should live in the seed/report unless the importer already supports a
safe ignored metadata field.

Final generated evidence:

```text
build/source-quality/final/source-selection-report.tsv
build/source-quality/final/source-selection-summary.md
```

Current 2026-05-31 output:

```text
probeFiles: 20
availableCandidates: 272
selected: 359
preserved: 65
runtime-only preserved: 47
compatibility-demoted preserved sources: 11
tier3 source-expansion appended sources: 40
tier1: 24
tier2: 260
tier3: 75
```

Selection-sample coverage inside the final embedded JSON:

```text
38/40 titles have at least two exact-match readable sources
凡戒窃灵: 0 exact-match readable sources in this candidate pool
逍遙小散仙: 0 exact-match readable sources in this candidate pool
```

The zero-count titles are kept in the sample set as regression probes. They do
not lower source quality scores by themselves because `SEARCH_EMPTY` is a search
coverage result, not a quality failure. Covering them requires new source
material or an explicit decision to normalize alternate title spellings.

Rare-source expansion addendum:

```text
source packages: 1096, 1075, 1034, 835
candidate rows after removing existing embedded sources: 1502
unique base URLs: 1502
engine-compatible probe rows: 787
available sources: 40
逍遥小散仙: 7
琼明神女录: 8
六朝清羽记: 4
仙子的修行: 34
```

These 40 sources are appended as tier 3, low-score breadth rows. They are placed
after normal selected/preserved sources and before the 11 runtime-preserved but
currently engine-incompatible rows. The appended set is checked against the
existing embedded set by normalized `bookSourceUrl`; overlap with non-appended
sources is 0, duplicate URLs inside the appended set is 0, and duplicate URLs in
the final 359-source app asset is 0.

Generation command after all device probe TSVs are pulled under
`build/source-quality/probe-runs/` and runtime preservation is exported:

```text
node tools/source-quality/select-final-sources.mjs \
  --probeDir build/source-quality/probe-runs/full-selection-20260531 \
  --runtimePreserve build/source-quality/runtime-preserved-sources.tsv
```

Review these files first:

```text
build/source-quality/final/book-sources-selected.json
build/source-quality/final/source-quality-seed-v1.tsv
build/source-quality/final/source-selection-report.tsv
build/source-quality/final/source-selection-summary.md
```

Only after the report is accepted, replace app assets:

```text
node tools/source-quality/select-final-sources.mjs \
  --probeDir build/source-quality/probe-runs/full-selection-20260531 \
  --runtimePreserve build/source-quality/runtime-preserved-sources.tsv \
  --apply true
```

## Phase 7: Validation

Host checks:

```text
node tools/source-quality/merge-source-candidates.mjs
node tools/source-quality/export-runtime-preserved-sources.mjs
node tools/source-quality/select-final-sources.mjs
deduped JSON parse check
duplicate normalized base URL check
./gradlew.bat --offline :app:testDebugUnitTest --tests com.ldp.reader.source.SourceQualityLabRunnerTest
./gradlew.bat --offline :app:assembleDebug
git diff --check
```

Device checks:

```text
bridge install debug APK
launch main app hidden source lab page
run smokeSample against final embedded JSON
search several mainstream titles
search several rare/breadth titles
open detail, catalog, and first readable chapter for selected books
confirm no extra desktop icon from test APK
confirm lab output stays under source-engine-lab/
```

Regression acceptance:

```text
mainstream search is not slower than the current embedded JSON
tier 1 returns exact title results for common samples
tier 1 keeps enough homogeneous fast sources for mainstream stability
tier 2/3 preserve rareReadable coverage
no duplicated latest-chapter list pollution in catalogs that the engine can filter
no user bookshelf/source data is changed during probe runs
```

## Rollback

Keep the previous embedded JSON in git history. If device smoke fails after the
asset replacement, revert only:

```text
app/src/main/assets/source-engine/book-sources.json
app/src/main/assets/source-quality-seed-v1.tsv
```

The lab runner, sample list, merge tool, and selection docs remain useful and
should not be reverted unless they caused the regression.
