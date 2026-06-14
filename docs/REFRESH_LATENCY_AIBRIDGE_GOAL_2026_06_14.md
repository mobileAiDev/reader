# AI Bridge Refresh Latency Goal - 2026-06-14

## Boundary

- Do not change application code or product behavior in this pass.
- Use AI App Bridge as the primary device evidence tool.
- Keep the app frozen while inspecting and planning, thaw only for capture/action, and thaw before handoff.
- Produce documentation only: this goal document and a progress/audit document.
- Separate user-facing evidence from debug-only probes. `SourceEngineActivity` and `MediaProbeActivity` are internal test panels, not normal user entry points.

## Main Objective

Audit why catalog/latest-chapter refresh feels slow in normal reading, especially when new chapters already exist but the bookshelf, reading page, or catalog drawer still show old chapters for minutes.

The audit must cover:

- Novels: at least 10 sampled books.
- Comics: search/detail/catalog/chapter flow and refresh/caching risks.
- Audiobooks: search/detail/catalog/episode flow and refresh/caching risks.
- Code review: identify likely latency, stale-cache, or promotion logic risks.
- Device evidence: screenshots, UI tree snapshots, and bridge/flow results where available.

## Novel Sample Set

| # | Book | Shelf latest shown before test | Shelf age |
|---|---|---|---|
| 1 | 灵源仙路 | 第1816章 界源，苦泉 | 6天前 |
| 2 | 叩问仙道 | 第二千七百零七章 九幽魔族 | 6天前 |
| 3 | 我在修仙界万古长青 | 第521章 诚不我欺，翻手为云 | 6天前 |
| 4 | 苟在两界修仙 | 第533章 任务（加更求月票） | 6天前 |
| 5 | 琼明神女录 | 第九十四章：从此人间清暮 | 4天前 |
| 6 | 清光宝鉴 | 第一百零九章：大劫争先机、茶友再相逢 | 6天前 |
| 7 | 元始法则 | 第一千一百二十六章 盂兰盆会 | 6天前 |
| 8 | 玄鉴仙族 | 第1497章 庙语（1+1/2）（以歌c 白银盟加更1/2 | 6天前 |
| 9 | 仙都 | 第二百八十五节 自杀式无人机 | 6天前 |
| 10 | 方寸道主 | 第177章 诡异门扉现 | 6天前 |

## Acceptance Criteria

- Record the current bookshelf/catalog state for all 10 novel samples.
- Run at least one full user-facing novel path from bookshelf into `ReadActivity`, refresh, and catalog drawer.
- Use internal source-engine probes only as supporting evidence for source latency and long-tail behavior.
- Run one comic flow and one audio flow through `MediaProbeActivity`, explicitly marked as debug-only evidence.
- Review the code paths that decide when catalogs are displayed, retained, promoted, cached, and persisted.
- Record build/test attempts, including environment blockers.

## Investigation Questions

- Does the reading page show cached catalog first and then wait for a slower verified/tier catalog?
- Can a newer catalog be ignored if it has the same chapter count as the current catalog?
- Can a newer but shorter/trimmed catalog be ignored because the existing catalog is larger?
- Are background tier/V8 jobs delayed by foreground network priority or exponential retry?
- Do comic/audio catalogs return route registry or route cache entries without a freshness check?
- Does adding to shelf persist a book before the catalog refresh is complete, leaving the user with stale latest chapter text?
