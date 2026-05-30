# Reading V8 PS-BMT Progress

Date: 2026-05-30

## Current Status

V8 is implemented as the source-engine production catalog mark detector.

Implemented:

- V8 detector, semantic model, quality gate, diagnostics, planner, source
  similarity, mark models, and source runner under `content/v8`;
- Android BGE provider and packaged `bge-small-zh-v1.5-onnx` assets;
- V8-named app scheduler, cache, tracker, secondary probe policy, catalog mark
  registry, source-quality routing, and UI mark path;
- schema 35 persisted marks;
- content-and-quality-digest cache invalidation;
- stable NORMAL probe result caching, so unchanged clean books do not keep
  regenerating V8 marks;
- hard catalog-title parse gate before V8 target/context selection; rows that
  are not parseable chapter or section titles do not enter V8;
- dynamic initial target planning plus repeated backward expansion until a clean
  boundary is observed before the first bad tail chapter;
- foreground network priority gate with V8 background resumption;
- continuous low-priority shelf maintenance that sweeps every source-engine
  shelf book instead of stopping after a fixed small batch;
- constructed-sample, detector, cache, router, and provider regression tests.

Removed:

- obsolete detector packages from source and test code;
- obsolete detector design/progress documents;
- stale cache namespace used by the previous production mark path.

## Key Fixes In This Iteration

1. Expanded V8 validation now includes every tail chapter from the earliest bad
   backtrack to the end and repeats the backtrack until a clean boundary is
   observed. This fixes books where pollution starts far before the first sampled
   tail chapter, such as `苟在两界修仙` and `元始法则`.
2. Future rescue uses future chapter membership plus suffix-to-future
   acceptance. If the current candidate is an early fragment rupture and the
   future chapter also has early fragment risk, the future cannot rescue it;
   this catches consecutive polluted tails such as `仙人消失之后` 第2881章 ->
   第2882章.
3. V8 cache keys include fetched body content digest. Catalog-title equality
   alone cannot reuse old marks.
4. V8 app classes and events now use V8 names so production code does not imply
   another detector line is active.
5. Real BGE target validation now uses manual first-wrong boundaries instead of
   old labels, and parses both `章` and `节` titles. Sparse cache rows without
   nearby previous chapters fall back to `INSUFFICIENT_CONTEXT` instead of using
   distant chapters as fake reference context.
6. Low-priority maintenance no longer caps a pass to four books. It sweeps all
   source-engine shelf books, prepares only the dedicated readable tier needed
   to trigger V8, and then moves on instead of blocking on global low-priority
   tier filling. Books that are not ready, including timed-out books, retry
  after the short idle delay. A clean pass sleeps until the normal 15-minute
  trigger; unchanged catalogs/content hit the current schema + content-digest cache
   instead of recalculating V8 marks.
7. Clean-result caching also survives a thin `INCONCLUSIVE` probe when enough
   stable `NORMAL` marks remain after dropping the fragile thin chapter. This
   prevents a mostly-clean book from rerunning every maintenance cycle because
   one empty/too-short chapter was intentionally not trusted.
8. Source-engine readable-body cache is bumped to `source-engine-content-v10` so
   stale per-title chapter body cache files from older source choices cannot be mistaken for
   current schema verification evidence. The invalidation removes cached
   chapter bodies while preserving persisted source-tier metadata, and the V8
   maintenance loop runs that invalidation for every source-engine shelf book.
9. Catalog fusion drops a leading latest-update prefix before the full ascending
   catalog. This prevents sources shaped like `687..681, 1..687` from making V8
   validate the wrong tail and skip visible late chapters such as `青山`
   `685、举国搜拿`.
10. V8 now rejects unparseable catalog rows in the actual provider path before
    body fetch, target planning, content digesting, and PS-BMT. This keeps
    entries such as `鹤守抄，真可笑！` out of the first-wrong boundary and
    prevents a catalog comment from poisoning later story chapters.
11. Same-run target verdicts no longer update the reference memory for later
    targets. V8 requests now carry the planner-selected context indexes
    explicitly, so one target misjudgment cannot change the input evidence for
    the following dozens of target chapters.
12. V8 now stabilizes the bad-tail boundary after per-chapter detection. A
    visible WRONG must be part of a sustained tail boundary with a clean guard;
    isolated PS-BMT WRONG results are downgraded to INCONCLUSIVE instead
    of becoming the first visible wrong chapter.
13. V8 result output now separates raw `planningMarks` from stabilized visible
    `marks`. Dynamic expansion uses raw marks; registry/cache/UI and
    source-quality commit use stable marks. This keeps initial sparse probes
    aggressive enough to backtrack without letting one false positive display
    as a bad tail.
14. Dynamic expansion now follows a bad-tail cluster instead of the earliest
    isolated raw bad mark, including near-tail singletons, and uses a smaller
    first backtrack window. One sparse false positive can no longer pull dozens
    of later chapters into the same verification wave.
15. Catalog tail trimming now trims only a contiguous unreadable suffix. If a
    later tail chapter is readable, an earlier failed tail probe no longer
    removes or deletes that later chapter.
16. Low-priority maintenance waits for the scheduled V8 job to finish before
    reporting the book finished and moving to the next shelf book. This prevents
    background V8 jobs from piling up while the maintenance loop keeps starting
    new books.
17. The first behavior-preserving performance pass adds clean-text memory LRU,
    exact-input detector memory LRU, disk-backed BGE window embedding LRU, and
    V8 timing logs. The clean LRU is keyed by exact title plus raw body. The
    detector LRU is process-only and keyed by full previous/current/future
    chapter input. The BGE disk LRU is keyed by model namespace, model
    fingerprint, token limit, and exact window text. It caches only normalized
    vectors, not final WRONG/NORMAL verdicts. The disk LRU budget is 50,000
    entries or 512 MiB, and eviction is checked every 512 writes to avoid
    repeated directory scans during cold backfills.
18. The persisted mark-cache digest also includes provider quality signal fields
    (`qualityScore`, `coherenceScore`, `cleanedLength`, and warnings). If the
    same body text is later fetched with different quality warnings that can
    affect V8 quality gating, the cache misses and V8 reruns.

## Performance Log Fields

The AI Bridge/logcat performance surface now includes:

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

These fields are observational. They do not change planner target selection,
PS-BMT scoring, bad-tail stabilization, or source-quality routing.

## Known Real-Book Finding

`清光宝鉴` had intermittent missed marks after the first bad chapter. Manual
inspection of cached chapter bodies showed that several gap chapters were real
pollution cases with the same early prefix-then-foreign-tail shape.

Observed bad gap examples:

```text
第七十九章：朱棺鬼咒
第八十六章：广寒来仙子、莲中五色光！(1.0798w!)
第八十七章：太古道场、酒中道妙！(9.437k！)
第八十八章：酒仙人的酒、茶仙子的茶
第八十九章：剑窟狂剑、劫仙法力!(9.069k！)
第九十二章：道花华池开，乘月醉高台！
第九十四章：一界大能、往事越千年!
```

The expected V8 behavior after this iteration is that those chapters enter the
expanded V8 run and are individually judged, rather than being skipped by the
planner.

## Validation Commands

Detector and source-engine tests:

```powershell
.\gradlew.bat "-Dorg.gradle.java.home=C:\Users\ldp\.jdks\corretto-17.0.18" :source-engine:test --tests com.ldp.reader.sourceengine.content.v8.* --no-daemon
```

App V8 regression tests:

```powershell
.\gradlew.bat "-Dorg.gradle.java.home=C:\Users\ldp\.jdks\corretto-17.0.18" :app:testDebugUnitTest --tests com.ldp.reader.source.SourceEngineV8MarkCacheTest --tests com.ldp.reader.source.SourceEngineV8SecondaryProbePolicyTest --tests com.ldp.reader.source.SourceEngineV8ValidationTrackerTest --tests com.ldp.reader.source.SourceQualityRouterTest --no-daemon
```

Build:

```powershell
.\gradlew.bat "-Dorg.gradle.java.home=C:\Users\ldp\.jdks\corretto-17.0.18" :app:assembleDebug --no-daemon
```

Real BGE target-book validation:

```powershell
.\gradlew.bat "-Dorg.gradle.java.home=C:\Users\ldp\.jdks\corretto-17.0.18" -Dv8BgeTargetValidation=true :source-engine:test --tests com.ldp.reader.sourceengine.content.v8.V8PsbmtDetectorTest.reportsBgeTargetBooksValidationWhenEnabled --no-daemon
```

Latest result:

```text
records=112
normal=98
polluted=14
normalWrong=0
normalWrongOrSuspect=0
pollutedCaught=14
medianMs=1051
p90Ms=1596
maxMs=2297
```

Behavior-preserving performance pass validation:

```powershell
.\gradlew.bat "-Dorg.gradle.java.home=C:\Users\ldp\.jdks\corretto-17.0.18" :source-engine:test --tests com.ldp.reader.sourceengine.content.v8.V8SourceChapterValidatorTest --tests com.ldp.reader.sourceengine.content.v8.V8PsbmtDetectorTest --no-daemon
.\gradlew.bat "-Dorg.gradle.java.home=C:\Users\ldp\.jdks\corretto-17.0.18" :source-engine:test --tests com.ldp.reader.sourceengine.content.v8.* --no-daemon
.\gradlew.bat "-Dorg.gradle.java.home=C:\Users\ldp\.jdks\corretto-17.0.18" :app:testDebugUnitTest --tests com.ldp.reader.source.SourceEngineReaderContentProviderTest.v8ValidationDigestChangesWhenQualitySignalChanges --tests com.ldp.reader.source.SourceEngineV8MarkCacheTest --tests com.ldp.reader.source.SourceEngineV8SecondaryProbePolicyTest --tests com.ldp.reader.source.SourceEngineV8ValidationTrackerTest --tests com.ldp.reader.source.SourceQualityRouterTest --no-daemon
.\gradlew.bat "-Dorg.gradle.java.home=C:\Users\ldp\.jdks\corretto-17.0.18" :app:assembleDebug --no-daemon
git diff --check
```

Result:

```text
all passed
```

Actual device performance evidence after the optimization pass:

```text
BGE disk cache state:
  files=24512
  size=199252 KB

Outer V8 mark cache hits:
  苟在武道世界成圣: source_catalog_v8_cache_content_hit returned 96 marks in about 12 ms
  苟在两界修仙: source_catalog_v8_cache_content_hit returned 128 marks in about 9 ms
  仙都: source_catalog_v8_cache_content_hit returned 49 marks in about 4 ms
  清光宝鉴: source_catalog_v8_cache_content_hit returned 57 marks in about 6 ms

Warm BGE disk-cache run:
  灵源仙途: memHits=1491 diskHits=978 onnxRuns=0 diskWrites=0 diskEvictions=0 ms=30410

Post-budget cold/write run:
  仙都: memHits=1455 diskHits=0 onnxRuns=984 diskWrites=984 diskEvictions=0 durationMs=52997

Old 20,000-entry budget churn observed before the budget increase:
  苟在两界修仙: diskEvictions=1279
  苟在武道世界成圣: diskEvictions=896
```

The effect is behavior-preserving:

```text
Repeated unchanged shelf validations can return persisted V8 marks in single-digit
or low-double-digit milliseconds.

Warm BGE windows can be served from disk without ONNX reruns.

The larger BGE LRU budget removes the immediate eviction churn observed during
cold backfills, while still keeping a bounded 512 MiB disk budget.
```

Device validation uses AI Bridge after installing the debug APK. Required
runtime checks:

```text
open shelf books
trigger V8 validation
pull source_engine_v8_marks cache
verify schema 35 and content/quality digest
manually verify every first wrong chapter and +/- 1..2 neighbors
manually verify every unmarked hole from first wrong through the tail
for books with no wrong marks, manually verify the final several chapters
```

## Books To Verify On Device

Shelf verification must cover:

```text
青山
清光宝鉴
叩问仙道
仙都
元始法则
苟在武道世界成圣
仙人消失之后
灵源仙途：我养的灵兽太懂感恩了
苟在两界修仙
我在修仙界万古长青
```

For any book with a first wrong mark, manually inspect the first wrong chapter
and the one or two adjacent chapters before and after it.

## Device Hard-Acceptance Result

The current shelf was verified on device with AI Bridge plus phone-side chapter
fetches. No PC network fetch was used for the content judgments.

Books with WRONG marks:

```text
元始法则
  first WRONG: 第九百五十九章 分别和相聚（还三）
  checked neighbors: 第九百五十八章 normal, 第九百六十章 wrong
  user-mentioned chapter: 第一千零二十四章 辉烬灵种 is true pollution
  hole: index 1018 is absent from the catalog sequence; no story chapter leaked

苟在武道世界成圣
  first WRONG: 第664章 战场
  checked neighbors: 第662章 and 第663章 normal, 第665章 wrong
  tail checks: 第680章, 第681章, 第718章 are true pollution
  hole: 请假一天！ non-story notice

青山
  first WRONG: 685、举国搜拿
  checked neighbors: 683/684 normal or inconclusive-not-wrong, 686/687 wrong
  tail checks: 688/689 true pollution
  hole: 请假一天 non-story notice

苟在两界修仙
  first WRONG: 第393章 出手
  checked neighbors: 第391章 and 第392章 normal, 第394章 wrong
  user-mentioned chapter: 第414章 四法 is true pollution
  holes: none in the current first-wrong-to-tail range

清光宝鉴
  first WRONG: 第六十九章：膏黄仙人
  checked neighbors: 第六十七章 and 第六十八章 normal, 第七十章 and 第七十一章 wrong
  tail checks: 第九十五章 true pollution
  hole: 汇总 non-story row

仙人消失之后
  first WRONG: 第2884章 这叫作“切割”
  checked neighbors: 第2882章 and 第2883章 normal, 第2885章 wrong
  tail checks: 第2899章 true pollution
  holes: none

叩问仙道
  first WRONG: 第二千六百九十六章 再战天魔
  checked neighbors: 第二千六百九十四章 and 第二千六百九十五章 normal,
    第二千六百九十七章 and 第二千六百九十八章 wrong
  tail checks: 第二千七百零一章 true pollution
  holes: none
```

Books without WRONG marks:

```text
仙都
  final chapters checked. The tail is internally coherent with this book's
  unusual world and viewpoint shifts, so no WRONG mark is accepted.

灵源仙途：我养的灵兽太懂感恩了
  final chapters checked and coherent.

我在修仙界万古长青
  final chapters checked and coherent. Site noise appears, but no cross-book
  pollution was found.
```

Acceptance status:

```text
source and app tests pass
debug APK builds and installs
schema 35 mark cache exists with content/quality digest
first wrong chapters and +/- 1..2 neighbors checked
first-wrong-to-tail holes checked
no-WRONG books' final chapters checked
no correct adjacent chapter is currently marked WRONG
no known polluted adjacent/gap chapter remains NORMAL because it was skipped
```

## Current Acceptance Target

V8 is considered complete for this task only after:

```text
source and app tests pass
debug APK builds
AI Bridge device run writes current V8 marks for the shelf
all first wrong chapters are manually checked against actual content
all holes from first wrong through the tail are manually checked
books with no wrong marks have their final several chapters manually checked
no correct adjacent chapter is marked WRONG
no known polluted adjacent/gap chapter remains NORMAL because it was skipped
```
