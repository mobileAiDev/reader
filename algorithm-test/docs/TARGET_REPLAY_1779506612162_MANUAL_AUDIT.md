# Target Replay 1779506612162 Manual Audit

Date: 2026-05-23

Scope:

- Raw corpus only; no network re-fetch.
- Corpus root:
  `algorithm-test/build/raw-corpus-101/tmp-replay-existing`.
- Replay output:
  `algorithm-test/algorithm-test/build/raw-corpus-target-replay-1779506612162`.
- Books manually checked:
  - `book-021`: 仙人消失之后 / 风行水云间
  - `book-023`: 旧域怪诞 / 狐尾的笔

## Summary

| Book | Replay result | Manual judgement |
| --- | --- | --- |
| 仙人消失之后 | 0 pollution suggestions; 22 `BAD_EXTRACTION`; 8 trimmed memory chapters | Correct as story-pollution output. The sampled late chapters are bad page extraction / site shell, not clean story and not mixed-novel pollution. |
| 旧域怪诞 | 12 pollution suggestions, 9 of them target chapters | Chapter-level pollution detection is correct. Boundary offsets are not precise enough: most polluted suffixes start around 140-175 chars, but the report often starts at 267, and one chapter starts at 650. |

## 仙人消失之后

Replay report:

- Analyzed chapters: `8`
- Chunks: `34`
- Quality: `clean=0`, `trimmed=8`, `nonStory=0`, `badExtraction=22`, `uncertain=0`
- Suggestions: `0`

Manual checks:

- Early memory backfill chapters such as `00011-第15章_盘龙往事.txt`,
  `00022-第24章_位卑者慎.txt`, and
  `00077-第79章_半个月的风起云涌.txt` contain real story text in the middle
  after page shell is trimmed. Examples include stable in-book entities:
  `贺灵川`, `贺淳华`, `盘龙城`, `黑水城`.
- Late target chapters such as:
  - `02825-第2770章_入场.txt`
  - `02861-第2806章_地母的思考.txt`
  - `02893-第2838章_让人恐惧的事实.txt`
  - `02909-第2854章_站在命运的悬崖上.txt`
  - `02917-第2862章_超能之战.txt`
  - `02921-第2866章_天外飞斧.txt`
  - `02923-第2868章_第二计划启动.txt`
  - `02924-第2869章_被堵死的蛇口.txt`
  start and end with JavaScript/page chrome. Their story body is only a short
  preview followed by subscription prompts, recommendations, directory/footer,
  and JS.

Judgement:

- This source is not a valid full-text story source for the sampled late
  chapters.
- The analyzer should not call these chapters story pollution; the current
  `BAD_EXTRACTION` path is the right layer.
- This also confirms the quality gate did not simply delete all good text: the
  early backfill chapters still yielded clean story after trimming.

## 旧域怪诞

Replay report:

- Analyzed chapters: `18`
- Chunks: `71`
- Quality: `clean=17`, `trimmed=1`, `badExtraction=0`,
  `uncertain=1`
- Suggestions: `12`
- Target suggestions: `227,383,419,451,467,475,479,481,482`
- Context suggestions: `296,381,382`

Manual checks on reported chapters:

| Raw file index | Report start | Manual first clear foreign text | Judgement |
| --- | ---: | ---: | --- |
| `00227-今天可能会有点晚.txt` | 0 | 39 | True mixed extraction / polluted run. Only the first short sentence looks plausibly in-book; the rest is many unrelated novels. |
| `00296-第二百九十二章_拉克夫.txt` | 267 | 145 | True suffix pollution. Prefix is 张文达/拉克夫, then jumps to 黄源/血鬼谷/剑魔传人/叶浩 etc. |
| `00381-第三百七十一章_红色.txt` | 267 | 153 | True suffix pollution. Prefix is 张文达/红色/动物园, then jumps to 巧儿/赵亮/查酒驾/萧奉铭 etc. |
| `00382-第三百七十二章_寓言.txt` | 267 | 148 | True suffix pollution. Prefix is 张文达/胡毛毛/红色蜡笔, then jumps to 厉津衍/皇陵/天血魔尊 etc. |
| `00383-第三百七十三章_黄色.txt` | 267 | 142 | True suffix pollution. Prefix is 张文达/胡毛毛, then jumps to 韩炳/秦横天/海王殿 etc. |
| `00419-第四百零八章_穿越.txt` | 267 | 142 | True suffix pollution. Prefix is 张文达/胡毛毛/1999, then jumps to 南宫亦儿/墨宸/杨炎 etc. |
| `00451-第四百三十七章_抵达.txt` | 0 | 145 | True mixed extraction / polluted run. The first short prefix may be in-book, but the chapter body is mostly unrelated fragments. |
| `00467-第四百五十一章_拉克夫.txt` | 650 | 139 | True suffix pollution, but reported start is too late. Prefix is 张文达/拉克夫, then jumps to 齐桦/solo/阿九/李源鸣 etc. |
| `00475-第四百五十九章_数学.txt` | 267 | 175 | True suffix pollution. Prefix is 张文达/数学课/欧阳老师, then jumps to 棠渔/陈余浩/时雨 etc. |
| `00479-第四百六十三章_故人.txt` | 267 | 150 | True suffix pollution. Prefix is 张文达 trapped in corridor, then jumps to 林萧/段东风/月初 etc. |
| `00481-第四百六十五章_狂飙3号.txt` | 267 | 148 | True suffix pollution. Prefix is 张文达/狂飙3号, then jumps to unrelated burned-face/黑暴猿/李成业 etc. |
| `00482-第四百六十六章_绝境.txt` | 267 | 173 | True suffix pollution. Prefix is 张文达/羽蛇神/月神/拉克夫, then jumps to 何十一/龙牙公司/龙域 etc. |

Manual checks on clean/no-suggestion context chapters:

- `00000-第一章_张文达.txt`
- `00083-第八十二章_老舅.txt`
- `00125-第一百二十四章_前进.txt`
- `00168-第一百六十六章_大人世界.txt`
- `00211-第二百零九章_地下.txt`
- `00253-第二百五十章_攻击方式.txt`

These sampled no-suggestion chapters are coherent 张文达/宋建国/胡毛毛/唐兴雄 story text and do not show the multi-novel fragment pattern found in the reported chapters.

Judgement:

- The chapter-level `旧域怪诞` suggestions are true positives.
- The detector still fails the stricter "no more, no less" boundary requirement.
  It is often detecting the polluted suffix after the first contaminated
  sentence instead of at the real first foreign fragment.
- `00467` is the clearest boundary bug: report starts at `650`, but the first
  foreign fragment starts around `139`, leaving a large polluted middle segment
  undeleted if the offset is used directly.

## Required Follow-up

Before calling this production-grade:

1. Keep the quality gate separation: `BAD_EXTRACTION` is not story pollution.
2. Add a boundary refinement pass after a chapter is classified polluted:
   scan backward from the detected chunk boundary to the earliest local
   low-membership / alien-fragment transition.
3. Distinguish "discard whole mixed extraction chapter" from "surgical suffix
   trim":
   - if the clean prefix is only tens of characters, whole-chapter discard is
     acceptable;
   - if the clean prefix is a meaningful story prefix, the start offset must be
     precise enough not to leave pollution behind.
