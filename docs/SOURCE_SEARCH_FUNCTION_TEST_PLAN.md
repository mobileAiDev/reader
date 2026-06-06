# Source Search Function Test Plan

Date: 2026-06-05

## Objective

Validate the novel search chain end to end after the tier-wave and validation
candidate-selection changes.

This plan tests the user-facing search path, not only source-quality lab
coverage:

```text
search UI -> source tier waves -> ranking -> group validation -> first publish
          -> selected reading candidate -> detail/catalog entry
```

## Guardrails

- Do not open or inspect chapter body for sensitive rare titles.
- Safe samples may use search, detail, catalog, and a basic read-open smoke.
- Sensitive samples are metadata-only: search result/log/count checks, no
  chapter body inspection.
- Treat source-quality lab results as source-pool evidence, not as a replacement
  for the UI search path.

## P0 Smoke Samples

Run these first after each search-chain change.

| Title | Bucket | Purpose | Expected Checks |
| --- | --- | --- | --- |
| `叩问仙道` | regression-long | User regression: avoid 50-chapter bad source and choose a long catalog. | UI publishes result; selected reading candidate is not `m.bbiquge*`; catalog is clearly long; `ranked > 0`. |
| `诡秘之主` | high-coverage-qidian | Fast common-case source consensus. | First publish should be fast; two trusted sources; catalog is long. |
| `凡人修仙传` | classic-long | Classic long-title distribution with many mirrors. | Catalog count must not collapse to 50; T1/T2 should be enough. |
| `我在精神病院学斩神` | fanqie/breadth | Checks non-classic broad coverage and cover/intro metadata. | Result has readable catalog and metadata; no empty final publish. |
| `三体` | published/shorter | Shorter published work must not be filtered as bad just because it is not long. | Result can publish without long-book penalty; catalog is plausible for the work. |
| `青山` | current/runtime-front | Runtime-front/personal-tier style title with newer source evidence. | Search does not wait for full content tier; detail/catalog opens. |
| `夜无疆` | current/serial | New/current book behavior. | Shorter catalog is allowed when there is no long-book signal. |
| `苟在两界修仙` | runtime-front-long | Per-book source evidence and content-tier fill path. | Search result opens; selected source has normal catalog, not a bad 50-chapter catalog. |

## P1 Broad Safe Samples

Use this set for a full UI-search pass when there is enough time.

Classic long books:

- `斗破苍穹`
- `凡人修仙传`
- `剑来`
- `雪中悍刀行`
- `庆余年`
- `全职高手`
- `斗罗大陆`
- `吞噬星空`

Qidian/current high-coverage books:

- `诡秘之主`
- `大奉打更人`
- `宿命之环`
- `灵境行者`
- `赤心巡天`
- `深海余烬`
- `玄鉴仙族`

Fanqie/breadth books:

- `我在精神病院学斩神`
- `十日终焉`
- `我不是戏神`
- `异兽迷城`
- `开局地摊卖大力`
- `从红月开始`
- `从姑获鸟开始`
- `这个明星很想退休`
- `我有一座恐怖屋`
- `我的治愈系游戏`
- `道诡异仙`

Runtime-front regression books:

- `叩问仙道`
- `青山`
- `夜无疆`
- `苟在两界修仙`
- `元始法则`
- `仙人消失之后`
- `清光宝鉴`
- `我在修仙界万古长青`
- `仙都`
- `苟在武道世界成圣`

Published/category books:

- `三体`
- `鬼吹灯`
- `明朝那些事儿`
- `平凡的世界`
- `活着`
- `围城`

## P2 Sensitive Or Rare Metadata-Only Samples

These are useful for rare-source coverage and search grouping, but do not open
or inspect chapter body during automated verification.

- `琼明神女录`
- `六朝清羽记`
- `逍遥小散仙`
- `仙子的修行`

Allowed checks:

- Raw search candidates exist.
- Exact-title groups are formed.
- Completed validation plan fills beyond the old six-candidate overlap case
  when `total` is larger.
- Final publish is either a valid metadata result or a clear rejection reason.

Disallowed checks:

- Opening chapters.
- Quoting or inspecting chapter body.
- Using正文 content to judge quality.

## Assertions

For every P0/P1 UI search:

- `source_search_ui_started` appears for the query.
- `source_search_tier_wave_started` and `source_search_tier_wave_settled`
  appear in tier order.
- `source_search_rank_stage_finished` has `ranked > 0`.
- `source_search_ui_publish` eventually has `count > 0` and `final_true`.
- Common/high-coverage samples should first publish within 6 seconds on a
  normal network; broad/current samples should target 12 seconds.
- `source_search_validation_plan` should not stop at 6 candidates in completed
  mode when a same-title group has many more candidates available.
- For known long books, selected reading candidates must not have a 50-chapter
  partial catalog.
- Known bad partial sources such as `m.bbiquge.net` and `m.bbiquge8.net` may
  appear as low-tier candidates, but must not become the selected reading
  candidate for long books.
- Rapidly starting a second search should emit cancellation for the old query
  and must not show stale results from the old query.

For detail/catalog entry on safe samples:

- Opening the first displayed result reaches detail without crash.
- Catalog count is plausible for the title class.
- Per-book content tier can continue filling in the background; first display
  must not wait for all 32 sources.
- The read `Intent` payload must not contain the full chapter list. Reader entry
  should use a lightweight book payload and source-engine session cache.
- Reading an uncollected search result must not silently create a bookshelf
  entry. If the exit prompt is cancelled, the shelf remains unchanged.

## Execution Lanes

P0 UI smoke:

```text
叩问仙道
诡秘之主
凡人修仙传
我在精神病院学斩神
三体
青山
夜无疆
苟在两界修仙
```

P1 UI full pass:

```text
斗破苍穹, 凡人修仙传, 剑来, 雪中悍刀行, 庆余年, 全职高手, 斗罗大陆, 吞噬星空,
诡秘之主, 大奉打更人, 宿命之环, 灵境行者, 赤心巡天, 深海余烬, 玄鉴仙族,
我在精神病院学斩神, 十日终焉, 我不是戏神, 异兽迷城, 开局地摊卖大力,
从红月开始, 从姑获鸟开始, 这个明星很想退休, 我有一座恐怖屋, 我的治愈系游戏, 道诡异仙,
叩问仙道, 青山, 夜无疆, 苟在两界修仙, 元始法则, 仙人消失之后, 清光宝鉴,
我在修仙界万古长青, 仙都, 苟在武道世界成圣,
三体, 鬼吹灯, 明朝那些事儿, 平凡的世界, 活着, 围城
```

Source-quality lab shards:

- Shard by source offset so one failing source set does not block the whole run.
- Use the P1 title set for broad coverage.
- Keep `maxContentSamples=1` for routine runs; increase only when auditing a
  specific source.

## Result Template

```text
title:
  uiFirstPublishMs:
  finalPublishMs:
  ranked:
  validationGroups:
  selectedSource:
  selectedChapters:
  bad50SourceSelected: yes/no
  detailCatalogOk: yes/no/not-run
  notes:
```
