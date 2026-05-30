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
