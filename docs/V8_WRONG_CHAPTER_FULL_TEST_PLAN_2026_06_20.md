# V8 错章与专属源完整测试用例

日期：2026-06-20

## 目标

1. 阅读体验不能退化：进阅读页要快速显示完整目录，当前章节可读。
2. 搜索体验不能退化：搜索和加入书架不能被 V8 或专属源构建阻塞。
3. 新章节后的目录刷新要快：先展示完整目录，再低优先级补 V8 标记。
4. 错章标记要可解释：能从日志看出触发点、来源、分析范围、章节状态和耗时。
5. 资源消耗要可控：阅读路径只跑轻量 V8，后台维护只在必要时低优先级运行。

## 必采证据

每个用例至少保留三类证据：

1. UI 截图：AI Bridge `screenshot`，证明用户实际看到的状态。
2. UI 树：AI Bridge `tree` 或 `uia-tree`，证明控件文本和可操作节点。
3. 事件日志：AI Bridge `events`，用以下事件串联代码路径。

关键事件：

| 链路 | 事件 |
| --- | --- |
| 目录刷新 | `source_read_catalog_started`, `source_read_catalog_request`, `source_read_catalog_ready`, `source_read_catalog_apply_started`, `source_read_catalog_applied` |
| 前台 V8 触发 | `source_read_v8_ensure_started`, `source_read_v8_ensure_finished` |
| 专属源准备 | `source_content_tier_prepare_started`, `source_content_tier_persisted_load`, `source_content_tier_personal_search_skipped`, `source_content_tier_prepare_finished` |
| V8 调度 | `source_catalog_v8_epoch_started`, `source_catalog_v8_schedule_skipped`, `source_catalog_v8_epoch_running` |
| V8 输入与耗时 | `source_catalog_v8_validate_input_ready`, `source_catalog_v8_validate_finished`, `source_catalog_v8_epoch_committed` |
| V8 标记明细 | `source_catalog_v8_mark_details` |
| V8 cache | `source_catalog_v8_cache_marks_restored`, `source_catalog_v8_cache_content_hit`, `source_catalog_v8_cache_replay_hit`, `source_catalog_v8_cache_saved` |
| 后台维护 | `source_catalog_v8_maintenance_cycle`, `source_catalog_v8_maintenance_book_started`, `source_catalog_v8_maintenance_book_finished` |
| 封面 fallback | `book_cover_candidate_failed`, `book_cover_candidate_loaded`, `book_cover_candidate_promoted` |

性能字段：

| 字段 | 用途 |
| --- | --- |
| `durationMs` | 本阶段耗时，例如目录请求、V8 ensure、tier prepare、V8 validate |
| `elapsedMs` | 从进入阅读页到当前阶段的总耗时 |
| `target`, `mode`, `scope`, `targetLimit` | 证明阅读路径不是 full 32 源大任务 |
| `analysis`, `targets`, `chars` | 证明 V8 输入规模 |
| `heapUsedMb`, `heapMaxMb` | 证明内存占用趋势 |
| `normal`, `wrong`, `nonStory`, `badExtraction`, `inconclusive`, `hidden` | 证明标记结果 |
| `sample`, `targetIndexes` | 证明是哪几章被分析和标记 |

## 全书架验收流程

适用于书架中所有非本地书。每轮测试先记录书架书籍总数、设备型号、网络状态、APK git revision、V8 schema version。

1. 启动 App，等待首页稳定。
2. 遍历每一本书，进入阅读页，记录 `source_read_activity_started` 的书名和当前章节。
3. 等正文可读，记录 `source_read_page_open_finished`、`source_read_catalog_ready`。
4. 打开目录面板，勾选 `显示错章`，截图首屏、当前位置、最后 10 章。
5. 对至少 3 本书执行“清空本书错章标记后重建”：清 V8 cache、registry、章节持久化 mark，再进入阅读页。
6. 对至少 2 本新书执行“新增书籍冷路径”：搜索、加入书架、首次打开、等待目录/V8。
7. 触发一次目录刷新；如果书没有刷新按钮，则退出重进阅读页触发 reading-light 路径。
8. 采集该书从进入阅读页开始的全部 AI Bridge `events/state/network`。
9. 等待 V8 达到终态：`cache_content_hit`、`cache_replay_hit`、`validate_finished + epoch_committed`、`cache_save_skipped`、或明确失败事件。
10. 若 10 分钟内没有终态，保留当前事件，标记为性能/卡住待查，不继续猜原因。
11. 回到书架，继续下一本书。

每本书必须输出一条结论：`PASS`、`PASS_WITH_LONG_V8`、`FAIL_CATALOG`、`FAIL_V8_TRIGGER`、`FAIL_V8_RUNTIME`、`FAIL_MARK_APPLY`、`FAIL_CACHE_POLICY`。

## 时间 SLA

V8 会随章节数和扩展窗口变慢，但阅读体验不能等 V8。

| 项目 | 通过标准 | 失败处理 |
| --- | --- | --- |
| 首页到可点书 | 3 秒内展示书架核心内容；封面可异步 | 超过 5 秒记录首页卡顿 |
| 进入阅读页正文可读 | 5 秒内正文可读 | 超过 8 秒记录 `source_read_page_open_finished` 缺失或慢 |
| 目录可打开并显示完整章节 | 8 秒内可打开目录；章节数、首章、尾章正确 | 目录缺章/错尾章为 `FAIL_CATALOG` |
| 已有 current cache 回放 | 2 秒内恢复 badge 或隐藏标记 | 超过 5 秒为 cache 回放慢 |
| reading_light V8 | target 2/16 应在 60 秒内结束或给出 skip/cache 结论 | 超时为 `FAIL_V8_RUNTIME` |
| reading_catalog_changed 初始窗口 | target 160 应在 3 分钟内 `validate_finished` | 超时保留 `validate_input_ready` 与 `v8.run.start` |
| 扩展窗口 | target 320 应在 10 分钟内完成并提交 | 超过 10 分钟为 `PASS_WITH_LONG_V8` 或性能失败，视是否已提交 |
| 用户可见错章标记 | 常规书 5 分钟内；大量扩展书 10 分钟内 | 有 `wrong>0` 但 UI 没 badge 为 `FAIL_MARK_APPLY` |

注意：如果 V8 需要扩到 320 目标，耗时可以明显增加，但必须不阻塞阅读页和目录。超过 10 分钟仍无提交，要单独开性能问题，不算“正常慢”。

## 全量判定规则

| 证据 | 根因分类 |
| --- | --- |
| 没有 `source_catalog_v8_epoch_started`，也没有 cache hit/replay/skip | 没有触发检查，调度链路 bug |
| `validate_input_ready` 没包含新尾章，但目录已有新尾章 | planner/target 选择 bug |
| `probe_fetched` 已覆盖最后 10 章，但无 `v8.run.start` | validate 调用前被取消或卡在 semaphore |
| `v8.run.start` 后超过 SLA 无 `validate_finished`，且 logcat 无异常 | V8/semantic detector 性能或卡住 |
| `validate_finished wrong>0`，`mark_details h>0`，但目录无 badge/隐藏 | Registry 或 UI 应用 bug |
| `validate_finished all INCONCLUSIVE` 且 `cache_saved saved=true` | cache policy bug |
| current cache 只有 INCONCLUSIVE，却显示 `cacheState_current` | maintenance summary/load policy bug |
| 旧 cache 只覆盖旧尾章，新追加章无标记 | 需要 `reading_catalog_changed` 或更大 target window |
| 目录尾章正确但正文打开旧章节 | PageLoader/current chapter 同步 bug |
| 清空标记后 UI 仍显示旧 badge | 没清到章节持久化 mark 或 runtime registry |
| 新增书首次打开没有 `source_content_tier_prepare_started` | 新书冷路径触发 bug |

## 全书架测试矩阵

每本书至少跑 `A/B/C/D/E` 五类路径；对有问题的书追加 `F/G`。

| 编号 | 场景 | 操作 | 通过标准 |
| --- | --- | --- | --- |
| A | 冷启动进入阅读 | force-stop 后从书架点书 | 正文 5 秒内可读；目录事件完整；V8 不阻塞 |
| B | 目录面板展示 | 打开目录并勾选 `显示错章` | 章节数、当前章节、尾章正确；badge 与 registry 计数可解释 |
| C | 目录刷新 | 点击刷新或重进触发刷新 | 刷新后首/尾章正确；如目录变化，V8 cache 判 stale |
| D | cache 回放 | 第二次打开同一本书 | current cache 或可 replay cache 被恢复；无重复重跑重任务 |
| E | V8 终态 | 等待 V8 hit/replay/commit/skip | 有明确终态；终态与 UI 标记一致 |
| F | 新章节追加 | 找到 catalogSize 变化的书 | promoted 到 `reading_catalog_changed`；最后 10 章被 probe |
| G | 坏缓存回归 | 构造/保留全 INCONCLUSIVE cache | load/replay/summaries 都忽略；maintenance 不判 current |
| H | 本书标记清空重建 | 清 V8 cache、registry、章节持久化 mark 后打开 | UI 起始无旧 badge；随后 V8 重新 probe/commit 并恢复正确 badge |
| I | 新增书籍冷路径 | 搜索新书、加入书架、首次打开 | 首次目录/正文可用；无 cache 也能触发 V8；不影响搜索/阅读性能 |

## 测试矩阵

| 编号 | 场景 | 操作 | 通过标准 |
| --- | --- | --- | --- |
| T01 | 首页封面 fallback 首次加载 | force-stop 后打开首页，等待封面加载 | 有 `book_cover_candidate_failed` 后出现 `book_cover_candidate_promoted`；截图中封面显示成功 |
| T02 | 首页封面 fallback 重启复用 | 不清数据，force-stop 后再次打开首页 | 同一本书不再重复先打失败主封面；无重复 `coll_book_iv_cover` 失败事件 |
| T03 | 元始法则进入阅读页 | 首页点 `元始法则`，等待正文展示 | 可读正文出现；目录刷新事件存在；没有被 V8 阻塞 |
| T04 | 元始法则目录面板文案 | 阅读页点中部，再点 `目录` | 未运行 V8 时不显示 `已分析X章 · 无错章`；只有运行时允许显示 `AI智能错章分析中 · N%` |
| T05 | 元始法则 cache 标记恢复 | 进入阅读页后采集 events | 有 `source_catalog_v8_cache_marks_restored` 与 `source_catalog_v8_mark_details origin_cache_restore`；能看到 marks、hidden、sample |
| T06 | 元始法则标记是否正确 | 对照 `source_catalog_v8_mark_details sample` 与目录截图 | sample 中的章节状态能在目录 badge 或隐藏逻辑中对应；`hidden=0` 时不能把 UI 解释成“全书无错章” |
| T07 | 阅读页前台轻量 V8 | 清 V8 cache 或新增未分析书，进入阅读页并等待前台网络空闲 | 出现 `source_read_v8_ensure_started`；tier 为 `mode_reading_light target_2`；V8 为 `scope_reading_light targetLimit_2` |
| T08 | 阅读页不拉 full tier | 打开任意书并触发当前阅读 V8 | 阅读链路不得出现 `mode_full target_32` 的 tier prepare；不得触发 global search |
| T09 | 目录刷新速度 | 阅读页点刷新目录 | `source_read_catalog_ready durationMs` 与 `source_read_catalog_applied applyMs` 可见；完整目录先展示，V8 后台补标记 |
| T10 | 新章节目录变化 | 选择一本有更新的书或手动添加后刷新目录 | 目录变化不能阻塞展示；维护可以判 V8 cache stale，但只能低优先级 reading_light，不得 full 32 |
| T11 | 后台维护当前 cache | 打开首页等待维护周期 | CURRENT 书籍出现 `cachedRestored_true tierSkipped_true`；只恢复标记，不重建专属源 |
| T12 | 后台维护 missing/stale | 书架中找一本文献缺少 V8 cache 的书 | 只跑 `READING_LIGHT target_2`；完成后有 `source_catalog_v8_mark_details` 与耗时 |
| T13 | 专属源持久化复用 | 已有 `.source_engine_content_tier` 的书重复打开 | 出现 `source_content_tier_persisted_load loaded>0`；候选足够时 `source_content_tier_personal_search_skipped` |
| T14 | 专属源淘汰策略 | 单测构造 tierSize 与 failedRoutes | `SourceContentTierHealthPolicy` 按 1 个失败用于小池、2 个失败用于 4 个以上池子的规则淘汰，不要求最低 8 个源 |
| T15 | 新增书籍全链路 | 搜索一本新书，加入书架，打开阅读 | 搜索结果正常；进入阅读先有完整目录/正文；后台再生成轻量 V8 标记 |
| T16 | CPU/内存趋势 | 对 T07、T12 各采集一次 events | `heapUsedMb` 不随单本反复打开持续增长；V8 validate 的 `chars` 和 `analysis` 符合轻量范围 |
| T17 | 清空元始法则全部标记后重建 | 只清《元始法则》的 V8 cache、runtime registry、章节持久化 mark，重启后进入阅读 | 起始目录无旧 `错章` badge；随后出现 `epoch_started`、`validate_finished`、`cache_saved`；最后 10 章 badge 恢复 |
| T18 | 清空随机书全部标记后重建 | 在书架随机选 2 本章节数不同的书重复 T17 | 章节少的书走 reading_light/小窗口；章节多或目录变化书按 SLA 扩展，不阻塞阅读 |
| T19 | 新增书籍冷路径 | 搜索并加入 2 本之前书架没有的书，一本短书一本长书 | 首次打开没有 cache；仍能展示目录/正文；V8 最终有明确 hit/replay/commit/skip 结论 |
| T20 | 新增书籍后重启复测 | 新书首次 V8 完成后 force-stop，再打开同一本书 | 应走 cache restore/content hit；不得重复跑完整 expanded 窗口 |

## 自动化采集建议

AI Bridge 是必选证据通道。建议每本书生成一个目录：

```text
.ai-bridge-artifacts/full-v8-audit/<book-key>/
  00-status.json
  01-open-reader-events.json
  02-catalog-top.png
  03-catalog-current.png
  04-catalog-tail.png
  05-events.tsv
  06-state.json
  07-result.md
```

关键命令模板：

```bash
ai-app-bridge status --package-name com.ldp.reader
ai-app-bridge events --package-name com.ldp.reader --since-id "$SINCE" --limit 2000
ai-app-bridge state --package-name com.ldp.reader --limit 500
ai-app-bridge tree --package-name com.ldp.reader --compact --visible-only --max-nodes 200
ai-app-bridge screenshot --package-name com.ldp.reader --out-file "$OUT.png"
ai-app-bridge logcat --package-name com.ldp.reader --app-pid --grep 'V8|v8|source_catalog|Exception|FATAL|ANR' --lines 500
```

清空单本标记建议提供 debug-only 入口，不要在正式 UI 暴露。清理动作必须同时覆盖：

1. `source_engine_v8_marks` 中匹配该书 `Identity` 的 cache 文件。
2. `SourceEngineCatalogMarkRegistry` 中该书的 sourceBook/sourceIdentity/bookIdentity/title marks。
3. 章节持久化字段：`sourceIntegrityState`、`sourceIntegrityConfidence`、`sourceIntegrityReason`。
4. 当前阅读页内存章节对象上的 mark。

清理成功的通过标准：

```text
首次打开目录：tail10UiBadges=0 或 only runtime-readable NORMAL
events：没有 cache_marks_restored 指向被清书
state：没有旧 source_catalog_v8_epoch_committed 被误当本轮结果
随后：重新出现 epoch_started -> validate_input_ready -> validate_finished -> cache_saved/skip
```

结果摘要必须包含：

```text
book:
catalogSize:
lastTitle:
openReadableMs:
catalogReadyMs:
v8Scope:
targets:
analysis:
validateMs:
expanded:
wrong:
hidden:
inconclusive:
cacheState:
cacheSaved:
tail10ProbeCovered:
tail10UiBadges:
result:
```

## 2026-06-20 已执行全书架维护审计

本轮实际执行范围：通过 AI Bridge 枚举当前书架 12 本书，安装修复后的 debug APK，冷启动 `MainActivity`，等待启动后 35 秒，让后台维护自然跑完，并采集 `events/state/screenshot`。这验证的是全书架目录数/V8 cache/current-stale 判定/错章标记回放链路；逐本打开目录面板并截图尾 10 章仍需按上面的 `A/B/C/D/E` 矩阵继续跑。

书架枚举截图：

- `.ai-bridge-artifacts/full-shelf-2026-06-20/main-bookshelf.png`
- `.ai-bridge-artifacts/full-shelf-2026-06-20/bookshelf-scroll-1.png`
- `.ai-bridge-artifacts/full-shelf-2026-06-20/bookshelf-scroll-2.png`

关键中间证据：

1. 初始全书架审计：`source_catalog_v8_maintenance_audit_cycle books_12_current_10_stale_2_missing_0_marks_985`。
2. `灵源仙路` 误判 stale：书架名 `灵源仙路`，cache 源名 `灵源仙途：我养的灵兽太懂感恩了`，同作者同目录数同末章，但旧规则没按别名识别。
3. `琼明神女录` 误判 stale：route 指向 84 章旧源，但 94 章当前 cache 已存在；设备 cache 证据为：
   - 84 章：`bookName=琼明神女录 author=倒悬山剑气长存 catalogSize=84 lastTitle=【琼明神女录】（94）完`
   - 94 章：`bookName=琼明神女录 author=剑气长存 catalogSize=94 lastTitle=第九十四章：从此人间清暮`
4. 修复调度后同一维护周期出现 `source_catalog_v8_cache_marks_restored trigger_reading-tier-first-trusted source_开心文学网... catalog_94`，证明源切换后会对新的 primary 再调度/恢复 V8。

最终通过证据：

1. `source_catalog_v8_maintenance_audit_cycle books_12_current_12_stale_0_missing_0_marks_1125`。
2. `source_catalog_v8_maintenance_cycle books_12_stale_0_missing_0_current_12`。
3. `source_catalog_v8_maintenance_cycle_finished books_12_retryBooks_0`。
4. 逐书 current 摘要：
   - `元始法则 bookCatalog_1149 cacheCatalog_1149 cacheMarks_320`
   - `灵源仙路 bookCatalog_1844 cacheCatalog_1844 cacheMarks_160`
   - `琼明神女录 bookCatalog_94 cacheCatalog_94 cacheMarks_20`
   - 其余 9 本均为 `state_current`。
5. 最终截图：`.ai-bridge-artifacts/full-shelf-2026-06-20/ai_app_bridge_screenshot-20260620-121246-573-86608-ia47ez.png`

## 元始法则专项判断

现象：旧 UI 显示“已分析2章 · 无错章”。

必须用以下证据判断，而不是只看文案：

1. `source_catalog_v8_book_cache_replayed`：证明进入阅读页时是否从 V8 cache 回放。
2. `source_catalog_v8_cache_marks_restored marks_2 catalog_1149`：证明只是恢复了 2 个已有 mark，不代表全书已完成。
3. `source_catalog_v8_mark_details`：证明这 2 个 mark 的 `sample`、状态计数和 `hidden` 数。
4. `source_read_catalog_apply_started marked_2 hidden_0 markMatched_2`：证明 ReadActivity 把这 2 个 mark 应用到目录。
5. 目录面板截图：证明不再显示误导性完成文案；如果 V8 正在跑，只显示 `AI智能错章分析中 · N%`。

结论判定：

| 证据组合 | 结论 |
| --- | --- |
| marks=2, hidden=0, UI 无完成文案 | 正常。只是 2 个样本 mark，且没有隐藏错章 |
| marks=2, hidden>0, 目录 badge/隐藏不对应 | 标记应用或 UI 展示有 bug |
| 没有 cache_restore，也没有 ensure_started | 触发链路有 bug |
| ensure_started 后无 prepare/validate/skip 结论 | V8 job 卡住或被取消，要看 `source_read_v8_ensure_finished` 与失败事件 |

## 元始法则修复后基线

2026-06-20 桥验证结论：

1. 旧坏缓存形状：1149 章 current cache 只有 `[1194,1195]` 两个 target，两个 mark 都是 `INCONCLUSIVE`。
2. 修复后维护事件变为 `cacheState_stale cacheCatalog_1139 bookCatalog_1149`，证明 1149/2 mark 坏缓存不再被 summaries 当 current。
3. 进入阅读页后只回放旧好缓存：`source_catalog_v8_book_cache_replayed restored_2 candidates_2`，不再回放 1149/2 mark。
4. `source_catalog_v8_scope_promoted` 给出 `reason_old_hidden_tail_appended oldCatalog_1139 currentCatalog_1149`。
5. 初始 V8：`scope_reading_catalog_changed targetLimit_160 analysis_170 targets_160`，并且 probe 覆盖 1137-1146。
6. 初始结果：`validate_finished phase_initial targets_160 normal_0 wrong_0 inconclusive_160 durationMs_132329`，随后扩展到 320 target。
7. 扩展结果：`validate_finished phase_expanded_1 analysis_326 targets_320 normal_132 wrong_187 inconclusive_1 badTail_891 durationMs_598004`。
8. cache 保存：`source_catalog_v8_cache_saved saved_true marks_320 catalog_1149`。
9. UI 截图：
   - `.ai-bridge-artifacts/yuanshi-after-fix-catalog-final-tail.png`：1137-1142 已显示 `错章`。
   - `.ai-bridge-artifacts/yuanshi-after-fix-catalog-last-chapters.png`：1137-1145 可见段已显示 `错章`。

这个基线同时说明两件事：不是没有检查，也不是只送最后 10 章；真实问题是坏 current cache 被接受，以及 catalog changed 场景需要扩展窗口才能确认尾部边界。性能上，320 target 扩展耗时接近 10 分钟，必须纳入全书架 SLA 监控。

## 记录模板

每次实测记录：

```text
书名：
入口：首页/搜索/阅读刷新/后台维护
截图：
tree：
关键事件：
目录耗时：
V8 耗时：
tier mode/target：
V8 scope/targets/chars：
mark details：
heap：
结论：
```
