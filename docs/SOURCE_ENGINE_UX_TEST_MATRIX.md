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
| S44 | 斩神 | 斩神 | 天刈留香 | exact-title over derivative | rank |
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
  - S44 `斩神` previously returned `我在精神病院学斩神 / 三九音域`;
    the current generic exact-title rule expects `斩神 / 天刈留香` first.
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
  - Later fingerprint and short-prefix pollution detection changes bump this
    again to `source-engine-content-v4`, so real-device validation cannot reuse
    stale v3 chapter bodies.
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

### 2026-05-20 Real Device 100-Book Run

- Scope: start a full 100-book real-device validation using the MCP JSON-RPC
  `tools/call` path, not direct device CLI calls.
- Pre-run fixes verified on OnePlus PKR110:
  - Android 16 regex crash in `LegadoRuleEvaluator` fixed.
  - Empty search/no-request symptom traced to the source-engine init crash.
  - Detail fallback now continues the waterfall when a direct result has a raw
    catalog but not enough readable chapters.
  - Same-title fallback no longer relies on a preset known-book list; it keeps
    same-title candidates and ranks them by author consensus and source/book
    quality.
- Targeted checks before full 100:
  - #86 `何以笙箫默`: PASS, search -> detail -> read -> catalog.
  - #88 `后宫甄嬛传`: PASS, search -> detail -> read -> catalog.
  - #87 `步步惊心`: PASS for technical read/catalog after fallback; semantic
    author disambiguation remains unresolved because the input only supplies
    the title.
  - #79 `长安的荔枝`: reader opened successfully; catalog validation is still
    marked for full-run confirmation.
- Full-run evidence will be written to
  `build/device-100-real/device-100-real-results.json`,
  `build/device-100-real/device-100-real-results.tsv`, and matching
  screenshots under `build/device-100-real/screenshots/`.
- Harness correction: an unset `READER_DEVICE100_ONLY` must mean "all books";
  blank tokens are now ignored before parsing indices. The earlier instant
  full-run exit only rewrote old results and was not accepted as evidence.
- Live progress: #1-#10 passed on OnePlus PKR110 through the MCP JSON-RPC
  path, with no WARN/FAIL.
- Mid-run regression and repair:
  - #15 `剑来` first failed after search selected an early strong-catalog source
    whose detail tail later trimmed to zero readable chapters.
  - The generic fix is to avoid returning from a same-title validation group
    after only the first strong candidate; later same-title candidates can now
    complete and re-rank the group.
  - Targeted MCP rerun for #15 passed: first/detail `剑来 / 烽火戏诸侯`, 1220
    readable chapters, catalog starts at `第一章 惊蛰`.
- After reinstalling the repaired APK, the full 100-book run restarted. New
  APK progress: #1-#10 PASS, 0 WARN, 0 FAIL.
- New APK progress: #1-#20 PASS, 0 WARN, 0 FAIL. #15 `剑来` passed again
  inside the full run.
- #23 `牧神记` exposed that 3 completed same-title candidates were still not
  enough when the best source completed fifth. The gate now waits for 5
  completed candidates before early return. Targeted MCP rerun passed with
  `52书库.net`, 3210 readable chapters, and catalog `第1页` onward.
- Final APK full-run restart: #1-#10 PASS, 0 WARN, 0 FAIL. Treat this run,
  not the interrupted intermediate runs, as the current official 100-book
  evidence set.
- Final APK progress: #1-#20 PASS, 0 WARN, 0 FAIL.
- #22 `圣墟` targeted rerun after catalog-head scoring passed. The resolved
  catalog now starts at `第一章 沙漠中的彼岸花` instead of `第二章`.
- Current official full run after the catalog-head fix: #1-#10 PASS, 0 WARN,
  0 FAIL.
- Current official full run after the catalog-head fix: #1-#20 PASS, 0 WARN,
  0 FAIL.
- Current official full run after the catalog-head fix: #1-#30 PASS, 0 WARN,
  0 FAIL.
- #34 `雪中悍刀行` targeted rerun after generic short-prefix search expansion
  passed. The selected source was `52书库.net` with 2302 chapters and catalog
  head `第1页`.
- Current official full run after short-prefix search expansion: #1-#10 PASS,
  0 WARN, 0 FAIL.
- Current official full run after short-prefix search expansion: #1-#20 PASS,
  0 WARN, 0 FAIL.
- Current official full run after short-prefix search expansion: #1-#30 PASS,
  0 WARN, 0 FAIL.
- Current official full run after short-prefix search expansion: #1-#40 PASS,
  0 WARN, 0 FAIL.
- Current official full run after short-prefix search expansion: #1-#50 PASS,
  0 WARN, 0 FAIL.
- Current official full run after short-prefix search expansion: #1-#60 PASS,
  0 WARN, 0 FAIL.
- Follow-up design item: add strict per-book character/environment fingerprints
  to content belonging checks. Fingerprints must be learned from trusted early
  and verified middle chapters only; final chapters are excluded, and final
  dozens of chapters are near-untrusted for learning.
- Runtime tuning: source-engine search now fans out up to 64 concurrent source
  jobs, with detail fallback search/probe concurrency raised to 48/32 and
  content/cover fallback probes raised to 16. Source fetch deadlines were
  revised after real source latency checks: all true network request timeouts
  must be at least 10s. Search fetches are now 10s/15s, normal source fetches
  10s/20s, detail probes 10s/15s, and content/tail/fingerprint probes use
  10-15s-class item budgets with longer total windows. Scoped OkHttp call
  cancellation was added so leaving search, detail, or reading pages still
  releases in-flight source requests quickly.
- Validation requirement: the next MCP JSON-RPC device run must confirm that
  the higher concurrency plus more patient request budgets preserve waterfall
  speed while improving final reading success on slow-but-good sites.
- Content fingerprint checkpoint: per-book character/environment fingerprints
  are now part of content belonging checks. The learning set excludes final
  chapters and the final dozens of chapters, then samples trusted early/middle
  chapters. Detection uses bounded candidate breakpoints from unpunctuated
  hard line-breaks, punctuation-density shifts, paragraph-shape shifts, and
  sampled paragraph/window boundaries; unpunctuated breaks in the high-risk
  offset band are weighted highest.
- Adaptive fingerprint checkpoint: the per-book fingerprint is now a bounded
  mutable profile. Real readable chapters that pass quality/coherence checks
  update the same profile, while tail chapters remain excluded from learning.
  Device validation must watch both false negatives on polluted tails and false
  positives on normal cross-scene chapters before older heuristic checks are
  reduced further.
- `青山` device regression checkpoint: `672、取剑` from `55读书` is a confirmed
  polluted source chapter, not a display-only issue. The raw source hard-breaks
  after a valid opening line without punctuation, then appends unrelated
  fragments such as `炎黄族`, `柳琴心`, `韩铁方`, `江离`, `唐云/罗德尼`, and
  `八色雷电`. The expected UX after the fix is that this candidate is rejected
  by content quality and waterfall falls through to another readable source or
  avoids displaying the polluted chapter as successful reading.

### 2026-05-21 Pause: Dedicated Tier First Display Contract

This is the pause point for the tier redesign. It records what must be verified
after resuming; it is not proof that the new behavior has already passed.

- Search/detail/reading use the same source-engine job shape: keep filling the
  current book's dedicated trusted tier while the page is alive.
- Search and detail use memory-only tiers. They can display once two trusted
  sources are available, then continue filling the tier in the background.
- Reading can display the current chapter once two distinct trusted current
  chapter bodies pass fingerprint and quality checks. Current chapter loading
  has priority; neighbor/prefetch work is lower priority and bounded.
- Reading is the only path that persists the tier. Search spam must not create
  `.source_engine_content_tier` files for every queried book.
- The cover requirement is at least one usable cover from a trusted source.
  The other trusted sources do not need covers.
- A catalog being trusted means the post-trim catalog remains normal and usable,
  not that every raw source catalog row was accepted.

Resume validation must check:

1. Cached chapter body exists but UI is in `STATUS_ERROR`: opening/reopening the
   chapter must recover to loading or rendered text, not stay permanently
   failed.
2. Current chapter waterfall: if the first source fails fingerprint/quality,
   later candidates are tried until two trusted current-chapter bodies exist or
   the job is canceled.
3. Catalog: chapters that do not survive fingerprint/tail trimming must not be
   clickable in the visible catalog.
4. Persistence: only actual reading writes `.source_engine_content_tier`; search
   and detail must leave the tier memory-only.
5. Cancellation/resume: leaving search/detail/reader cancels in-flight work;
   reopening can continue from the in-memory or persisted tier state without
   reusing a stale error state.
6. Manual text check: for the first one or two books, read the last visible
   chapter and neighboring final chapters directly. Confirm no polluted chapter
   is kept and no correct chapter is removed, then expand to five books and ten
   books.

Current evidence:

- `.\gradlew.bat :app:compileDebugKotlin` passed after the latest tier edits.
- Targeted unit tests still fail because several tests encode the old
  one-trusted-source contract and the old synchronous provider behavior.
- No APK install, device run, catalog-tail readthrough, or manual last-chapter
  comparison has been completed after this redesign.

### 2026-05-21 Addendum: Search To Detail To Reading Reuse

This addendum updates the UX contract for page transitions. The product should
reuse validated source-engine evidence instead of restarting the same work on
each page.

Expected search behavior:

- A visible result requires two trusted sources for the selected title/author
  group.
- Both trusted sources must have matching normalized author, non-empty intro,
  usable trimmed catalog, and body evidence that passes fingerprint/quality.
- At least one trusted source must have a usable cover.
- Search is a single progressive waterfall. It publishes visible batches as
  soon as two-source trusted groups mature, then continues source discovery and
  publishes later batches instead of stopping on the first strong group.
- Search-owned work is still bounded: 3 minutes max, 30 visible groups max, and
  expensive validation only for the top 30 multi-source groups.
- Starting a new search or leaving the search page cancels search-owned network
  calls.

Expected detail behavior:

- Tapping a search result transfers the already validated book session to
  detail.
- Detail should render immediately from the transferred title, author, intro,
  cover, trusted catalog, and source evidence when those fields exist.
- Detail cancels search-owned requests, then continues its own bounded tier
  fill for the selected book.
- Detail still keeps the tier memory-only.

Expected reading behavior:

- Opening reading transfers the selected book session from detail and cancels
  detail-owned requests.
- Reading persists the dedicated tier and loads chapters from that tier first.
- Reading is instant only when the exact opening chapter already has two
  trusted cached bodies. Otherwise it should show loading while fetching the
  exact chapter through the trusted tier.
- If trusted tier routes fail for a chapter, reading continues the waterfall to
  add more verified tier routes instead of getting stuck in `STATUS_ERROR`.

Validation cases to add after implementation:

1. Search a book, wait until it is visible, open detail, and confirm no second
   full search/detail waterfall is required before title, author, intro, cover,
   and catalog appear.
2. Inspect logs/network traces while leaving search for detail. Search-owned
   calls must be canceled; detail-owned tier fill may continue.
3. Open reading from detail. If the first/opening chapter was preloaded, verify
   immediate render from two trusted cached bodies. If it was not preloaded,
   verify loading then render, not permanent error.
4. Confirm search/detail do not write `.source_engine_content_tier`; reading
   does write it for the selected book.
5. Run the same flow for a same-title query where multiple authors exist. The
   chosen result must keep its own session and not merge into another author.
