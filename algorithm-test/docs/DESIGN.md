# Novel Algorithm Test Design

## Goal

Build an isolated Android experiment module for validating novel identity
fingerprints, catalog selection/fusion, and polluted chapter detection without
changing production reader code.

The module is allowed to move fast. Production reader logic is not.

## Scope

This module owns:

- Manual chapter paste experiments.
- Source-engine fetch experiments using either pasted source JSON or the host
  app bundled `source-engine/book-sources.json` asset.
- Catalog candidate comparison and fusion reports.
- Fingerprint construction experiments.
- Chunk-level belonging scores.
- Chapter-level pollution suggestions.
- Trace logs for every critical decision.

This module must not own:

- Production search ranking.
- Production reader catalog display.
- Production chapter loading.
- Production auto-delete behavior.

## Host App Integration

`algorithm-test` is an Android library included by the debug `app` build. It
adds only a standalone `MainActivity` for bridge-launched experiments. It must
not change production search, detail, reading, catalog, or chapter functions.

The primary runtime path is the same source-engine contract used by the reader:

```text
source-engine sources
  -> quality-seed ordered source waterfall
  -> exact title/author search
  -> book detail
  -> catalog
  -> sampled earlier chapters for fingerprint
  -> real tail chapters for pollution detection
  -> algorithm report
```

Manual pasted chapters are kept only for quick inspection. They are not enough
to prove algorithm quality.

Phone-side source validation must use the installed Android app and phone
network. The desktop may build, install, pull reports, and read logs, but its
network result is not accepted as source reachability evidence.

## Quality Gate and Same-Source Memory Backfill

正文质量门禁只负责把章节文本分成：

- clean story text,
- clean story with safe trim,
- non-story chapters such as notices or postscript,
- bad extraction such as page chrome, JavaScript shell, preview-only text, or
  paywall fragments,
- uncertain short/mixed text.

This layer must not make source-selection decisions. If sampled chapters from a
source are mostly `BAD_EXTRACTION`, the first response is to skip those chapters
and keep sampling earlier chapters from the same catalog until Book Memory has
enough clean story context. Source switching is a higher-level fallback only
after the same source cannot provide enough usable memory chapters.

The analyzer itself cannot fetch more chapters, so its
`need-more-clean-story-context` guard means "caller must provide more clean
context", not "this book/source is impossible". Source and raw-corpus sampling
must therefore pass explicit seed chapter indexes into the analyzer and keep
backfilling those seed chapters before pollution judgment runs.

Backfill is budgeted but not fixed to the first few failed probes. The current
experiment budget is 256 additional same-source memory probes. The sampler logs
every probe so a failure can be distinguished clearly:

- no full fetched data available locally,
- fetched data exists but the selected region is mostly preview/page shell,
- quality gate misclassified non-story or story text,
- enough clean memory was built but the pollution detector still made a wrong
  decision.

## MVP Pipeline

```text
Real novel input
  -> clean text
  -> fetch fingerprint samples from earlier chapters
  -> fetch tail chapters where real pollution is common
  -> split fetched chapters into overlapping chunks
  -> extract cross-chapter features
  -> build weighted identity fingerprint
  -> iteratively remove low-belong chunks
  -> score every chunk
  -> detect local abnormal runs and suffix pollution
  -> show report and logs
```

Real validation must use real novels and real source pollution. Synthetic data is
not accepted as algorithm-quality evidence.

## First Fingerprint Strategy

The first strategy intentionally stays rule-based:

- Cross-chapter common terms are mandatory for fingerprint inclusion.
- Single-chapter terms can affect a chunk score only as weak alien evidence.
- Terms are classified before becoming fingerprint features.
- Generic web-novel terms are filtered or heavily down-weighted.
- Person, organization, location, skill, item, realm, resource, world-term,
  phrase, and relation-edge features are scored separately.
- Judgment is focused on the back two thirds of a chapter. The first third is
  useful context, but it must not by itself trigger suffix deletion.

The current first implementation is an open-set belonging detector:

```text
normal_score =
  known entity / phrase hits
+ known relation-edge hits

alien_score =
  repeated unknown typed entities
+ unknown entity graph density

belong_score =
  normal_score / (normal_score + alien_score + smooth)
```

This means the analyzer does not ask "which genre is this chunk?". It asks:

```text
Does this chunk still belong to this specific novel identity?
```

Single mentions are intentionally weak. A term must appear across chapters to
enter the book fingerprint, and unknown character-like terms must repeat before
they become alien evidence. This avoids false names such as short fragments that
only occur once.

## Current Real-Failure Finding

Phone-side validation on `我在修仙界万古长青 / 快餐店` found a real polluted
tail chapter:

```text
第521章 诚不我欺，翻手为云
```

Manual text inspection showed the chapter begins with the correct novel
identity (`陆长安`, `覆海真君`, `大劫`), then around the first few hundred
characters abruptly switches into unrelated texts containing names and settings
such as `沈幼清`, `庄公子`, `安德莉亚`, `洛基`, `夏洛`, `韩允钧`, `杜子辕`.

The original analyzer missed this for two general reasons:

- Short chapters were chunked too coarsely. A 1176-character chapter produced
  only two chunks, so the first chunk mixed a small normal prefix with a large
  polluted suffix.
- Character extraction was too permissive. Enumerated knowledge such as common
  surname lists can be used as one recall signal, similar to enumerable country,
  sect/organization, item, weapon, vehicle, currency, and realm morphology. It
  is not the algorithm itself. Person confirmation must come from evidence in
  the novel: cross-chapter stability, boundary/context behavior, and
  relation-graph connection. Otherwise a two-character candidate must stay out
  of the `CHARACTER` fingerprint.

This is an algorithmic issue, not a book-specific issue. It must not be solved
by adding individual words such as adjectives or one-off names to stop-word
lists. The repair direction is:

- adaptive smaller chunks for short chapters and suffix-heavy tail validation,
- character candidates confirmed by distribution, boundary/context, and graph
  evidence; enumerable dictionaries and regexes are only generic recall/type
  aids,
- change-point decisions that can use an absolute core-identity drop, not only a
  normalized belong score.

## Generic Enumeration Boundary

The experiment module may use finite or highly regular helper rules when they
are domain-general:

- common surname characters for person-candidate recall,
- country/dynasty/sect/organization suffixes,
- location suffixes,
- weapon, magic item, vehicle, resource, currency, and realm suffixes or
  regex-like morphology.

These helpers are not allowed to be book-specific rescue rules. They only decide
whether a term is worth testing as a typed candidate. A candidate still needs
statistical and structural evidence before it can become a fingerprint feature.

Current person-candidate rule:

- three-character names are still accepted after fragment checks,
- two-character names from surname recall need repeated context-bearing
  occurrences across chapters,
- being split out as a standalone token by spaces or punctuation is not enough.

This keeps generic false positives such as adjective-like two-character terms
out of the person fingerprint without adding those adjectives to a stop-word
list.

Surname enumeration is only a recall boundary. It is allowed to be expanded
because common surnames are finite enough to enumerate, but seeing a surname
character such as `白` or `张` does not by itself create a person feature or a
delete signal. A recalled name candidate must still pass structural evidence:

- boundary and fragment checks,
- repeated evidence for short names,
- or participation in an alien entity cluster with multiple independent
  person/organization/location/world entities.

This prevents arbitrary n-gram fragments from becoming cleanup evidence while
still allowing polluted mixed snippets to be detected when many one-off foreign
names co-occur.

Typed generic terms are removed by the same generic type morphology used for
candidate recall, not by a growing per-book patch list. For example, a term that
is exactly the type signal `秘境`, `筑基`, or `金丹` has no book-specific modifier,
so it can help recognize "this looks like a location/realm token" but it cannot
become identity evidence by itself. A term such as `倥海金地` or a book-specific
artifact name must still pass the ordinary cross-chapter, boundary, and
structure checks before it can become a fingerprint feature.

This distinction is mandatory:

- finite or highly regular morphology can be used to recall and type a
  candidate,
- the bare type label itself is not identity evidence,
- open lexical items must be accepted or rejected by statistics, boundaries,
  continuity, and graph/relationship evidence,
- if a case requires adding arbitrary adjectives or one-off words, the algorithm
  is wrong and must be redesigned instead of patched.

## Entity Promotion Rule

All dictionary/regex-derived terms must pass a promotion gate before they can
be used as identity evidence. The code must keep these two states separate:

```text
candidate = recalled by surname / suffix / regex / n-gram
entity    = candidate promoted by structure and allowed to affect judgment
```

Required rule:

- surname recall only creates a person candidate;
- type suffixes and regular morphology only create typed candidates;
- a two-character person candidate must repeat before promotion;
- a three-character person candidate must either repeat or appear inside a
  supported entity cluster;
- a typed candidate must be repeated, long and specific, or contain an explicit
  type signal with a real modifier;
- a cluster is supported only when there are repeated entities, multiple strong
  typed entities, or several independent high-quality person candidates
  co-occurring;
- no cleanup decision may be based on a raw recalled candidate.

This rule is deliberately generic. It is allowed to expand finite recall lists
such as common surnames or regular suffix classes, but the expansion only
improves recall. It must not weaken promotion. Random fragments like
`青年哈` or `白瞎伱` are allowed to be observed as raw substrings, but they
must not become alien evidence unless the surrounding segment also supplies
repeat/type/co-occurrence support.

## Continuity Gate

Late-arc chapters can introduce new names, places, and concepts that are not in
the earlier fingerprint. Those should not be deleted merely because the local
chunk is unfamiliar. The current experiment therefore adds a local-arc support
gate before emitting a cleanup suggestion:

- the chapter prefix must still have strong known-book evidence,
- the suspicious suffix/run must repeat several topic terms inside the same
  chapter segment,
- at least one nearby known-book chapter must share several of those repeated
  terms,
- otherwise the segment remains suspicious and can be reported.

This is deliberately stricter than "a neighboring chapter shares one term". A
polluted chapter often contains multiple unrelated snippets, so its own internal
continuity is poor. Such a chapter must not be rescued just because one generic
word or one same-genre concept appears nearby.

## Twenty-Book Evaluation Result

The 20-book real batch proved that threshold tuning is not sufficient for the
current implementation. Some manually inspected normal chapters receive the
same structural scores as real polluted chapters:

```text
normal chapter: membershipLow ~= 1.00, alienCluster ~= 0.95
polluted chapter: membershipLow ~= 1.00, alienCluster ~= 0.90
```

Raising the cleanup threshold would hide the normal-chapter false positives but
would also miss obvious mixed-source pollution. Lowering the threshold would do
the opposite. That means the current score space is not linearly separable.

The V2 direction is still valid, but these implementation pieces must be
replaced before further tuning:

- entity extraction must not be driven by arbitrary n-gram slices;
- identity features must be promoted from stable entities, not from surface
  fragments that merely look like names or typed terms;
- non-surname but stable character names must be supported by cross-chapter
  repetition and co-occurrence, otherwise books with names such as `邓肯`,
  `雪莉`, or `阿狗` lose their actual core cast;
- opening/prologue chapters cannot be judged as foreign only because the later
  book fingerprint has not absorbed their one-off cast;
- sparse sampled chapters cannot rely on near-future integration unless the
  neighboring chapter window was actually fetched.

Therefore the next implementation step is not another threshold pass. It is an
entity/fingerprint rebuild:

```text
raw candidate -> promoted entity -> graph/prototype feature -> cleanup score
```

Only promoted entities are allowed to affect alien-cluster and graph-absorption
decisions.

The 20-book replay confirmed that this was a real design boundary. After the
promotion rebuild, the same red/green suite passes. The decisive changes were
structural, not numeric:

- stable unlisted two/three-character n-grams are no longer promoted directly
  to `CHARACTER`; otherwise ordinary words such as `如今`, `事情`, or `发现`
  become false identity evidence;
- surname and compound-surname rules are recall aids only. They can surface
  candidates such as `欧阳木`, `令狐笑`, or `上官玄见`, but they do not remove
  the need for promotion checks;
- segment-level alien entities need explicit type morphology or repeated
  evidence. Length alone is not proof, because phrases like `洞察识海` or
  `包裹孤岛` can be normal world-building fragments;
- repeated alien entities are not automatically pollution. If a suffix is a
  compact continuing local arc and its entities were already introduced in the
  chapter prefix, it should be treated as absorbed by the current chapter unless
  other evidence proves it is foreign;
- fragmented one-off clusters remain strong pollution evidence even when each
  individual snippet is short.

This is the current answer to "can tuning solve it?": no. Threshold movement
cannot separate the old false positives from true pollution. The algorithm must
separate raw recall, promoted entities, local-arc absorption, and fragmented
foreign-cluster evidence.

## V2 Structural Absorption Algorithm

The first fingerprint strategy is now only a baseline. Real samples showed that
patching it with more short-word filters, transition guesses, or neighbor
rescues will become a rule wall. The next algorithm must judge whether new text
can be absorbed by the book structure, not whether it matches a hand-written
transition pattern.

V2 represents every chunk with multiple views:

- semantic vector,
- entity set,
- fingerprint feature set,
- entity/relation graph,
- world vector,
- style vector.

The chapter-level pipeline is:

```text
chapter chunks
  -> self-similarity / change-point candidates
  -> suffix membership against the book model
  -> alien entity cluster detection
  -> graph absorption check
  -> world/profile consistency check
  -> future integration check
  -> final high-confidence pollution decision
```

High-confidence pollution requires all of these to be true:

- the chapter has a strong internal break,
- the suffix is internally coherent enough to look like a separate narrative,
- suffix membership in this book is low,
- unfamiliar entities form a dense isolated cluster,
- the isolated cluster is new relative to the same chapter prefix,
- the cluster has weak connection to the book entity graph,
- later chunks do not integrate that cluster back into the book core,
- the suffix remains far from the book's prototypes or clean distribution.

Normal transitions must be accepted without enumerating transition words. A new
map, viewpoint, enemy, flashback, or side plot is legal when it is absorbed by
the book graph, world profile, or future integration. A polluted suffix is
different: it may be coherent by itself, but it stays structurally disconnected
from the book.

The first V2 implementation intentionally separates topic n-grams from
identity entities:

- n-grams can feed sparse topic vectors, prototype distance, and
  prefix/suffix separation;
- n-grams cannot directly create an alien entity cluster;
- alien entity clusters are rebuilt from the de-overlapped evidence text and
  require boundary checks, repeat support, type morphology, and co-occurrence;
- a suffix cluster must be novel against the same chapter prefix. If the same
  alleged alien entities already appeared before the split and the suffix keeps
  carrying the same local scene, this is a local side-arc continuity case, not a
  suffix pollution proof;
- weak suffix-only morphology such as a single short word ending in `门` or
  `山` is not enough unless it repeats, has an explicit type signal, is long and
  specific, or appears with other strong entities.
- `alienContinuity` is tracked separately from `alienCluster`. A high cluster
  score means there is enough unfamiliar entity evidence; a high continuity
  score means the evidence is concentrated in a small repeated local cast. That
  second signal can suppress auto-cleanup only when the same local cast was
  already introduced by the chapter prefix. It must not suppress fragmented
  pollution made of many one-off unrelated snippets.

This is the core guard against the old failure mode where random fragments such
as ordinary phrase tails were treated as foreign people or settings.

## V2 Implementation Order

1. Build richer chunk facts from existing text: entities, relations, fingerprint
   hits, world/style statistics, and sparse lexical vectors.
2. Add self-similarity and change-point scoring. This only finds possible
   breaks; it does not decide pollution.
3. Build a `BookModel` with core/support fingerprints, an entity graph,
   world/style profiles, and multiple prototypes from high-confidence clean
   chunks.
4. Score suffixes with book membership, alien cluster isolation, graph
   absorption, world consistency, prototype distance, and future integration.
5. Emit `NORMAL`, `LEGITIMATE_TRANSITION`, `SUSPICIOUS`, or
   `HIGH_CONFIDENCE_POLLUTION` with evidence scores.
6. Replay only real phone reports and manually inspect tail chapters before
   expanding sample count.

Embedding/prototype work can start with sparse lexical or TF-IDF vectors so the
module stays Android-feasible. A TFLite/ONNX embedding model is an optional
later enhancement, not required for the first V2 proof.

## V3 Novel State Memory, Non-trained

The current implementation direction is the non-trained Novel State Memory
version. It is allowed to replace the earlier V1/V2 internals as long as the
same real replay fixtures keep passing. The key rule is that the analyzer no
longer lets a single chunk or a raw recalled word decide cleanup.

### Chunk Data

Each chunk is converted into several lightweight views:

- promoted entities and known/alien entity splits,
- relation edges derived from promoted entities,
- world vector by typed feature distribution,
- style vector from punctuation, dialogue, paragraph, and length statistics,
- sparse lexical vector from topic term counts with local IDF weighting.

Raw n-grams, surname hits, and suffix/regex matches are only recall candidates.
They must pass promotion before they become entity evidence. The sparse lexical
view can affect prototype distance and topic separation, but it cannot by
itself form an alien entity cluster.

### Book Memory

Clean earlier chunks build a book memory instead of one global center:

- `coreEntities` and `relationEdges` model the stable identity graph,
- `coreTerms` hold the stable fingerprint vocabulary,
- `lexicalPrototypes` are multiple TF-IDF-like sparse centers for different
  narrative/topic lines,
- `worldProfile` and `styleProfile` hold the normal book distribution.

This is deliberately multi-prototype. A normal viewpoint switch, side plot, or
new map does not need to resemble the immediately previous chunk; it only needs
to be explainable by one of the book-memory views or be absorbed by the same
chapter/book graph.

### Target Chapter Sequence

The target chapter is scored as a chunk sequence:

```text
chapter chunks
  -> candidate suffix/run boundary
  -> compare evidence segment against Book Memory
  -> suppress absorbed local arcs
  -> report only persistent structural outliers
```

Current sequence signals include:

- break score and prefix/suffix separation,
- suffix cohesion,
- book membership loss,
- prototype similarity / OOD distance,
- graph absorption,
- alien entity cluster size, novelty, and continuity,
- prefix book strength and prefix alien absorption,
- abnormal evidence character count and evidence coverage inside the chapter.

### Decision Types

The V3 state vocabulary is:

- `NORMAL`: no cleanup suggestion,
- `NON_STORY`: skipped metadata, author note, lottery, leave notice, or similar
  non-story chapter,
- `POLLUTED_SUFFIX`: suffix after a boundary is high-confidence foreign text,
- `POLLUTED_RUN`: a local or whole-chapter foreign run is high-confidence,
- `UNCERTAIN`: scores are suspicious but do not satisfy the structural gates.

For compatibility with the existing experiment reports, `CleanSuggestion` still
keeps the older `PollutionType` field, but it now also exposes the V3
`stateType`. Logs use `v3.*` stages so future failures can be diagnosed from
the state path rather than guessed from threshold values.

### High-confidence Requirement

High-confidence pollution needs multiple independent structural facts. The
current code allows reporting only when the evidence segment is far from the
book memory and has weak graph absorption, or when a fragmented alien cluster
dominates the segment. A local continuing arc is suppressed when the alleged
alien cast already appears in the chapter prefix and remains world/profile
consistent.

Short whole-chapter and short-suffix pollution use absolute evidence gates in
addition to the ordinary sequence gates. This is required for late source
pollution where a chapter has only a small valid prefix or no useful prefix at
all. A short segment can be reported only when enough of the chapter is covered
by promoted abnormal evidence and the same segment also has low book membership,
low prototype similarity, weak graph/future integration, and a strong alien
identity cluster. Evidence coverage is therefore a guard against both misses in
short polluted chapters and false positives from tiny residual tails.

This means the algorithm accepts normal transitions without enumerating
transition words. It asks whether the new content can be absorbed by the book
state. If it cannot be absorbed, is internally coherent or persistently
foreign, and has enough promoted entity evidence, it is reported.

## Catalog Strategy

Catalog experiments compare source catalogs by:

- normalized title key coverage,
- source chapter count,
- duplicate ratio,
- ordinal continuity,
- missing ordinal ranges,
- first/last chapter signals.

The module reports the best catalog candidate and the reason, but it does not
change production catalog code.

## Logging

All important decisions use `AlgorithmTrace`:

- input loaded,
- quality seed loaded and applied,
- source import/search/fetch,
- catalog candidate chosen,
- chunks generated,
- fingerprint built/refined,
- chunk scored,
- pollution segment detected,
- final suggestion emitted.

Successful phone-side runs also write report files and fetched chapter text to
the app external files `algorithm-test` directory. A fingerprint result is not
accepted until these real chapter files have been manually inspected.

## Batch Source Validation

Large-sample validation must be executed as a bounded parallel batch on the
phone, not as a strictly sequential one-book-at-a-time loop. The batch runner
uses these rules:

- schedule the whole target set in one run;
- import the bundled source JSON once and reuse the parsed source list;
- give every target its own local trace recorder and output directory;
- limit active targets with a finite parallelism cap;
- write `batch-summary.txt` after all targets finish.

The concurrency cap is part of the design. Running every target at once would
mix unrelated failures from Android heap pressure, source-engine request load,
and network contention into the algorithm result. Running exactly one book at a
time is also poor evidence because it makes large validation too slow and hides
batch stability issues. The cap is intentionally configurable. The earlier
70-book run used 8 active targets to prove the bounded-parallel harness, but
large novels also produced heap OOMs under that load. The expanded 100-chapter
audit pass currently uses one active target to keep evidence clean; after the
algorithm benchmark is stable, the cap can be raised again and treated as a
separate performance/stability validation.

The expanded source-validation replay is sampled, not full-book. It reads a
bounded late-book context for fingerprint/book-memory construction and a sparse
tail/backward probe set for pollution detection. This increases the chance of
seeing real source pollution in ongoing novels while still keeping a single
phone report small enough to inspect and replay locally.

Batch completion is not the same as algorithm acceptance. A complete report only
means the phone fetched real catalogs and real chapter text and the analyzer
produced output. Any new target becomes a red/green fixture only after its real
chapter files are inspected and the expected suggest/no-suggest indexes are
recorded.

## Extension Points

Later experiments can replace the analyzer behind the same UI:

- PMI and boundary entropy termhood,
- relation graph scoring,
- background IDF dictionaries,
- local model recheck for borderline chunks,
- benchmark harness over many real novels.

## 2026-05-23 Tail Sampling Replay Policy

Raw-corpus replay no longer reads or analyzes whole books. The replay treats the
last 100 chapters as a high-risk window, but does not run all 100 chapters. It
uses a bounded deterministic probe plan:

- `TARGET_RECENT`: the last 2 chapters contiguously.
- `TARGET_TAIL`: exponential offsets inside the last 100 chapters
  (`1, 2, 4, 8, 16, 32, 64`, and the 100-chapter boundary when present), with
  no automatic neighbor expansion in the first production-style pass.
- `TARGET_EXTENDED`: if the book is longer than the 100-chapter risk window,
  probe farther back by coarse exponential offsets (`256, 512, 1024, ...`).
  This catches long pollution runs without scanning every chapter. It does not
  force a book-head probe in the first pass; if the coarse probes imply a long
  run, a targeted second pass can narrow that range.
- `TARGET_BRACKET`: not part of the first fast replay pass. If the first pass
  finds a clean/polluted bracket, a later targeted replay can add midpoint
  probes in that interval.
- `NEAR_CONTEXT`: up to 8 evenly-spaced chapters from the 300 chapters before
  the risk window. These are the main Book Memory / fingerprint seed because
  they reflect the current late-book state.
- `MID_CONTEXT`: up to 2 evenly-spaced chapters between the long-term anchors
  and the near context.
- `LONG_ANCHOR`: up to 1 early/mid-book anchor. These prevent total drift but
  cannot dominate late-book identity.
- For short books with no pre-risk context, a fallback context is sampled before
  the earliest target probe.

Only context roles are passed as explicit seed chapters. Target probes
(`TARGET_RECENT`, `TARGET_TAIL`, `TARGET_EXTENDED`) are not
allowed to build the fingerprint, and only target-probe suggestions are counted
in the replay summary. This keeps validation focused on tail pollution without
letting the target text contaminate the Book Memory.

Every replay item logs phase timing: file listing, sampling, text read, analyzer
run, audit extract writing, and total elapsed time. This is required because the
same strategy must be deployable on-device, not just useful in an offline test.
