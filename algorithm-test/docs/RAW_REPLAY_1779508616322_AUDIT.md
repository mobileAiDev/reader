# Raw Corpus Replay 1779508616322 Audit

## Run Summary

- Device output: `/storage/emulated/0/Android/data/com.ldp.reader/files/algorithm-test/raw-corpus-replay-1779508616322`
- Local copy: `C:/project/reader/algorithm-test/build/phone-reports/raw-corpus-replay-1779508616322`
- Replay corpus roots: `fetch-batch-1779484863140`, `fetch-topup-1779493474318`
- Items: 101
- OK: 101
- Fail: 0
- Suggestion books: 46
- Suggestion chapters: 144
- Audit rows: 770
- Suggestion audit rows: 128
- No-suggestion tail audit rows: 440
- Parallelism: 5
- Device elapsed: about 746 seconds from first START to FINISH

## Performance Findings

The run is not I/O bound. `listMs`, `sampleMs`, and `readMs` are small for most books. Runtime is dominated by `analyzeMs`, especially high-chunk samples.

Slowest samples:

| bookNo | title | chunks | analyzeMs | result |
| --- | --- | ---: | ---: | --- |
| 57 | 神农道君 | 284 | 146046 | no suggestions |
| 107 | 星门 | 313 | 144815 | no suggestions |
| 43 | 执魔 | 263 | 117650 | no suggestions |
| 106 | 万族之劫 | 280 | 109364 | no suggestions |
| 81 | 赤心巡天 | 242 | 99436 | no suggestions |

## Manual Audit Pass 1

This pass manually read extracted real chapter text, not only algorithm logs.

| bookNo | title | chapter(s) checked | algorithm result | manual result | note |
| --- | --- | --- | --- | --- | --- |
| 22 | 从赘婿开始建立长生家族 | 103 | POLLUTED_RUN | TRUE_POSITIVE | Full chapter is a mosaic of unrelated names/scenes such as 叶梵天, 洛尧擢, 朱由检, 星辉/都市/玄幻 fragments. |
| 23 | 旧域怪诞 | 227, 481 | POLLUTED_RUN | TRUE_POSITIVE | Chapter body is random unrelated story fragments; 481 has normal prefix then unrelated suffix. |
| 3 | 道爷要飞升 | 617 | POLLUTED_RUN | TRUE_POSITIVE | Random unrelated fragments after no stable book identity. |
| 17 | 苟在武道世界成圣 | 706 | POLLUTED_RUN | TRUE_POSITIVE | Normal prefix followed by unrelated modern/game/fantasy fragments. |
| 18 | 苟在两界修仙 | 408 | POLLUTED_RUN | TRUE_POSITIVE | Normal prefix followed by unrelated multi-novel fragments. |
| 15 | 夜无疆 | 726 | POLLUTED_SUFFIX | TRUE_POSITIVE | Normal prefix followed by unrelated palace/modern/game/fantasy fragments. |
| 16 | 叩问仙道 | 2784 | POLLUTED_RUN | TRUE_POSITIVE | Normal prefix followed by unrelated fragments. |
| 24 | 异度旅社 | 768 | POLLUTED_RUN | TRUE_POSITIVE | Normal prefix followed by unrelated fragments. |
| 6 | 仙工开物 | 1034 | POLLUTED_RUN | TRUE_POSITIVE | Normal prefix followed by unrelated fragments. |
| 7 | 阵问长生 | 1495 | POLLUTED_SUFFIX | TRUE_POSITIVE | Normal prefix followed by unrelated fragments. |
| 49 | 大不列颠之影 | 149 | POLLUTED_RUN | NON_STORY_TRUE | This is a book recommendation chapter, not story pollution, but should not enter clean story catalog/content. Type is wrong; action to hide is still correct. |
| 44 | 盖世双谐 | 662, 663, 664 | POLLUTED_RUN on 663 | FALSE_POSITIVE | 662/663/664 are coherent same-arc normal story around 孙黄/顾其影/淳空/毓秀山庄. |
| 89 | 奥术神座 | 648, 1717 | POLLUTED_RUN | FALSE_POSITIVE | Both are normal book content involving 路西恩/奥术审核 and 奥希里德/巫王支线. |
| 36 | 北宋穿越指南 | 596, 1398 | POLLUTED_RUN | MIXED | 596 is normal 北宋穿越指南 content and a false positive. 1398 appears to be a different story/source contamination. |
| 11 | 苟在初圣魔门当人材 | 1577 | POLLUTED_RUN | LIKELY_FALSE_POSITIVE | 番外十一 is coherent with 司祟/初圣/光海/筑基境 world terms; needs neighbor check, but current read looks like valid番外. |
| 104 | 夜的命名术 | 1642 | POLLUTED_RUN | FALSE_POSITIVE | Normal 夜的命名术 content around 庆尘/禁忌之地/旋转木马. |
| 1 | 我在修仙界万古长青 | 542 | NO_SUGGEST_TAIL_CHECK | TRUE_NEGATIVE | Tail sample is coherent normal story. |
| 57 | 神农道君 | 711 | NO_SUGGEST_TAIL_CHECK | TRUE_NEGATIVE | Tail sample is coherent normal story. |

## Current Accuracy Read

The detector catches obvious mixed-fragment pollution well, especially when a normal prefix is followed by unrelated names/scenes. However, the replay is not close enough to production quality yet because the first audit pass found clear false positives in normal side arcs,番外, and legitimate old-book subplots.

Known false-positive pattern:

- Book Memory is built from a small sampled context, so legitimate remote arcs or番外 can have low entity/prototype coverage.
- The current judge then treats coherent but unseen entity clusters as alien clusters.
- `futureIntegration=0` is often caused by sampled future context not containing the integration path, not by the chapter truly failing to integrate.
- Some non-story recommendation chapters are still typed as `POLLUTED_RUN` instead of `NON_STORY`; action can be acceptable, but metrics and downstream handling need the type separated.

## Next Metric Split

Do not report one flat precision number yet. Track at least:

- `story_pollution_precision`: suggestions that are actual contaminated story text.
- `non_story_detection`: recommendations/author notes/page shell that should be hidden but should not count as story pollution.
- `false_positive_story`: normal story/番外/side arc incorrectly suggested.
- `true_negative_tail`: sampled no-suggestion tail chapters that are manually clean.
- `boundary_quality`: true pollution but start/end offset late, early, or exact.

## Immediate Design Implication

Parameter tuning alone is risky. The false positives are structural: legitimate arcs are outside the sampled Book Memory. The next change should focus on memory/prototype coverage and decision layering, not only threshold changes.
