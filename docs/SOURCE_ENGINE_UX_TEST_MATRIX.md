# Source Engine UX Test Matrix

Date: 2026-05-19

Purpose: verify the user-visible reader experience after switching to
source-engine. The pass must cover search relevance, cover selection, detail
load, shelf identity, catalog ordering, readable content, and bad latest
chapter filtering.

## Acceptance Criteria

- Search: intended book appears first for exact title, common partial title,
  title with book-title marks, alias/current-name query, and author query.
- Result list: first result has the expected title and author; unrelated or
  derivative works do not outrank the intended book.
- Cover: first result/detail/shelf use a real book cover when at least one
  merged source has a usable cover; wrong logo/placeholder covers are not
  selected over a real cover.
- Detail: opens without crash, shows title, author, intro, latest readable
  chapter, and start/follow actions.
- Shelf: adding the same canonical book through different source rows, title
  marks, or aliases creates one shelf item, not duplicates.
- Catalog head: first visible catalog entries are in main-catalog order, not a
  recent-update prefix mistakenly placed before chapter 1.
- Catalog middle: non-chapter announcements such as update plans, leave notes,
  new-book notices, and author notes are filtered when they are not ordinal
  chapters.
- Catalog tail: latest polluted chapters are hidden when content quality or
  coherence fails. The last visible chapter must be readable.
- Reading: first chapter, a middle chapter, and last visible chapter can render
  body text without crash, blank page, or obvious unrelated-book tail.
- Recovery: historical saved reading position beyond a newly trimmed catalog is
  clamped to the last readable chapter.

## Search Coverage Set

| ID | Query | Expected Top Result | Author | Bucket | Checks |
| --- | --- | --- | --- | --- | --- |
| S01 | 斗破 | 斗破苍穹 | 天蚕土豆 | 起点 完结 热门 partial | rank, cover |
| S02 | 《斗破苍穹》 | 斗破苍穹 | 天蚕土豆 | title marks | rank, dedupe |
| S03 | 天蚕土豆 | 斗破苍穹 or another 天蚕土豆 book | 天蚕土豆 | author | author relevance |
| S04 | 诡秘 | 诡秘之主 | 爱潜水的乌贼 | 起点 完结 热门 partial | rank, cover |
| S05 | 《诡秘之主》 | 诡秘之主 | 爱潜水的乌贼 | title marks | rank, dedupe |
| S06 | 爱潜水的乌贼 | 诡秘之主 or 宿命之环 | 爱潜水的乌贼 | author | author relevance |
| S07 | 凡人 | 凡人修仙传 | 忘语 | 起点 完结 热门 partial | rank |
| S08 | 忘语 | 凡人修仙传 or 玄界之门 | 忘语 | author | author relevance |
| S09 | 遮天 | 遮天 | 辰东 | 起点 完结 热门 exact | rank |
| S10 | 完美世界 | 完美世界 | 辰东 | 起点 完结 热门 exact | rank |
| S11 | 牧神记 | 牧神记 | 宅猪 | 起点 完结 热门 exact | rank |
| S12 | 大奉 | 大奉打更人 | 卖报小郎君 | 起点 完结 热门 partial | rank |
| S13 | 剑来 | 剑来 | 烽火戏诸侯 | 纵横 长篇 热门 | rank |
| S14 | 雪中 | 雪中悍刀行 | 烽火戏诸侯 | 纵横 完结 热门 partial | rank |
| S15 | 庆余年 | 庆余年 | 猫腻 | 起点 完结 热门 exact | rank |
| S16 | 将夜 | 将夜 | 猫腻 | 起点 完结 热门 exact | rank |
| S17 | 全职高手 | 全职高手 | 蝴蝶蓝 | 起点 完结 热门 exact | rank |
| S18 | 盗墓笔记 | 盗墓笔记 | 南派三叔 | 出版/网文 热门 | rank |
| S19 | 鬼吹灯 | 鬼吹灯 | 天下霸唱 | 出版/网文 热门 | rank |
| S20 | 斗罗大陆 | 斗罗大陆 | 唐家三少 | 起点 完结 热门 exact | rank |
| S21 | 神印王座 | 神印王座 | 唐家三少 | 起点 完结 热门 exact | rank |
| S22 | 星辰变 | 星辰变 | 我吃西红柿 | 起点 完结 热门 exact | rank |
| S23 | 吞噬星空 | 吞噬星空 | 我吃西红柿 | 起点 完结 热门 exact | rank |
| S24 | 仙逆 | 仙逆 | 耳根 | 起点 完结 热门 exact | rank |
| S25 | 求魔 | 求魔 | 耳根 | 起点 完结 热门 exact | rank |
| S26 | 一念永恒 | 一念永恒 | 耳根 | 起点 完结 热门 exact | rank |
| S27 | 圣墟 | 圣墟 | 辰东 | 起点 完结 热门 exact | rank |
| S28 | 宿命之环 | 宿命之环 | 爱潜水的乌贼 | 起点 近年 | rank, tail |
| S29 | 万相之王 | 万相之王 | 天蚕土豆 | 纵横/连载 | rank, tail |
| S30 | 夜无疆 | 夜无疆 | 辰东 | 起点 连载 | rank, tail |
| S31 | 光阴之外 | 光阴之外 | 耳根 | 起点 近年 | rank, tail |
| S32 | 谁让他修仙的 | 谁让他修仙的 | 最白的乌鸦 | 起点 连载 | rank, tail |
| S33 | 仙工开物 | 仙工开物 | 蛊真人 | 起点 连载 | rank, tail |
| S34 | 高武纪元 | 高武纪元 | 烽仙 | 起点 连载 | rank, tail |
| S35 | 大道之上 | 大道之上 | 宅猪 | 起点 连载 | rank, tail |
| S36 | 赤心巡天 | 赤心巡天 | 情何以甚 | 纵横 完结/长篇 | rank, tail |
| S37 | 玄鉴仙族 | 玄鉴仙族 | 季越人 | 起点 连载 | rank, polluted tail |
| S38 | 叩问仙道 | 叩问仙道 | 雨打青石 | 起点/仙侠 连载 | rank, polluted tail |
| S39 | 我在修仙界万古长青 | 我在修仙界万古长青 | 快餐店 | 起点/仙侠 | rank, polluted tail |
| S40 | 灵源仙路 | 灵源仙途：我养的灵兽太懂感恩了 | 春雾煮茶 | alias/current title | alias rank |
| S41 | 十日终焉 | 十日终焉 | 杀虫队队员 | 番茄 热门 | rank, cover |
| S42 | 我不是戏神 | 我不是戏神 | 三九音域 | 番茄 热门 | rank, cover |
| S43 | 我在精神病院学斩神 | 我在精神病院学斩神 | 三九音域 | 番茄 热门 | rank |
| S44 | 斩神 | 我在精神病院学斩神 | 三九音域 | 番茄 partial | rank |
| S45 | 第九特区 | 第九特区 | 伪戒 | 番茄/都市 | rank |
| S46 | 灵境行者 | 灵境行者 | 卖报小郎君 | 起点 完结/近年 | rank |
| S47 | 长生从炼丹宗师开始 | 长生从炼丹宗师开始 | 雨去欲续 | 中等知名 | rank, tail |
| S48 | 苟在妖武乱世修仙 | 苟在妖武乱世修仙 | 文抄公 | 中等知名 | rank, tail |

## Full Journey Set

Each full journey case must execute:

1. Search query from a clean SearchActivity state.
2. Verify first result title, author, and visible cover state.
3. Open detail and verify title, author, cover, latest readable chapter, intro,
   and action buttons.
4. Tap follow/add; return to shelf; verify one canonical shelf item and cover.
5. Open reader from shelf or detail; verify body text renders.
6. Open catalog; verify chapter 1 order near head.
7. Jump or scroll to tail; verify last visible chapter and absence of known bad
   tail/announcement entries.
8. For selected cases, tap last visible chapter and verify body text renders.

| ID | Book | Query | Why This Case |
| --- | --- | --- | --- |
| F01 | 斗破苍穹 | 斗破 | classic completed, cover merge, partial ranking |
| F02 | 诡秘之主 | 《诡秘之主》 | title marks, duplicate shelf identity |
| F03 | 凡人修仙传 | 凡人 | classic long catalog |
| F04 | 剑来 | 剑来 | non-Qidian long catalog, high chapter count |
| F05 | 十日终焉 | 十日终焉 | Fanqie popular title |
| F06 | 我不是戏神 | 我不是戏神 | Fanqie popular title, same author family as 斩神 |
| F07 | 叩问仙道 | 叩问仙道 | known bad latest chapter and clamp recovery |
| F08 | 我在修仙界万古长青 | 我在修仙界万古长青 | known 200-300 char correct prefix then foreign tail |
| F09 | 玄鉴仙族 | 玄鉴仙族 | live probe polluted latest chapter risk |
| F10 | 灵源仙路 | 灵源仙路 | alias/current-title search |
| F11 | 谁让他修仙的 | 谁让他修仙的 | ongoing source freshness and tail filtering |
| F12 | 长生从炼丹宗师开始 | 长生从炼丹宗师开始 | mid-popularity source coverage |

## Current Run Log

Results are appended below during execution. Screenshots and raw MCP evidence
are stored under `build/` with `ai-bridge-*` names.

### 2026-05-19 MCP Run

- Search S01-S48: passed after the alias/ranking fix. Important repaired
  cases:
  - S40 `灵源仙路` returns `灵源仙途：我养的灵兽太懂感恩了 / 春雾煮茶`
    first.
  - S44 `斩神` returns `我在精神病院学斩神 / 三九音域` first.
  - S35-S48 were rerun after the S44 fix; all expected first results passed.
- Shelf identity and covers: passed after multi-source cover fallback and
  no-cover marker filtering. MCP shelf sweep showed:
  - `诡秘之主` appears once, not three times.
  - `斗破苍穹` appears once with a real cover.
  - `玄鉴仙族` uses a real source cover instead of the default loading icon.
- Full journey F01-F12: passed for search -> detail -> follow/read -> catalog
  head. Key results:
  - F01 `斗破`: first/detail `斗破苍穹 / 天蚕土豆`, catalog starts at
    `第一章 陨落的天才`.
  - F02 `《诡秘之主》`: title marks resolve to the existing canonical shelf
    item, catalog starts at `第一章 绯红`.
  - F03 `凡人`: repaired malformed volume-prefixed tail titles; tail ends at
    `第两千四百四十六章 飞升仙界〔大结局）`.
  - F04 `剑来`: catalog starts at `第一章 惊蛰`.
  - F05 `十日终焉`: catalog starts at `第1章 空屋`.
  - F06 `我不是戏神`: catalog starts at `第1章 戏鬼回家`.
  - F09 `玄鉴仙族`: initial pass was later falsified by reading-path testing;
    `第1491章` through the latest chapters contained unreadable or polluted
    content and are covered by the follow-up notes below.
  - F10 `灵源仙路`: alias journey opens
    `灵源仙途：我养的灵兽太懂感恩了 / 春雾煮茶`.
  - F11 `谁让他修仙的`: ordinal chapter titles containing thanks text remain
    visible because they are real chapters.
  - F12 `长生从炼丹宗师开始`: catalog starts at `第一章 散修罗尘`.
- Content-tail pollution:
  - Reproduced a real missed case for F07 `叩问仙道`: chapter
    `第二千六百九十章 宇宙洪荒，混沌星辰` opened with a correct-looking
    prefix, then page 2 switched to unrelated `王府/黎筱雨/薇娅` content.
  - Added deterministic `coherent-foreign-tail-after-valid-prefix` detection
    through `ContentBelongingChecker`, keeping the checker replaceable by a
    later local-model implementation.
  - Retested by MCP after install: the first pass still allowed
    `第二千六百八十九章 凋零`, but page-forward verification showed the chapter
    switched into unrelated `许州城/龙炎/圣龙尊者/宋依依/夏侯策` content.

### 2026-05-19 MCP Follow-Up: Cache And Tail Holes

- Confirmed the user report was not only stale cache:
  - Old `.nb` cache files could bypass newer content checks, so source-engine
    reading cache now carries `.source_engine_content_cache_version` and bumps
    to `source-engine-content-v3`.
  - New source fetches also still needed stricter detection for short correct
    prefixes followed by unrelated multi-book fragments.
- Added deterministic short-prefix foreign-tail detection for chapters where
  only the opening 200-300 characters are correct and later text comes from
  unrelated books. Regression fixtures cover:
  - `叩问仙道` `第二千六百八十八章 虫魔噬界`, which mixed in `国足/迈巴赫/胡八一/王胖子/黄毛`.
  - `玄鉴仙族` tail content, which mixed in `芝加哥/GMC/俱乐部/影院/韩冲/周钊`.
- Added tail-window catalog probing before the exponential/binary boundary
  search. This catches a tail with holes, where the latest chapter may appear
  readable but an earlier chapter in the last few chapters is unreadable.
- MCP evidence after reinstall:
  - `叩问仙道`: cache v3 invalidates old files; catalog is trimmed from 2718
    raw chapters to 2713 kept chapters, ending at
    `第二千六百八十六章 道境`; `2687-2691` are hidden. The cached `2686`
    body was scanned and did not contain the previously observed foreign
    fragments.
  - `玄鉴仙族`: catalog is trimmed from 1543 raw chapters to 1536 kept
    chapters, ending at `第1490章 丹尸（1+1/2）（Raincheck白银盟主加更`;
    `第1491章` through `第1497章` are hidden. Opening the last visible chapter
    renders body text instead of the prior loading-failure page, and cached
    body files do not contain the previous `芝加哥/GMC/韩冲/周钊` fragments.
- AI Bridge MCP notes: no confirmed MCP defect was found in this run. The only
  interruptions were environmental foreground/installer/USB dialogs, and one
  tool-parameter misuse (`swipe` requires `startX/startY/endX/endY`), so no AI
  Bridge documentation bug note was added.
