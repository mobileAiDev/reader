# Source Quality Routing Design

Date: 2026-05-30

## Goal

The reader should not scan every source as the normal cold-start path. It starts
from a global source ranking, then adapts locally from real runtime evidence.

V8 catalog marks are one source of that evidence.

## Score Layers

```text
global source seed: app/src/main/assets/source-quality-seed-v1.tsv
local source delta: MMKV runtime evidence
local book-source delta: evidence for this exact book/source pair
```

Effective order:

```text
personal book tier first
then global tier 1
then global tier 2
then global tier 3
```

The global tier assignment is not rewritten by local learning. Local learning
only changes the membership and order of one book's personal tier.

Tier 1 is allowed to contain many homogeneous fast mainstream sources. The
selection rule limits excessive concentration from one host/site family, but it
does not try to deduplicate mainstream coverage down to one representative.

## V8 Mark Evidence

V8 keeps observed catalog rows and writes marks. Routing consumes the marks but
does not reinterpret them.

```text
NORMAL: verified usable story chapter
WRONG: wrong-book story or polluted suffix
NON_STORY: announcement, author note, leave note, postscript
BAD_EXTRACTION: page shell, blank, preview-only, broken extraction
INCONCLUSIVE: checked but not enough evidence to mark usable or wrong
```

Source scoring uses:

```text
latestObservedOrdinal
latestVerifiedGoodOrdinal
badTailStartOrdinal
wrongCurrentChapter
badExtractionRate
```

Short bad tails should be local book-source penalties, not heavy global source
demotions. A fast source can remain globally useful even when one book's latest
tail is polluted.

## Routing Rules

- Valid chapter gain raises the book-source score.
- A short polluted tail creates a bounded local penalty.
- A wrong current chapter creates a stronger book-source penalty.
- Low quality diagnostics can lower score, but displayable cleaned text should
  not keep the reader waterfalling forever.
- A challenger source with more V8-valid latest chapters can rise in the
  personal tier and may become the next anchor at an epoch boundary.

Routing may choose source order and network budgets. It must not change V8
cleaning, planning, detector thresholds, cache semantics, or mark states.

## Timing

Search is a fast path and should not synchronously run V8 over a full catalog.

Detail is a medium path and may fetch enough catalog/content evidence to show a
stable book page.

Reading, shelf entry, catalog opening, and explicit repair are strong intent
paths. These paths can run V8 catalog validation and update book-source scores.

## Request Priority

Foreground requests are always highest priority. V8 is background work and must
yield to:

```text
current chapter loading
search requests
detail requests
user-visible source waterfall requests
```

When the foreground counter returns to zero, queued V8 work resumes.

## Validation

Routing changes that consume V8 evidence must be checked with:

```text
unit tests for score updates
unit tests for request priority gates
device log evidence that V8 is background priority
device catalog evidence that V8 marks update source-quality records
```

## Source Quality Lab

The production reader and the source-quality lab are intentionally separate.

The lab accepts one Legado source JSON payload, imports it through the same
source-engine importer, and classifies every row without writing production
source data or user reading state:

```text
rejected import rows
disabled sources
engine-incompatible sources
network/search/detail/catalog/content failures
available sources with score and tier
```

Runtime entry:

```text
SourceQualityLabRunner
SourceEngineActivity -> 探测内嵌源 / 探测Lab JSON
debug SourceEngineActivity -> bridge/shell launch for isolated lab runs
```

Storage is isolated under app-private `source-engine-lab/`:

```text
source-engine-lab/book-sources.json
source-engine-lab/reports/source-quality-lab-latest.txt
source-engine-lab/reports/source-quality-lab-latest.tsv
```

This lab may use the same embedded global seed as a cold-start baseline, but it
uses in-memory score storage. It must not update MMKV source-quality deltas,
the production `source-engine/book-sources.json`, the bookshelf, or chapter
caches. Promoting a lab result into production source seeds remains a separate
explicit step.

## Lab Sample Shape

The default lab sample is intentionally bounded:

```text
smoke sample book pool: 10 configured titles for quick checks
selection sample book pool: 40 configured titles for final selection
network-probed sources: sourceOffset + maxSources batch of enabled and engine-compatible sources
books per source: first search result for each configured sample title
content samples per catalog: first, middle, and last chapter
```

The quick smoke title pool is:

```text
斗破苍穹
诡秘之主
大奉打更人
凡人修仙传
我在精神病院学斩神
十日终焉
我不是戏神
异兽迷城
剑来
雪中悍刀行
```

The final selection title pool has 40 books across classic, Qidian-like,
Fanqie-like, breadth, rare, published, and category buckets:

```text
斗破苍穹、凡人修仙传、剑来、雪中悍刀行、庆余年、全职高手、斗罗大陆、吞噬星空
诡秘之主、大奉打更人、宿命之环、灵境行者、赤心巡天、深海余烬、玄鉴仙族
我在精神病院学斩神、十日终焉、我不是戏神、异兽迷城、开局地摊卖大力
从红月开始、从姑获鸟开始、这个明星很想退休、我有一座恐怖屋、我的治愈系游戏、道诡异仙
剑烛大荒、凡戒窃灵、六朝清羽记、逍遙小散仙、琼明神女录、庄老邪修仙传
围城、活着、平凡的世界、白鹿原、三体、许三观卖血记
鬼吹灯、明朝那些事儿
```

Each source is still imported and classified even when it is outside the network
probe budget. Disabled rows, rejected rows, incompatible rules, and skipped rows
do not perform network access.

For large candidate pools the lab is run in batches. `sourceOffset` counts only
enabled and engine-compatible sources, so batch windows are stable even when the
JSON also contains disabled, rejected, or incompatible rows.

The lab can read a sample-title list from the app-private
`source-engine-lab/sample-keywords.txt` file. This avoids relying on shell
encoding for long Chinese title lists when Bridge launches batch runs.

For each probed source and each sample title, the lab checks:

```text
search -> book detail -> canonical catalog -> clean content samples
```

Search only counts when the returned book title exactly matches the sample
title after light normalization such as trimming whitespace and outer title
brackets. The lab scans the full returned list, not only the first result. If
any result is an exact title match, that matched result is used for the
detail/catalog/content probe even when nearby results are fan works or unrelated
titles with extra words. A non-empty result list with no exact title match is
recorded as `SEARCH_MISMATCH`, not as source availability. This is important for
sites that return arbitrary recommendations for every keyword.

The catalog pass records chapter count, duplicate catalog entries, and missing
ordinal ranges. The content pass accepts the best successful sample only when it
has at least 200 cleaned characters, quality score >= 70, and coherence score
>= 70.

If a source fails at the search transport/rule level, the runner stops probing
additional sample titles for that source. Empty search results are different:
they are recorded as scope information and the runner continues with the next
sample title because narrow or niche coverage can be useful.

A source row is `AVAILABLE` when at least one sample title passes the full
search/detail/catalog/content chain. The row records
`availableSampleCount/sampleCount`, `searchEmpty`, and `failedHits`, but low
sample coverage is not a quality penalty by itself. A rare source that can read
one hard-to-find title is valuable even if many mainstream titles return empty
search results. Scoring should penalize sources that claim or return a sample
book and then fail detail/catalog/content validation, not sources whose catalog
scope is simply narrow.

The lab also annotates rare readable samples. After all source rows finish, it
counts how many sources can fully read each sample title. If a source can read a
title that only one or two sources can read, its row is marked
`rareReadable=true` with `readableSourceCountForSample`. These rows are listed
in a dedicated `Rare readable` section so niche but useful sources are not
hidden behind many homogeneous mainstream sources.

Network execution for real source probes should run inside the Android app
process. Debug builds export the hidden `SourceEngineActivity` so bridge/shell
automation can open it without installing a separate test APK. Release builds
keep the activity non-exported and user-invisible. Host-side Gradle commands may
build or install the APK, but should not instantiate
`LegadoSourceQualityProbeEngine` for live network probing on the desktop.

## Runtime Preserve Export

The same hidden activity has a `runtime-preserve` mode for final source
selection. It reads the current bookshelf titles and the app's
`source_quality_score_v1` MMKV records, then writes a TSV under
`source-engine-lab/runtime-preserved-sources.tsv`.

The exported set contains sources that reached a book-specific personal tier,
sources with useful runtime score evidence for a bookshelf title, and the first
runtime waterfall batch for each bookshelf title. This is deliberately narrower
than preserving the entire previously embedded source JSON. Preserved sources
are deduped by `bookSourceUrl` and are forced back into the final generated JSON
when they still exist in the deduped candidate pool. This runtime evidence is
treated as real-use validation, so a preserved source can remain even when the
40-book probe sample does not include the bookshelf title that made it useful.
Sources whose name or URL explicitly says `已失效`, `失效`, or `禁用` are still
excluded from generated app assets.
