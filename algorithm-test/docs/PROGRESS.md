# Algorithm Test Progress

## 2026-05-23

- Added a pre-analysis chapter quality gate that separates clean story text,
  safely trimmed story text, non-story chapters, bad extraction/page chrome, and
  uncertain mixed text before fingerprint or Book Memory construction.
- Verified with real snippets that the cleaner is not deleting good text in the
  inspected failure cases: clean chapters keep the story body, while bad
  chapters are mostly JavaScript/page shell plus preview-only or truncated text.
- Clarified the layer boundary: `need-more-clean-story-context` is not a final
  source failure. It means the sampler must keep taking earlier chapters from
  the same source/catalog until enough clean story context exists for Book
  Memory. Source switching remains a higher-level fallback only after same-source
  backfill cannot satisfy that requirement.
- Updated source experiments to pass explicit seed chapter indexes into the
  analyzer, instead of relying on the analyzer's default first-70%-of-sampled
  fallback.
- Added same-source memory backfill to source fetching: if the planned seed
  sample has too few clean story chapters after the quality gate, the runner
  probes additional earlier chapters from the same catalog and logs every
  backfill fetch with role, quality state, raw chars, and clean chars.
- Added quality-aware context backfill to local raw-corpus replay. The replay
  keeps target probes unchanged but adds `MEMORY_BACKFILL` context chapters from
  the same local book directory until enough usable clean context is available,
  recording the probes in `sampling-plan.txt`.
- Replayed the 61-book fixed seed suite after the quality gate change. The
  previous bad-extraction false positives for `item-21 / 仙人消失之后` and
  `item-51 / 旧域怪诞` no longer appear in the failure list. The suite is still
  red for unrelated remaining cases: missing suggestions in `item-02`,
  `item-17`, `item-18`, `item-22`, `item-23`, `item-37`, and extra suggestions
  in `item-27`.
- Confirmed the full fetched raw corpus already exists on the phone under
  `/storage/emulated/0/Android/data/com.ldp.reader/files/algorithm-test`.
  Pulled only the existing `fetch-batch-1779484863140` target directories for
  `021-仙人消失之后` and `023-旧域怪诞`; no network/source re-fetch was used.
- Added an opt-in targeted raw-corpus replay test that reads existing full
  fetched directories, applies tail/extended sampling plus same-source memory
  backfill, passes explicit seed indexes to the analyzer, and writes
  `summary.tsv`, `sampling-plan.txt`, `algorithm-report.txt`, and
  `algorithm-log.txt`.
- Targeted replay initially exposed an actual planning gap: with only 96
  backfill probes, `仙人消失之后` produced only 6 usable context chapters.
  Increased the configurable same-source backfill budget to 256 and classified
  `写给书友/书友的一封信/茶话会` as non-story author material.
- Targeted replay then passed on the existing full fetched data:
  `仙人消失之后` used 2909 full fetched chapter files, selected 30 analysis
  chapters, reached 8 usable context chapters, and produced no target
  suggestions; `旧域怪诞` used 483 chapter files, selected 19 analysis chapters,
  reached 9 usable context chapters, and produced target suggestions that still
  need manual audit.
- `:algorithm-test:testDebugUnitTest --offline --no-daemon --tests
  com.ldp.reader.algorithmtest.core.ChapterQualityGateTest --tests
  com.ldp.reader.algorithmtest.core.NovelPollutionAnalyzerGuardTest` passed.
- `:algorithm-test:testDebugUnitTest --offline --no-daemon --tests
  com.ldp.reader.algorithmtest.core.RawCorpusTargetReplayTest.replayTargetedFetchedRawCorpusWithMemoryBackfill
  -DrawCorpusTargetReplay=true -DrawCorpusTargetRoot=C:/project/reader/algorithm-test/build/raw-corpus-101/tmp-replay-existing`
  passed.
- Ran the existing 101-book raw corpus on the phone with raw replay
  parallelism 5. Output:
  `/storage/emulated/0/Android/data/com.ldp.reader/files/algorithm-test/raw-corpus-replay-1779508616322`.
  Local copy:
  `algorithm-test/build/phone-reports/raw-corpus-replay-1779508616322`.
  Result: 101 ok, 0 fail, 46 suggestion books, 144 suggestion chapters,
  device elapsed about 746 seconds.
- Started manual audit from the generated audit extracts and recorded the first
  pass in `RAW_REPLAY_1779508616322_AUDIT.md`. Confirmed true positives on
  obvious mixed-fragment pollution such as `从赘婿开始建立长生家族`,
  `旧域怪诞`, `苟在武道世界成圣`, `苟在两界修仙`, `叩问仙道`,
  `异度旅社`, and `仙工开物`.
- The same audit found clear false positives on normal story content:
  `盖世双谐`, `奥术神座`, `夜的命名术`, and at least part of
  `北宋穿越指南`. This means the current system is not yet production-close;
  the issue is structural memory/prototype coverage for legitimate remote arcs,
  side arcs, and fanwai, not a simple threshold-only problem.

## 2026-05-22

- Created branch `codex/algorithmtest`.
- Deleted misspelled branch `codex/algrithentest`.
- Added isolated `:algorithm-test` module registration.
- Added module README, design document, and progress document.
- Implemented MVP rule-based pollution analyzer inside `algorithm-test`.
- Added catalog fusion probe for source catalog comparison.
- Added source experiment runner for pasted Legado-compatible source JSON.
- Added standalone Android UI harness with `AlgorithmTrace` logs.
- Removed synthetic-pollution validation from the accepted workflow after review.
- Shifted source runner to real tail-chapter sampling: earlier chapters build
  the fingerprint, tail chapters are fetched for pollution detection.
- Converted `algorithm-test` from a standalone app into a debug library module
  hosted by the single reader app.
- Added debug dependency from `app` to `algorithm-test` without touching
  production reader functions or existing logic.
- Added a launcher entry for the experiment Activity so it can be opened
  directly during debug validation.
- `:algorithm-test:testDebugUnitTest :app:assembleDebug --offline --no-daemon`
  passed after converting the module into a host-app debug library.
- Added a runtime button that loads the host app bundled
  `source-engine/book-sources.json` asset, so real source-engine search/catalog
  and content fetch can be tested without hand-pasting source JSON.
- AI App Bridge `install-apk` successfully installed the host `com.ldp.reader`
  debug APK, and `launch-activity` opened
  `com.ldp.reader.algorithmtest.MainActivity`.
- Bridge tree confirmed the debug test UI is inside the host reader app and
  shows `Run Bundled Sources`.
- First bundled-source smoke on `仙逆` reached source-engine search:
  12 source attempts, 115 returned books, 2 exact title/author matches.
- The first matched source failed catalog fetch with HTTP 403. Updated the
  runner to record per-source detail/catalog/content failures and continue to
  the next matched source instead of failing the whole experiment.
- Updated search execution in the experiment runner from one opaque batch call
  to per-source `source-engine` calls, so logs now show every source search
  start, finish, duration, result count, and failure message.
- The second smoke showed a source with a valid 4264-chapter catalog but blank
  cleaned content. Updated content sampling so blank content is logged as
  failure, three consecutive blank chapters abandon that source, and a source
  must provide at least 8 usable sampled chapters before it can feed the
  analyzer.
- Increased experiment search coverage to 40 sources and catalog candidates to
  8 so the harness can keep looking for a usable real source after bad early
  sources.
- Third smoke showed individual bad sources can still spend 16 seconds in a
  single search. The experiment runner now creates its own `source-engine`
  fetcher with shorter 4s connect / 6s read timeouts so bad sources do not
  dominate algorithm validation runs.
- The fourth smoke showed the 21.6MB bundled source asset was read on the UI
  thread and caused skipped frames. Moved bundled source loading into an IO
  coroutine before starting the experiment.
- Reworked the first fingerprint implementation into an open-set belonging
  detector:
  - n-gram terms are only candidates,
  - book fingerprint terms must repeat across chapters,
  - terms must classify into entity/world/phrase categories before use,
  - contained short fragments are suppressed when a longer typed feature has
    comparable chapter coverage,
  - repeated unknown typed entities become alien evidence,
  - known and alien relation graphs both affect the belong score,
  - suffix/local abnormal detection ignores the chapter's first third.
- Added guard tests for the algorithm invariants. These are not validation
  evidence; they only protect implementation rules:
  - repeated real short name can enter the fingerprint,
  - one-off false long name cannot enter the fingerprint,
  - long organization terms suppress embedded false short names,
  - alien runs in the first third do not trigger cleanup,
  - suffix pollution after the judgment boundary can be detected.
- Added the second algorithm roadmap to the design document: OOD detection,
  prototype classification, graph anomaly detection, change-point detection,
  stylometry, and weak-supervision ensemble.
- `:algorithm-test:testDebugUnitTest --offline --no-daemon` passed after the
  fingerprint rewrite and guard tests.
- Added an opt-in real-novel probe test. It is skipped by default, and when
  explicitly enabled it uses `source-engine` to fetch real catalog/content,
  runs the algorithm, then writes the report plus fetched chapter text under
  `algorithm-test/build/reports/real-novel-probe/` for manual tail-chapter
  inspection.
- Local JVM real-novel probes are not accepted as source connectivity evidence
  on this machine because the PC network is behind VPN and has different
  reachability from the phone. Source fetch validation must run inside the
  installed Android app on the phone; the PC is only used for build/install and
  log collection.
- Reinstalled the host debug APK on the arm64 phone `b46093e6 / PKR110` through
  AI App Bridge `install-apk`. The 32-bit `KM16232B40184 / K2_MINI` device
  rejected the current APK with `INSTALL_FAILED_NO_MATCHING_ABIS` because the
  app debug build only packages `arm64-v8a` native libraries.
- Phone-side source validation for `仙逆 / 耳根` with the bundled source list:
  first 40 searchable sources produced 132 books and 3 exact title/author
  matches. The matched sources were not usable for algorithm validation:
  `丁丁小说` catalog returned HTTP 403, `读万卷info` had a 4264-chapter catalog
  but three sampled chapters returned blank content, and `搜小说` had only 20
  pseudo split chapters and was rejected by the minimum catalog length gate.
- Phone-side source validation for `斗破苍穹 / 天蚕土豆` with the bundled source
  list: first 40 searchable sources produced 130 books and 2 exact title/author
  matches. `读万卷info` had a 3328-chapter catalog but returned blank sampled
  content, and `爱尚小说②` returned 0 chapters. No real text was available for
  algorithm validation from that fixed search window.
- Reworked the real source runner in `algorithm-test` from fixed-window
  search-then-validate into per-source waterfall validation: each source search
  is checked for exact title/author matches immediately, matching books go
  straight through detail/catalog/content sampling, and the runner stops once it
  has enough real chapter text for the analyzer.
- Reinstalled and reran the phone-side waterfall on `斗破苍穹 / 天蚕土豆` using
  the arm64 phone `b46093e6 / PKR110`. It was confirmed to run on the phone
  network, not through the PC. The first 40 raw JSON-order sources still failed:
  2 exact title/author matches, one 3328-chapter catalog with blank sampled
  content, and one empty catalog.
- Updated the experiment runner to load the existing global
  `source-quality-seed-v1.tsv` and sort real-source validation by tier/score
  before falling back to raw JSON order. The default validation pool is now 160
  sources and 24 catalog candidates so a bad early slice cannot terminate the
  test prematurely.
- Phone-side runs now write report, trace, algorithm log, and fetched real
  chapter text under the app external files `algorithm-test` directory. These
  files are required evidence for the next step: manually read the fetched tail
  chapters before judging whether the fingerprint result is correct.
- Next step: rebuild, install through AI App Bridge onto `b46093e6`, rerun one
  book, pull the generated report/chapter files from the phone, and inspect the
  real text before expanding validation.
- Switched validation target from older completed examples to the ongoing
  xuanhuan/xiuxian novel `我在修仙界万古长青 / 快餐店`.
- Phone-side run on `b46093e6 / PKR110` found a real usable source:
  `👍 55读书`, 543 catalog entries, 48 sampled chapters fetched from the phone
  network.
- The first run hit a real Android heap OOM before report generation. Root
  causes in the experiment analyzer were confirmed by stack traces:
  `buildRelationEdges()` re-ran full candidate extraction, and term statistics
  stored per-term chapter/chunk sets plus boundary maps. Reworked the
  experiment module to build relation edges from already selected entity
  features and changed term/relation stats to compact counters. This is test
  infrastructure work inside `algorithm-test`; production reader code was not
  changed.
- After the memory fix, the same phone-side run completed and wrote the device
  report under:
  `/storage/emulated/0/Android/data/com.ldp.reader/files/algorithm-test/我在修仙界万古长青-1779431462833`.
- Manual inspection of the pulled real tail chapters found true pollution in
  `第521章 诚不我欺，翻手为云`: the chapter starts with valid
  `陆长安 / 覆海真君` content, then abruptly switches into unrelated snippets
  with names/settings such as `沈幼清`, `庄公子`, `安德莉亚`, `洛基`, `夏洛`,
  `韩允钧`, `杜子辕`.
- Root cause of the initial miss was not a missing stop-word. It was a general
  algorithm issue: short chapters were chunked too coarsely, and character
  confirmation was too permissive for two-character candidates. The repair
  direction is adaptive short-chapter chunking plus character confirmation by
  distribution/boundary/context/graph evidence. Enumerated dictionaries can be
  considered as optional recall aids only; they are not accepted as a patch-wall
  solution.
- Added phone replay diagnostics for tail chunk scores and lowest-belong chunks,
  then added `phoneReportMustSuggestIndexes` so manually confirmed polluted real
  chapters can become replay requirements.
- Clarified the algorithm boundary after review: finite helper enums/regexes
  such as surname characters, organization/location suffixes, weapon/item,
  vehicle, currency, and realm morphology are allowed only as generic
  candidate-recall/type aids. They must not become book-specific stop-word or
  rescue patches.
- Tightened two-character person candidates so standalone tokenization is no
  longer enough to enter the `CHARACTER` fingerprint. A two-character
  surname-recalled candidate now needs repeated context-bearing occurrences
  across chapters. This addresses false person candidates such as adjective-like
  two-character terms without adding those terms to a stop-word list.
- Extended the generic item/vehicle suffix helpers for experimental type recall
  (`塔`, `幡`, `旗`, `环`, `镯`, `瓶`, `车`, `船`, `舰`, `梭`, `辇`) while
  keeping the same statistical fingerprint gates.
- Re-ran `NovelPollutionAnalyzerGuardTest`: passed.
- Re-ran the phone-report replay for
  `我在修仙界万古长青 / 快餐店` with
  `phoneReportMustSuggestIndexes=542`: passed. The manually inspected polluted
  chapter is still reported as `LOCAL_ABNORMAL` starting around offset 267.
- Re-ran full `:algorithm-test:testDebugUnitTest --offline --no-daemon`: passed.
- Rebuilt `:app:assembleDebug --offline --no-daemon`, installed the debug APK
  to phone `b46093e6 / PKR110` with AI App Bridge `install-apk`, and launched
  `com.ldp.reader.algorithmtest.MainActivity`.
- Phone-side bundled-source experiment on `我在修仙界万古长青 / 快餐店` completed
  on the phone network. It imported 6411 sources, found 1 exact title/author
  match, selected `👍 55读书` with 543 catalog entries, fetched 48 real chapters,
  and wrote the report under
  `/storage/emulated/0/Android/data/com.ldp.reader/files/algorithm-test/我在修仙界万古长青-1779432940717`.
- Pulled the report to
  `algorithm-test/build/phone-reports/wangu-1779432940717`. The device report
  still emits 1 suggestion: chapter 543 / `第521章 诚不我欺，翻手为云`,
  `LOCAL_ABNORMAL`, start offset 267, confidence 1.00.
- Manually re-read the pulled chapter text. It starts with valid
  `陆长安 / 覆海真君 / 大劫` content and then abruptly switches into unrelated
  snippets containing `沈幼清`, `庄公子`, `安德莉亚`, `洛基`, `夏洛`,
  `韩允钧`, `杜子辕`, and other unrelated contexts. This is a confirmed true
  positive, not a synthetic fixture.
- Added `phoneReportMustSuggestIndexes` forwarding to the Gradle unit-test JVM
  so manually confirmed polluted chapters are real replay assertions instead of
  inert command-line text.
- Phone-side validation for `玄鉴仙族 / 季越人` found usable source
  `笔趣阁22`, 1644 catalog entries, 48 fetched chapters. Manual inspection found
  a false positive on `第888章 金钺`: the reported evidence was only a tiny final
  residual chunk. The detector now requires a minimum amount of abnormal
  evidence text before reporting suffix/local cleanup.
- The same `玄鉴仙族` report has confirmed true positives in the late tail
  chapters `第1492`, `第1493`, `第1494`, `第1495`, `第1496`, and `第1497`.
  Those chapters begin with plausible current-book text and then switch to
  unrelated snippets.
- `第1490章 丹尸...` and `第1491章 炉...` were manually inspected and treated as
  likely false positives. They are coherent late-arc material with repeated
  internal terms and adjacent-chapter support. The fix is a generic continuity
  gate: a suspicious run can be suppressed only when the chapter prefix has
  known-book evidence, the run repeats several topic terms inside itself, and
  nearby known-book chapters share those terms. A single shared word is not
  enough.
- Phone-side validation for `道爷要飞升 / 裴屠狗` found usable source
  `笔趣阁22`, 873 catalog entries, 48 fetched chapters. Manual inspection found
  true pollution in `第197章 不翼而飞`, `第198章 谁人垂钓混沌中`,
  `第199章 万坤轮回、石台外`, and `第200章 造化之气，几人上榜`.
  The polluted suffixes contain unrelated names/settings such as `影子堂`,
  `武当派`, `精灵族`, `吴汉`, `戾士`, `狐妖`, `封神榜`, `玄魔宗`, `金币`,
  `疗养院`, and `柳德邦`.
- Root cause for the `道爷要飞升` miss was not a missing word list. Bare
  type-like terms and short phrase hits were contributing too much normal
  evidence. The fix removes bare typed signals from identity evidence through
  generic morphology: a term that is exactly the location/realm/item/world
  signal can type a candidate, but it cannot become book identity by itself.
  This is not a book-specific patch wall.
- Replayed the three manually inspected phone reports:
  - `我在修仙界万古长青 / 快餐店`: must suggest pulled report index `542`.
  - `玄鉴仙族 / 季越人`: must not suggest `949`, `1634`, `1635`; must suggest
    `1636`, `1637`, `1639`, `1640`, `1642`, `1643`.
  - `道爷要飞升 / 裴屠狗`: must suggest `869`, `870`, `871`, `872`.
- Re-ran full `:algorithm-test:testDebugUnitTest --offline --no-daemon`: passed.
- Current validation status is three real phone-side reports with manually read
  tail chapters. The next step is to expand to two more ongoing xuanhuan/xianxia
  novels before considering a five-book pass stable.
- Expanded to phone-side sample `从水猴子开始成神 / 甲壳蚁` using source
  `👍 55读书`, 1529 catalog entries, 48 fetched chapters. Manual inspection
  confirmed that `第三百一十五章 已臻化境` was a false positive: the reported
  suffix is still the same-book flood rescue sequence around `梁渠`, `肥鲶鱼`,
  `拳头`, `卫绍`, and `颜庆山`.
- The same sample also contains clear tail pollution around the latest chapters:
  several chapters begin with real same-book content and then switch into
  unrelated snippets such as `孙殿英`, `魔晶`, `刘三石`, `陈默`, `张晓钢`,
  `龙辰`, `墨子柒`, and other disconnected contexts.
- This proves the V1 path is not stable enough: filtering one more short suffix
  phrase or adding another neighbor-rescue rule only moves the failure. The
  experiment direction is now changed to V2 structural absorption:
  change-point detection first, then suffix membership, alien entity cluster,
  graph absorption, world/profile consistency, prototype/OOD distance, and
  future integration. High-confidence cleanup must require multiple independent
  structural signals, not a single low fingerprint score or a hand-written
  transition/stop-word rule.
- Solidified the four manually inspected phone-side reports into local offline
  fixtures under `algorithm-test/src/test/resources/typical-cases/`. These are
  real pulled chapter texts, not network probes and not synthetic pollution.
- Added `cases.tsv` to record title, author, must-suggest chapter indexes, and
  no-suggest chapter indexes:
  - `我在修仙界万古长青 / 快餐店`: must suggest `542`.
  - `玄鉴仙族 / 季越人`: must suggest `1636,1637,1639,1640,1642,1643`; must
    not suggest `949,1634,1635`.
  - `道爷要飞升 / 裴屠狗`: must suggest `869,870,871,872`.
  - `从水猴子开始成神 / 甲壳蚁`: must suggest `1517,1518`; must not suggest
    `325`.
- Added `TypicalCaseReplayTest`. The fixture-presence test runs in the default
  unit-test suite. The full four-book red/green replay is opt-in:
  `.\gradlew.bat -DtypicalCaseProbe=true :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.TypicalCaseReplayTest.replayTypicalRealCases`
- Current status:
  - `.\gradlew.bat :algorithm-test:testDebugUnitTest --offline --no-daemon`
    passed.
  - The four-book replay command failed, as expected for V1, because
    `shuihouzi-1779435905960` chapter `325` is still suggested even though it
    was manually inspected as normal same-book content. This is now the local
    red-light acceptance test for the V2 algorithm.
- Implemented the first V2 structural absorption pass inside
  `NovelPollutionAnalyzer`:
  - each chunk now gets topic terms, typed entities, known/alien entity views,
    world profile, and style profile;
  - the book model now keeps core entities, relation edges, sparse prototypes,
    world profile, and style profile;
  - chapter decisions now log `v2.candidate` evidence with break,
    prefix/suffix separation, suffix cohesion, membership loss, alien cluster,
    graph absorption, prototype similarity, world consistency, future
    integration, and candidate alien terms;
  - n-grams feed topic/prototype/separation only and no longer directly create
    alien entity clusters.
- Made the entity-promotion rule explicit in code and design docs:
  dictionary/regex hits are only raw recall candidates, and they become entity
  evidence only after repeat support, strong typed morphology, or independent
  co-occurrence support. This is the generic fix for failures such as
  `青年哈` / `白瞎伱`; it is not a new word-specific stop-list patch.
- Added a guard test for the same rule and re-ran validation:
  - `.\gradlew.bat :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.NovelPollutionAnalyzerGuardTest`
    passed.
  - `.\gradlew.bat -DtypicalCaseProbe=true :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.TypicalCaseReplayTest.replayTypicalRealCases`
    passed on the four manually inspected real fixtures.
  - `.\gradlew.bat :algorithm-test:testDebugUnitTest --offline --no-daemon`
    passed.
- Began expanding beyond four books with phone-side run
  `神话之后 / 鹅是老五` from source `笔趣阁22`, 1259 catalog entries and 48
  fetched chapters. Manual inspection of the single reported chapter
  `第八百三十四章 不敌` showed it is a false positive: the alleged suffix keeps
  the same local `关欢 / 蒙川女 / 泉青成 / 拜越` scene that already appears before
  the split. Root cause is structural, not lexical: V2 did not require suffix
  alien clusters to be new relative to the same chapter prefix.
- Added alien-cluster novelty against the chapter prefix and a guard test for
  same-chapter side-arc continuity. This prevents a continuing local cast from
  being treated as a suffix from another novel.
- Review changed the sampling strategy: stop tuning one book at a time. The
  experiment UI now has a batch source runner with 20 real novel targets. Each
  item writes full source trace, algorithm log, and fetched chapter text so the
  next algorithm pass can be judged against a broad fixed sample set instead of
  repeatedly trading one single-book fix for another regression.
- Ran the 20-book batch on device `b46093e6` and pulled the full reports into
  `algorithm-test/build/phone-reports/batch-1779440890131-full/`. All 20 books
  produced reports; no batch item failed at the source-fetch level.
- Solidified those 20 real pulled reports into
  `algorithm-test/src/test/resources/batch-cases/`, including:
  - `batch-summary.tsv` with source-side report paths,
  - `suggestion-audit.txt` with real text snippets around every suggested
    cleanup start,
  - `cases.tsv` with manually inspected must-suggest and no-suggest indexes.
- Added `BatchCaseReplayTest`. The fixture-presence test runs directly; the
  full 20-book replay is opt-in:
  `.\gradlew.bat -DbatchCaseProbe=true :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.BatchCaseReplayTest.replayTwentyRealBatchCases`
- The true 20-book replay is currently red after adding `batchCaseProbe`
  passthrough:
  - `item-07` chapter `773` must not be suggested,
  - `item-17` chapters `0` and `883` must not be suggested,
  - `item-19` chapter `835` must not be suggested,
  - `item-20` chapters `78` and `572` must not be suggested.
- Evaluation result: this cannot be fixed reliably by threshold tuning. The
  false-positive normal chapters overlap the true-pollution chapters in the
  current score space, for example normal chapters can still show
  `membershipLow=1.00` and `alienCluster=0.95`. Raising thresholds would miss
  real mixed-source pollution. The next step is to replace the current broad
  n-gram/entity implementation with stricter promoted-entity based identity and
  graph/prototype scoring.
- Reworked the failing V2 implementation around the actual 20-book red/green
  evidence instead of threshold tuning:
  - removed the path where any stable two/three-character non-surname term could
    become a `CHARACTER` feature. That was the root cause behind generic words
    such as `如今`, `事情`, `开始`, and `发现` giving polluted chunks high
    known-book scores;
  - kept surname, compound-surname, and explicit nickname-prefix recall, but
    recall still only creates a candidate. It must pass structural checks before
    it can affect identity or alien evidence;
  - added compound-surname handling for names such as `欧阳木`, `令狐笑`, and
    `上官玄见`, so same-book late-arc characters are not automatically treated
    as foreign;
  - changed overlap handling for local runs so the chapter prefix is not empty
    just because chunk windows overlap the run start;
  - stopped treating every long typed-looking term as a strong alien entity.
    A segment-level typed entity now needs explicit type morphology or repeat
    support;
  - added `alienContinuity` to separate a compact continuing side-arc from
    a fragmented mixed-source suffix. A high-continuity new local arc can be
    suppressed only when the same alien cluster was already introduced in the
    chapter prefix; fragmented one-off clusters remain deletable.
- Manual text spot checks during this pass:
  - `阵问长生` chapter `773` / `第七百六十三章 牢狱` is normal same-book text.
    It introduces and continues `欧阳木`, `令狐笑`, `宋渐`, and `断金门` in the
    same prison scene, so the correct behavior is no cleanup suggestion.
  - `玄鉴仙族` chapter `1637` / `第1493章 学相` is real pollution. After a short
    same-book opening, it switches into unrelated snippets such as `夜辰`,
    `温思科`, `张启钟`, `应天府`, `李天逸`, and `血老鼠`.
  - `赤心巡天` chapter `883` / `第一百四十一章 万曈` is normal same-book text.
    The `危寻 / 海族 / 牛状海兽 / 识海之镜` scene is coherent world-building,
    not mixed-source pollution.
- Current validation status:
  - `.\gradlew.bat :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.NovelPollutionAnalyzerGuardTest`
    passed.
  - `.\gradlew.bat -DbatchCaseProbe=true :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.BatchCaseReplayTest.replayTwentyRealBatchCases`
    passed on all 20 real-book fixtures.
- Conclusion for the tuning question: threshold adjustment alone is rejected.
  The now-passing version required changing the feature promotion and structural
  decision model. Future failures should be addressed by adding real fixtures
  and improving promoted-entity/absorption evidence, not by moving a global
  threshold until the current sample happens to pass.
- Implemented the V3 non-trained Novel State Memory slice:
  - chunk data now includes promoted entities, known/alien splits, relation
    evidence, world/style vectors, and TF-IDF-like sparse lexical vectors;
  - book memory now keeps multiple lexical prototypes plus entity graph,
    relation edges, world profile, and style profile instead of a single
    center;
  - target chapter checks compare suffix/run evidence against this memory and
    require sequence-level structural outlier evidence before suggesting
    cleanup;
  - `CleanSuggestion` now includes V3 `stateType`
    (`POLLUTED_SUFFIX` / `POLLUTED_RUN`) while `v3.skip` logs mark
    `NON_STORY` chapters. Chapters with no suggestion are `NORMAL`, and
    suspicious but unreported cases remain `UNCERTAIN` internally.
- Renamed remaining structural logs from `v2.*` to `v3.*` so candidate,
  skip, and report logs match the current algorithm path.
- Re-ran the default algorithm-test JVM suite after adding the V3 state output:
  `.\gradlew.bat :algorithm-test:testDebugUnitTest --offline --no-daemon`
  passed.
- Re-ran the four-book typical replay after the V3 changes:
  `.\gradlew.bat -DtypicalCaseProbe=true :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.TypicalCaseReplayTest.replayTypicalRealCases`
  passed.
- Re-ran the 20-book batch replay after the V3 changes:
  `.\gradlew.bat -DbatchCaseProbe=true :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.BatchCaseReplayTest.replayTwentyRealBatchCases`
  passed.
- Expanded the phone-side batch target pool to 70 books:
  - 50 serial/ongoing-oriented targets, including `叩问仙道 / 雨打青石`,
    `苟在武道世界成圣 / 在水中的纸老虎`, and
    `苟在两界修仙 / 文抄公`;
  - 20 completed/other baseline targets.
- Added `BatchNovelTargetsTest` so the target pool count, required titles,
  blank fields, and duplicate title/author pairs are checked by the JVM suite.
- Reworked the device batch runner from book-by-book sequencing to bounded
  parallel execution:
  - all batch targets are scheduled together;
  - `BATCH_PARALLELISM = 8` limits active book experiments on the phone;
  - each book has its own `LocalAlgorithmTrace`;
  - source JSON is imported once and then reused by every target, avoiding
    concurrent re-parsing of the bundled 21MB source file.
- Re-ran validation after the batch-runner change:
  - `.\gradlew.bat :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.source.BatchNovelTargetsTest`
    passed;
  - `.\gradlew.bat :app:assembleDebug --offline --no-daemon` passed;
  - installed and launched the debug app on phone `b46093e6 / PKR110` through
    AI App Bridge.
- Ran the 70-book bounded-parallel batch on the phone. Device output directory:
  `/storage/emulated/0/Android/data/com.ldp.reader/files/algorithm-test/batch-1779454227451`.
  Pulled local evidence directory:
  `algorithm-test/build/phone-reports/batch-1779454227451-70/`.
- 70-book batch result:
  - total targets: 70;
  - complete reports: 61;
  - failed targets: 9;
  - completed/other baseline bucket: 20/20 complete;
  - serial/ongoing-oriented bucket: 41/50 complete.
- The three requested serial samples all produced complete real phone-side
  reports:
  - `叩问仙道 / 雨打青石`: 48 chapters, 4 suggestions;
  - `苟在武道世界成圣 / 在水中的纸老虎`: 48 chapters, 17 suggestions;
  - `苟在两界修仙 / 文抄公`: 48 chapters, 17 suggestions.
- The 9 failed serial targets need a separate follow-up pass:
  - 6 failed with the Android debug process 256MB heap OOM while running the
    large-book experiment concurrently;
  - 1 only returned short 50-chapter catalogs and was rejected by the catalog
    length gate;
  - 2 had no exact title/author match in the current source waterfall.
- Current conclusion for the user's concurrency question: yes, the batch should
  run together, but with a finite concurrency cap. Unlimited parallelism would
  make the source-engine/network/OOM signal worse, while strict book-by-book
  sequencing wastes phone runtime and hides cross-target stability problems.
- Next validation step: manually inspect the 61 complete reports before
  promoting any new item into permanent red/green fixtures. The 70-book batch
  proves source/batch coverage and gives evidence files, but algorithm
  correctness still requires real text inspection of suggested and suspicious
  tail chapters.
- Began manual audit of the 70-book batch and wrote the running audit document
  `algorithm-test/docs/BATCH70_AUDIT.md`.
- Generated an audit queue under
  `algorithm-test/build/phone-reports/batch-1779454227451-70/audit/`:
  61 complete reports, 22 books with suggestions, 185 suggestion rows.
- Read real fetched chapter text for the highest-suggestion books:
  - `从赘婿开始建立长生家族 / 天弦画柱`;
  - `旧域怪诞 / 狐尾的笔`;
  - `苟在武道世界成圣 / 在水中的纸老虎`;
  - `苟在两界修仙 / 文抄公`;
  - `大不列颠之影 / 趋时`;
  - `以神通之名 / 猪心虾仁`;
  - `叩问仙道 / 雨打青石`.
- The inspected suggestion chapters in those books are true pollution: each
  starts with plausible current-book content and then switches into unrelated
  snippets from multiple other novels. This is source text pollution, not a
  pure algorithm false positive in the inspected passages.
- Read no-suggestion tail samples for `神话之后 / 鹅是老五` and
  `大道之上 / 宅猪`. The sampled tail chapters were coherent same-book text,
  so no obvious missed pollution was seen in those inspected tails.
- `叩问仙道 / 雨打青石` is the first 70-book batch item ready for fixture
  promotion: all four suggested chapters were manually read and appear to be
  true polluted suffix/run cases.
- Promoted `叩问仙道 / 雨打青石` into the first audited 70-book fixture under
  `algorithm-test/src/test/resources/batch70-cases/`.
- Added `Batch70CaseReplayTest`:
  - default fixture-presence check runs with the normal JVM suite;
  - full replay is opt-in with
    `.\gradlew.bat -Dbatch70CaseProbe=true :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.Batch70CaseReplayTest.replayAuditedBatch70Cases`.
- Validation after fixture promotion:
  - `.\gradlew.bat :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.Batch70CaseReplayTest`
    passed;
  - a first parallel attempt to run both Gradle commands at once failed only
    because both processes tried to delete the same `test-results` binary
    output directory;
  - reran the opt-in replay alone and it passed.
- Continued 70-book manual audit with full real-text checks for four more
  reports:
  - `仙工开物 / 蛊真人`: all seven suggestions were manually read and confirmed
    as true mixed-source pollution. Neighboring chapters `579-582` were
    coherent and `今天请假` is non-story.
  - `没钱修什么仙？ / 熊狼狗`: all seven suggestions were manually read and
    confirmed as true mixed-source pollution. Neighboring chapters `885-887`
    were coherent and the checked leave chapters are non-story.
  - `贫道略通拳脚 / 九月当归`: the late tail pollution around fixture indexes
    `2010-2017` is real, but the current report misses `2013` and `2016`.
    The early `第三百零六章...妇人书生` report at fixture index `306` looks like
    same-book chapter content from the title and text, so it is treated as a
    likely false positive.
  - `异度旅社 / 远瞳`: chapters `768-775` are polluted in the fetched source,
    but the current report only suggests `770-775`, missing `768` and `769`.
    Chapters `766-767` were read as coherent same-book content.
- Promoted these four books into `batch70-cases` fixtures. `item-06` and
  `item-19` are green exact cases; `item-50` and `item-24` are deliberate red
  cases that prove the current V3 output is not yet "no extra, no missing" on
  the expanded 70-book audit.
- Fixed the `batch70CaseProbe` Gradle passthrough. Before this fix the opt-in
  replay command was being skipped by JUnit `Assume`, so a green result did not
  mean the 70-book fixtures had actually run.
- Re-ran validation:
  - `.\gradlew.bat :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.Batch70CaseReplayTest`
    passed, covering fixture presence in the default path.
  - `.\gradlew.bat -Dbatch70CaseProbe=true :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.Batch70CaseReplayTest.replayAuditedBatch70Cases`
    failed as expected with exactly the audited red findings:
    `item-50` extra `306`, missing `2013,2016`, and `item-24` missing
    `768,769`.
  - `.\gradlew.bat :algorithm-test:testDebugUnitTest --offline --no-daemon`
    passed.
- Fixed the audited 70-book red findings with generic V3 changes:
  - structural suffix candidates are no longer hard-blocked by the older
    chunk-level belong threshold before V3 evidence is scored;
  - a low-confidence break with no alien entity cluster is suppressed when the
    suspicious suffix is absorbed by the chapter title, which fixes the
    `贫道略通拳脚` fixture index `306` same-book `妇人书生` scene;
  - run detection now has a judgment-area fallback so a mostly polluted chapter
    with only a small valid opening can still be checked structurally;
  - dense whole-chapter alien clusters and strong foreign entity runs can pass
    without relying on one old chunk score.
- Re-ran the 70-book replay after the fix:
  `.\gradlew.bat -Dbatch70CaseProbe=true :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.Batch70CaseReplayTest.replayAuditedBatch70Cases`
  passed on the five audited 70-book fixtures.
- The broader fixture replays then exposed three additional true positives
  that had been missing from earlier human manifests, not algorithm regressions:
  - `没钱修什么仙？` fixture index `634`, title `今天第二章晚一点`, is random
    mixed-source text and was added to `batch70-cases`.
  - `从水猴子开始成神` fixture index `1527` is random mixed-source text and was
    added to both `typical-cases` and `batch-cases`.
  - `长生从炼丹宗师开始` fixture index `1352` is random mixed-source text and
    was added to `batch-cases`.
- Re-ran validation after updating those manifests:
  - `.\gradlew.bat -DtypicalCaseProbe=true :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.TypicalCaseReplayTest.replayTypicalRealCases`
    passed.
  - `.\gradlew.bat -DbatchCaseProbe=true :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.BatchCaseReplayTest.replayTwentyRealBatchCases`
    passed.
  - `.\gradlew.bat :algorithm-test:testDebugUnitTest --offline --no-daemon`
    passed.
- Built the 60+ book offline seed benchmark required before the next algorithm
  iteration:
  - copied all 61 complete phone-side reports and their real sampled chapter
    text into `algorithm-test/src/test/resources/batch70-seed-cases/`;
  - regenerated `cases.tsv` and `labels.tsv` from each original report plus
    manual text-audit overrides, instead of trusting the current algorithm
    output blindly;
  - seed size is 61 books, 2926 labelled sampled chapters, 197 polluted labels,
    2645 clean labels, and 84 non-story labels.
  - the 197 polluted labels are report suggestions plus manually inspected
    true-pollution additions minus manually inspected false positives; the
    remaining green labels are seed no-suggest expectations, not final
    line-by-line manual proof.
- Added `Batch70SeedReplayTest`:
  - fixture presence runs directly through
    `fullBatch70SeedFixturesArePresent`;
  - full replay is opt-in with
    `.\gradlew.bat -Dbatch70SeedProbe=true :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.Batch70SeedReplayTest.replayFullBatch70SeedCases`.
- Validation status for the 61-book seed:
  - presence check passed, proving every sampled chapter has a label and every
    must/no-suggest index exists in the real text fixture;
  - full replay ran for 4m43s and is red on exactly three no-suggest false
    positives: `长夜余火` index `960`, `修真四万年` index `5570`, and
    `斗破苍穹` index `1693`;
  - those three chapters were manually read as same-book content, so they remain
    seed red lights for the next algorithm pass rather than being removed from
    the benchmark.
- Reworked the non-model V3/V4 structural decision layer instead of adding a
  title-specific patch:
  - same-book anchor absorption is no longer a broad veto. It now only
    suppresses a narrow same-book concept drift shape: strong prefix book
    membership, high world consistency, low external identity strength, few
    alien entities, and cohesive/continuing evidence.
  - same-book explanatory/reference segments are absorbed only when the segment
    is highly expository, still matches the book world profile, has a strong
    same-book prefix, and does not contain strong external identity evidence.
  - this keeps the implementation model-free: chunk facts, book memory
    prototypes, graph absorption, world/style profiles, OOD, and sequence
    boundary evidence are still computed locally from the fixture text.
- Manually re-read the remaining `长夜余火` index `960` red light. The chapter
  follows `商见曜` / `真理` and then `蒋白棉` reading brain-area notes in the
  ordinary research area. The suspicious tail is same-book reference material,
  not mixed-source fiction.
- Re-ran validation after the non-model decision-layer change:
  - `.\gradlew.bat :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.NovelPollutionAnalyzerGuardTest`
    passed in 1m01s.
  - `.\gradlew.bat -Dbatch70SeedProbe=true :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.Batch70SeedReplayTest.replayFullBatch70SeedCases`
    passed in 5m04s. JUnit reported `tests=1 skipped=0 failures=0 errors=0`
    for the 61-book replay.
  - spot-check reports for `item-59` (`长夜余火`), `item-63` (`修真四万年`), and
    `item-70` (`斗破苍穹`) all show `Suggestions: 0`.
  - `.\gradlew.bat :algorithm-test:testDebugUnitTest --offline --no-daemon`
    passed in 32s.
- Expanded the next validation target pool beyond the 70-book seed:
  - current `BatchNovelTargets` contains 119 real title/author targets:
    79 serial/ongoing-oriented targets and 40 completed/other baseline targets;
  - source validation now samples 50 fingerprint chapters plus 50 tail chapters
    by default, so a successful target can inspect up to 100 fetched real
    chapters instead of the earlier 48-chapter run;
  - this is still a validation harness setting. It does not change production
    reader search, catalog, or chapter loading behavior.
- Changed the phone debug batch cap back to one active target for the expanded
  100-chapter pass. The previous 8-way batch proved bounded parallelism works
  as a harness shape, but it also produced Android heap OOMs on large novels.
  For the 100-chapter/manual-audit pass, correctness evidence is more important
  than throughput. Parallelism remains a tunable harness constant.
- Started the new 100-chapter phone-side pass:
  - `我在修仙界万古长青 / 快餐店` completed from source `👍 55读书`, selected
    100 real chapters, ran analysis, and emitted `Suggestions: 0`. Manual tail
    reading of the sampled end chapters found coherent same-book text and no
    obvious foreign mixed-source pollution in the checked passages.
  - `玄鉴仙族 / 季越人` completed from source `笔趣阁22`, selected 100 real
    chapters, and exposed a real algorithm miss: the device report suggested
    only two polluted chapters, while manual reading found six polluted
    chapters in the 100-chapter sample.
- Pulled the `玄鉴仙族` report to
  `algorithm-test/build/phone-reports/xuanjian-100` and converted the finding
  into a local replay requirement. The red command before the fix was:
  `.\gradlew.bat "-DphoneReportPath=C:\project\reader\algorithm-test\build\phone-reports\xuanjian-100" "-DphoneReportTitle=玄鉴仙族" "-DphoneReportAuthor=季越人" "-DphoneReportMustSuggestIndexes=1636,1637,1639,1640,1642,1643" :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.PhoneReportReplayTest.replayPulledPhoneReport`.
- Root cause of the `玄鉴仙族` miss:
  - several polluted chapters are short whole-chapter or near-whole-chapter
    foreign runs;
  - the older structural gate relied too much on relative suffix/run shape and
    not enough on absolute abnormal evidence coverage inside short chapters;
  - when the chapter has little valid prefix, the old path could under-report
    even though the text has a dense external entity cluster, weak book-memory
    prototype match, weak future integration, and enough promoted evidence.
- Fixed that as a generic non-model rule, not a title patch:
  - structural scores now track `evidenceCoverage`;
  - short whole-chapter foreign runs can pass when coverage, promoted alien
    identity, alien cluster, low prototype similarity, and low future
    integration all agree;
  - short suffix foreign runs have their own absolute evidence gate so a small
    but clearly foreign tail is not hidden by whole-chapter averaging.
- Replayed the pulled `玄鉴仙族` 100-chapter report after the fix with six
  must-suggest indexes and all other sampled chapters as no-suggest. The command
  passed in 1m16s and the report now emits exactly six suggestions for the
  manually confirmed polluted chapters.
- Re-ran the 61-book seed replay after the new gate. It initially reported nine
  additional chapters that the seed had treated as no-suggest. Manual reading
  showed all nine are real mixed-source pollution, not false positives:
  - `叩问仙道` index `2782`;
  - `苟在武道世界成圣` indexes `735`, `737`;
  - `苟在两界修仙` index `448`;
  - `从赘婿开始建立长生家族` index `1115`;
  - `踏星` indexes `5568`, `5571`;
  - `大不列颠之影` index `1152`;
  - `完美世界` index `2063`.
- Promoted those nine seed labels from no-suggest/clean to
  manual-reviewed polluted labels. This keeps the benchmark honest: the
  algorithm was stricter than the old manifest, and manual text inspection
  confirmed the stricter output.
- Re-ran validation after updating the seed labels:
  - `.\gradlew.bat -Dbatch70SeedProbe=true :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.core.Batch70SeedReplayTest.replayFullBatch70SeedCases`
    passed in 5m16s;
  - `.\gradlew.bat :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.source.BatchNovelTargetsTest --tests com.ldp.reader.algorithmtest.core.NovelPollutionAnalyzerGuardTest`
    passed in 42s;
  - `.\gradlew.bat :app:assembleDebug --offline --no-daemon` passed in 1m02s;
  - AI App Bridge `install-apk` installed the rebuilt debug APK.
- Current evidence boundary:
  - proven: the harness can run 100-chapter real phone reports, the target pool
    is expanded to 119 books, the 61-book/2926-chapter seed replay passes after
    manual correction, and the new `玄鉴仙族` 100-chapter red case is fixed
    exactly on the pulled text;
  - not yet proven: the full 119-book 100-chapter batch has not been completed
    and manually audited. The algorithm is a stronger production-candidate
    baseline, but it is not yet defensible to call it production-level until the
    expanded batch plus manual red-light audit passes without new misses or
    false positives.

## 2026-05-23 Raw 100-Book Acquisition

- Switched the expanded validation run to a fetch-only raw acquisition pass
  before doing more analyzer replay. This keeps network/source instability out
  of the algorithm loop: first land real text, then audit and replay locally.
- Added a phone harness entry `Run Fetch Top Up 101-112`:
  - the original `Run Batch Fetch Only` still fetches targets `1-100` into
    `fetch-batch-*`;
  - the top-up path fetches targets `101-112` into `fetch-topup-*`;
  - both use `FETCH_BATCH_PARALLELISM = 8` and whole-book chapter download;
  - top-up report folders keep global target numbers, such as `101-灵境行者`,
    so the data can be merged with the first batch without renumbering.
- Validation before installing:
  - `.\gradlew.bat :algorithm-test:testDebugUnitTest --offline --no-daemon --tests com.ldp.reader.algorithmtest.source.BatchNovelTargetsTest`
    passed in 49s;
  - `.\gradlew.bat :app:assembleDebug --offline --no-daemon` passed in 48s;
  - AI App Bridge `install-apk` reinstalled
    `app/build/outputs/apk/debug/app-debug.apk` to device `b46093e6`.
- First phone batch:
  - directory:
    `/storage/emulated/0/Android/data/com.ldp.reader/files/algorithm-test/fetch-batch-1779484863140`;
  - stopped waiting for the last slow items after the user confirmed not to
    block on them;
  - final retained state: `ok=94`, `fail=5`, `incomplete=1`,
    `chapters=129097`, `size=1.4G`;
  - no successful raw data was deleted.
- Top-up phone batch:
  - directory:
    `/storage/emulated/0/Android/data/com.ldp.reader/files/algorithm-test/fetch-topup-1779493474318`;
  - stopped once the combined success count exceeded 100;
  - final retained state: `ok=7`, `fail=0`, `incomplete=5`,
    `chapters=3469`, `size=67M`;
  - successful top-up books:
    `灵境行者` 286 chapters, `第一序列` 328 chapters,
    `大王饶命` 303 chapters, `夜的命名术` 454 chapters,
    `万族之劫` 1038 chapters, `星门` 663 chapters,
    `深空彼岸` 397 chapters.
- Combined raw acquisition proof:
  - retained successful books: `94 + 7 = 101`;
  - retained chapter files: `129097 + 3469 = 132566`;
  - the remaining incomplete folders are stopped mid-run evidence only and are
    not counted as successful samples.
- Next step:
  - build a local manifest from the 101 successful `fetch-report.txt` folders;
  - sample and manually label suspicious tail chapters from this fixed raw
    corpus;
  - only then rerun the no-model Novel State Memory analyzer against this new
    100-book seed.

## 2026-05-23 Raw Replay 1779498601772 Manual Audit

- Froze and pulled the current sampled replay snapshot:
  `algorithm-test/build/raw-corpus-replay-1779498601772`.
- Snapshot size:
  - completed books: `33`;
  - audit rows: `276`;
  - suggestion rows: `72`;
  - books with suggestions: `21`;
  - total suggestions: `83`.
- Manual text inspection confirms the analyzer catches obvious mixed-source
  pollution well. Confirmed true-positive examples include:
  - `玄鉴仙族` chapters `1636`, `1640`;
  - `道爷要飞升` chapter `617`;
  - `从水猴子开始成神` chapter `1521`;
  - `仙工开物` chapter `1034`;
  - `阵问长生` chapter `1481`;
  - `御兽从零分开始` chapter `1687`;
  - `长生从炼丹宗师开始` chapter `1353`;
  - `夜无疆` chapter `726`;
  - `叩问仙道` chapter `2784`;
  - `苟在武道世界成圣` chapter `706`;
  - `苟在两界修仙` chapter `408`;
  - `没钱修什么仙？` chapter `789`;
  - `从赘婿开始建立长生家族` chapter `103`;
  - `旧域怪诞` chapter `227`;
  - `异度旅社` chapter `768`;
  - `以神通之名` chapter `510`.
- The same audit found result-level blockers:
  - false positive: `大道之上` chapter `309` is an author thanks/meta chapter
    and should be `NON_STORY`, not story pollution;
  - false positive: `苟在初圣魔门当人材` chapter `1577` is coherent番外
    content around `司祟/初圣/祖龙/吕阳`; generic cultivation terms such as
    `筑基真人` were over-weighted as alien evidence;
  - false positive: `吞噬星空2起源大陆` chapter `453` remains in the
    `罗峰/元/星芒/渊象殿主` setting;
  - likely false positives: `北宋穿越指南` chapters `596` and `1398` are
    coherent side/late-arc content, not mixed-source garbage.
- The no-suggestion tail audit also found misses:
  - `仙人消失之后` chapters `2923` and `2924` start with JavaScript/page chrome
    and include site chrome/recommendation/footer text, but were not reported;
  - `这个武圣血条太厚`, `剑出衡山`, `太一道果`,
    `法力无边高大仙`, and `混在末日，独自成仙` tail samples are non-story/meta
    content and need a separate `NON_STORY` path.
- Visible root causes:
  - page chrome/recommendations can enter Book Memory; `仙人消失之后` core
    features include `全文免费`, `潇湘书院`, `傅总娇妻`, etc.;
  - entity candidates remain too broad (`平静`, `许久`, `全部`, `施展`,
    `居住`, `安排`, and partial `筑基` variants);
  - sparse sampling can starve late-arc memory, producing high-confidence
    false positives against valid late/side arcs;
  - current output conflates `POLLUTED`, `NON_STORY`, and bad extraction.
- Detailed audit file:
  `algorithm-test/docs/RAW_REPLAY_1779498601772_AUDIT.md`.
- Current conclusion: this replay is useful evidence and catches many real
  mixed-source chapters, but it is not production-grade yet. The next fix should
  start with正文 extraction / page chrome rejection before Book Memory, then
  separate `NON_STORY` and `BAD_EXTRACTION` from story pollution. More threshold
  tuning alone is not defensible.

## 2026-05-23 Target Replay 1779506612162 Manual Audit

- Manually checked two fixed raw-corpus books without re-fetching network data:
  - `book-021`: `仙人消失之后 / 风行水云间`;
  - `book-023`: `旧域怪诞 / 狐尾的笔`.
- `仙人消失之后` result:
  - replay output has `0` pollution suggestions and `22` `BAD_EXTRACTION`
    chapters;
  - manual reading confirms the sampled late chapters are page-shell /
    preview-only extraction failures from the source, not mixed-novel
    pollution;
  - early same-source backfill chapters still contain real story text after
    trimming, so this is not simply "cleaner deleted good text".
- `旧域怪诞` result:
  - all reported suspicious chapters inspected so far are true mixed-source
    pollution at the chapter level;
  - sampled clean/no-suggestion context chapters remain coherent
    `张文达/宋建国/胡毛毛/唐兴雄` story text;
  - however the reported trim boundary is often late: many first foreign
    fragments start around `140-175` chars, while the report commonly starts at
    `267`; `00467-第四百五十一章_拉克夫.txt` is worse, with report start `650`
    but first foreign fragment around `139`.
- Current conclusion:
  - quality separation is now directionally correct: bad extraction should not
    be counted as story pollution;
  - chapter-level pollution detection works for this `旧域怪诞` sample;
  - boundary precision is not yet good enough for "不多不少", so a polluted
    chapter needs a backward/refinement pass before using the deletion offset.
- Detailed audit:
  `algorithm-test/docs/TARGET_REPLAY_1779506612162_MANUAL_AUDIT.md`.
