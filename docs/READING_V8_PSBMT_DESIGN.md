# Reading V8 PS-BMT Design

Date: 2026-05-30

## Decision

V8 is the production chapter-integrity detector for source-engine reading
catalog marks.

It answers one question:

```text
Does the current chapter start as this book, then enter a sustained foreign
fragment tail without trusted future acceptance?
```

The implementation lives under:

```text
source-engine/src/main/kotlin/com/ldp/reader/sourceengine/content/v8/
```

App integration uses V8-named scheduler, cache, provider, routing, and UI mark
classes. Old detector packages are removed from source and test code.

## Runtime Shape

```text
open reader/catalog
  -> build dynamic V8 validation plan
  -> fetch target bodies plus nearby context at background priority
  -> run V8SourceChapterValidator
  -> persist V8 marks behind schema 35 and content digest
  -> update catalog mark registry and source-quality routing
```

Foreground chapter/search/detail network work keeps priority. V8 background work
uses the network gate and resumes when foreground requests drain.

## Dynamic Plan

The first pass is intentionally small:

```text
tail targets: last 16 markable chapters
tail anchors: 24, 32, 48, and 64 chapters from the end
context: immutable story anchors selected before target validation
```

If sampled targets form a bad-tail cluster, V8 dynamically expands backward from
that cluster and validates every chapter from the backtrack point through the
catalog tail. A single isolated bad target, including one near the tail, does
not drive expansion.
Expansion repeats until V8 observes a clean chapter directly before the bad-tail
cluster, or reaches the beginning of the catalog. This expansion is verification
behavior, not a blind tail-propagation rule. Every marked chapter must still be
individually validated by V8.

`V8SourceChapterValidator` does not propagate a bad-tail state. Same-run target
chapters never become reference memory for later targets, regardless of whether
they are judged NORMAL, WRONG, or INCONCLUSIVE. The request carries the
planner-selected context chapter indexes explicitly, and that immutable context
anchor set is the only reference memory used by every target in the epoch.
After individual chapter detection, V8 accepts a visible WRONG tail only when it
finds a clean guard before the candidate boundary and sustained bad density
after it. Isolated PS-BMT WRONG results before that credible tail boundary are
downgraded to INCONCLUSIVE, so a single false positive cannot become the visible
first wrong chapter or source-quality bad-tail boundary.

The validator result keeps raw `planningMarks` separate from stabilized
visible `marks`. Backward expansion uses raw marks so an initial sparse probe
can still expand around tail risk; registry, cache, UI, and source-quality
commit only receive stabilized visible marks.

Catalog tail trimming follows the same non-propagation rule. It only trims a
contiguous unreadable suffix; if a later tail chapter is readable, an earlier
failed probe does not remove that readable chapter.

## Cache Contract

V8 cache hits require:

```text
schema version matches
source identity matches
book identity matches
catalog shape matches
target chapter indexes match exactly
content digest matches exactly
```

The content digest is an MD5 over the fetched V8 input bodies and their
provider quality signals:

```text
target indexes
input chapter index
input title
input content length
input content
input quality score
input coherence score
input cleaned length
input warnings
```

Catalog title identity alone is not enough. If a source silently updates body
text, or if the same body arrives with different quality warnings that can
affect V8 quality gating, the digest changes and V8 reruns.

Runtime-only detector LRU is allowed only for exact duplicate V8 inputs in the
same process. Its key is derived from previous/current/future chapter index,
title, trusted flag, content length, and content text. It is not a persistent
disk cache. Restarting the app, changing chapter content, changing nearby
context, or changing future text forces a fresh detector run.

Clean-text LRU is also process-only. Its key is exact chapter title plus exact
raw text, so source body changes naturally bypass it.

BGE embedding can use a disk-backed LRU because it is a leaf artifact, not a
final verdict. The disk key includes the BGE cache namespace, model file
fingerprint, `maxTokens`, exact window length, and exact window text. A cache
hit returns the same normalized vector that ONNX inference would have produced;
V8 still runs the same membership, scoring, and stabilization logic afterward.
This lets the app use disk space to keep the memory embedding LRU small without
turning detector decisions into stale persistent state.

The default BGE disk LRU budget is 50,000 entries or 512 MiB, whichever is hit
first. Eviction is checked every 512 writes so cold backfills do not repeatedly
scan the cache directory while the cache is still within budget.

## Clean Layer

The clean layer only prepares text:

- remove HTML tags and simple entities;
- remove URL/navigation/app/bookmark/vote lines;
- collapse repeated adjacent lines;
- remove a duplicated chapter title at the beginning.

It does not mark a story chapter wrong. Too little usable body returns
`SOURCE_QUALITY_PROBLEM`; insufficient trusted previous context returns
`INSUFFICIENT_CONTEXT`.

Before this layer, catalog rows must pass a chapter-title parse gate. Rows that
cannot be parsed as chapter, section, or numeric catalog titles are excluded
from both V8 targets and book-memory context; notices, comments, summaries, and
stray catalog prose cannot become the first WRONG boundary.

## Semantic Membership

V8 supports two semantic implementations behind the same interface:

- `V8BgeSemanticModel`: Android production path backed by packaged BGE ONNX
  assets.
- `V8SparseSemanticModel`: JVM test path using sparse character n-gram vectors.

Reference text comes only from planner-selected trusted previous context
anchors. Future chapters are never added to the book reference because the
future may already be polluted. Target chapters validated in the current epoch
are also never added to the reference memory for later target chapters; this
keeps one misjudgment from changing the evidence used by the rest of the tail.

Reference defaults:

```text
window size: 192 chars
window stride: 192 chars
max previous chapters: 3
max chars per previous chapter: 2600
```

## Identity Sketch

Identity sketch is a weighted local Chinese n-gram fingerprint built from
trusted previous chapters. It is not a dictionary and it does not classify
people, places, factions, realms, or items.

The sketch is auxiliary evidence. It can support semantic membership, but it
cannot convict a chapter by itself.

## Candidate Scan

Candidates are scanned near the observed prefix-tail break region, plus a small
sparse extension:

```text
dense: 64..260 step 8
sparse: 284..min(800, current.length * 0.55) step 24
local rupture cue max offset: 320
```

Position is a candidate prior only. A chapter is marked wrong only when the text
evidence is strong.

## Main Evidence

For each candidate offset V8 computes:

```text
prefixSupport
suffixSupportMedian
suffixLowRatio
belongDrop
localRupture
suffixRepeatRatio
futureTrust
futureSupport
futureFragmentRisk
tailRisk
localRuptureCue
```

`localRuptureCue` is an auxiliary candidate signal for the observed source
pollution pattern. It requires an early offset, enough suffix text, low suffix
internal repeated n-gram continuity, and a strong local rupture.

Future rescue is based on future chapter membership and suffix-to-future
acceptance. If the current candidate is an early fragment rupture and the
future chapter also has early fragment risk, the future is not allowed to rescue
the current suffix. This prevents consecutive polluted tails from washing each
other back to `NORMAL`.

## Decision Contract

`WRONG_CONFIRMED` requires all of:

```text
prefix belongs to this book
suffix membership is absolutely and persistently low, or the chapter has a
  bounded early fragment rupture matching the observed source-pollution shape
no trusted future rescue
no tail-cluster explanation
not suffix-safe
```

`SUSPECT_RECHECK_REQUIRED` is used for:

```text
tail cluster risk
medium early prefix-suffix evidence
fragment evidence below confirmed confidence
whole-chapter low-support cases that need later source comparison
```

`NORMAL` requires the absence of a strong candidate, or a trusted future that
accepts the suffix as continuing this book.

## Pseudocode

```kotlin
fun detect(input: V8Input): V8Decision {
    val current = clean(input.current)
    val previous = cleanTrustedPrevious(input.previous)
    val future = cleanFuture(input.future)

    if (!hasEnoughCurrent(current)) return sourceQualityProblem()
    if (!hasEnoughPrevious(previous)) return insufficientContext()

    val semanticSpace = semanticModel.build(previous, current, future)
    val identity = buildIdentitySketch(previous)
    val calibration = calibrate(previous, semanticSpace, identity)
    val futureEvidence = evaluateFuture(future, semanticSpace, identity, calibration)

    val candidates = scanOffsets(current).map { offset ->
        scoreCandidate(
            offset = offset,
            current = current,
            semanticSpace = semanticSpace,
            identity = identity,
            calibration = calibration,
            future = futureEvidence
        )
    }

    return decide(candidates, futureEvidence)
}
```

## Validation Gate

V8 is accepted only when all of these pass:

```text
constructed pollution fixtures: high recall and cut-point tolerance
normal chapter fixtures: no WRONG and no broad SUSPECT spread
real target books: every first wrong chapter and +/- 1..2 neighbors manually checked
real target books: every unmarked hole from first wrong through tail manually checked
clean target books: the final several chapters manually checked
device run: V8 cache files created with current schema and digest
performance: background work does not block foreground reading
```

## Performance Observability

V8 performance work must preserve detector decisions. Optimization is limited to
reusing exact duplicate work and adding measurement. The production logs include:

```text
v8.perf qualityMs detectorMs cacheHits stabilizeMs memHits diskHits onnxRuns
  diskWrites diskEvictions ms
v8.mark.finish qualityMs detectorMs detectorCacheHits stabilizeMs
  semanticMemoryHits semanticDiskHits semanticOnnxRuns semanticDiskWrites
  semanticDiskEvictions ms
source_catalog_v8_validate_input_ready chars heapUsedMb heapTotalMb heapMaxMb
source_catalog_v8_validate_finished durationMs marks planningMarks state counts heap
source_catalog_v8_cache_content_hit digest
```

These fields are for AI Bridge and logcat inspection only; they do not change
target selection, evidence scoring, stabilization, or source-quality routing.
