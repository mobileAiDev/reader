# Reader 全 App 审计报告

Date: 2026-06-12

Status: Final

## 摘要

本轮按“不改代码”执行，只新增/更新审计文档。结论是：当前 debug APK 可以构建并安装，首页、小说搜索/详情/阅读、漫画 flow、听书 flow 都能跑通；但 app 单测套件有 1 个契约失败，lint 有 60 个 error，源引擎瀑布/V8/听书服务/Manifest 配置存在高优先级设计、性能、安全和代码质量问题。

最高优先级问题有三类：

- **发布阻断类**：`:app:testDebugUnitTest` 失败；`:app:lintDebug` 失败，且包含 minSdk 25 上会触发的 API 26 `java.util.Base64` 调用。
- **运行时体验类**：小说搜索首个结果约 5.7s 发布，但后台验证/tier 填充可持续 80s 以上；V8 初始+扩展验证超过 100s；详情页和阅读页会切到不同源目录，造成目录数量和尾章不一致。
- **安全/边界类**：`AudioPlaybackService` exported 且接受外部 action/URL；Manifest 有系统权限、`persistent`、`largeHeap`、全局 cleartext；本地文档入口 exported + `*/*` 过宽。

## 验证命令

| 命令 | 结果 | 备注 |
| --- | --- | --- |
| `.\gradlew.bat :source-engine:test :app:testDebugUnitTest :app:assembleDebug --offline --no-daemon --stacktrace` | 失败 | `:app:testDebugUnitTest` 519 tests，1 failed，1 skipped |
| `.\gradlew.bat :app:assembleDebug --offline --no-daemon --stacktrace` | 通过 | debug APK 构建成功 |
| `.\gradlew.bat :source-engine:test --offline --no-daemon --rerun-tasks --stacktrace` | 通过 | source-engine JVM 测试通过 |
| `.\gradlew.bat :app:lintDebug --offline --no-daemon --stacktrace` | 失败 | 60 errors / 815 warnings，报告在 `app/build/intermediates/lint_intermediate_text_report/debug/lint-results-debug.txt` |
| AI App Bridge `install-apk` | 通过 | 安装 `app/build/outputs/apk/debug/app-debug.apk` |
| AI App Bridge `launch-app` / `events` / `tree` | 通过 | 首页、搜索、详情、阅读、媒体 probe 均有运行时证据 |

## 运行时验证

| 场景 | 样本 | 结果 | 证据 |
| --- | --- | --- | --- |
| 首页/书架 | 启动当前 debug APK | 成功进入 `MainActivity`，书架可见 | Bridge tree/events，封面加载事件可见 |
| 小说搜索 | `诡秘之主` | 首次 UI 发布约 5.7s，结果可点；后台验证继续超过 88s | `source_search_first_publish_profile`、`source_search_ui_publish`、`source_search_progress_attempt_latest` |
| 小说详情 | `诡秘之主` | 详情可渲染，目录 1230 章，预览源为若雨中文 | `source_detail_activity_render`、`source_detail_preview_resolved` |
| 小说阅读 | `开始阅读` | 进入阅读页，首章约 3s 可读；实际目录切到 101 看书 1417 章 | `source_read_page_loader_created`、`source_catalog_resolved`、`source_read_chapter_finish_event` |
| V8 | 阅读触发 `reading-catalog-anchor-fast` | 初始验证 39.6s，扩展验证 66.1s，保存 46 marks | events 2728-2936，`source_catalog_v8_cache_saved` |
| 漫画 | `斗破苍穹`，24 源，1 本，3 章 | flow 成功，首/中/尾/前后章均 ok，用时 51.3s | `media_flow_summary comic books_1 ok_1 durationMs_51265` |
| 听书 | `三体`，24 源，1 本，3 章 | flow 成功，最终 m4a 可播，用时 9.5s | `media_flow_summary audio books_1 ok_1 durationMs_9496` |

## 问题清单

| 编号 | 严重级别 | 分类 | 模块 | 问题 | 证据 | 建议 |
| --- | --- | --- | --- | --- | --- | --- |
| AUD-001 | P0 | 功能/测试契约 | 小说搜索瀑布 | 单测契约和实现漂移，测试要求 `SEARCH_TAIL_CONTENT_TIMEOUT_MS = 5_000L`，实现是 `2_000L`。 | `app/src/test/java/com/ldp/reader/sourceengine/SourceEngineIsolationContractTest.java:135`；`app/src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt:9780` | 先决策 2s/5s 哪个是正确产品契约，再同步实现或测试；修完必须跑 `:app:testDebugUnitTest`。 |
| AUD-002 | P0 | API 兼容/发布阻断 | media/source route | minSdk 25，但多处调用 API 26 `java.util.Base64`。Android 7.1 设备有崩溃风险，lint 直接失败。 | `LegadoMediaRuleRuntime.kt:453`；`LegadoMediaScriptRuntime.kt:621/625/629/633/962/982/1047/1049`；`SourceEngineBookRoute.kt:14/15/136/140` | 改为 minSdk 可用 API 或提高 minSdk，并补低版本验证。 |
| AUD-003 | P0 | 安全/边界 | 听书服务 | `AudioPlaybackService` exported，外部可发 `TOGGLE/STOP/SET_SLEEP_TIMER` 或传 `EXTRA_URL` 启动播放。 | `AndroidManifest.xml:109-110`；`AudioPlaybackService.kt:120-140`、`516-527` | 默认设为非导出；如必须导出，增加签名权限、caller 校验和 URL 白名单。 |
| AUD-004 | P1 | 配置/安全/性能 | App Manifest | 申请系统/高危权限，并启用 `largeHeap`、`persistent`、全局 cleartext。 | `AndroidManifest.xml:10-16`、`34-40` | 权限和进程属性逐项写明需求契约；删除无明确需求的权限/属性；cleartext 改成按域配置。 |
| AUD-005 | P1 | 设计/API 契约 | SourceEngineSwitch | `SourceEngineSwitch.isEnabled()` 恒 true，`setEnabled()` 忽略入参，实际没有 backend rollback；测试名仍要求 switchable provider。 | `SourceEngineSwitch.kt:8-14`；`BookContentProviderRouter.kt` 搜索路由依赖该开关 | 明确这是永久迁移还是可回滚开关；如果永久迁移，重命名并删除误导测试；如果要回滚，实现真实开关。 |
| AUD-006 | P1 | V8/发布 | BGE 模型资产 | `SourceEngineV8BgeModelProvider` 只 `copyAssetIfMissing`，已存在且非空就不覆盖，APK 内模型升级可能不会落到 app files。 | `SourceEngineV8BgeModelProvider.kt:23-50`；`V8SemanticModel.kt:88/245` | 用 asset 版本/hash 做迁移，或把 cache dir version 和模型内容版本绑定；升级后做一次真实安装保留数据验证。 |
| AUD-007 | P1 | 性能/UX | 小说搜索瀑布 | 搜索首结果约 5.7s 可见，但进度仍显示验证/等待，后台 tier 超过 88s 仍未稳定到目标 32 源。 | events `source_search_first_publish_profile elapsed_5682`、`source_content_tier_prepare_finished duration_81140 ready_false` | 把“可读首结果”与“后台完善源池”分层展示；给后台 tier 独立取消/降优先级/上限策略。 |
| AUD-008 | P1 | 功能一致性 | 详情/阅读 | 详情渲染 1230 章、尾章为若雨中文结果；点击阅读后锚点切到 101 看书 1417 章，尾章变 `新书已发`。 | events `source_detail_activity_render chapters_1230`；`source_catalog_resolved source_101看书 cached_1417` | 源切换时刷新详情可见目录/尾章，或在阅读前提示/记录“更换了更完整源”。 |
| AUD-009 | P1 | V8 性能/准确性 | V8 尾部完整性 | V8 初始 39.6s，扩展 66.1s；扩展后 `normal_41 wrong_0 nonStory_2 badExtraction_1 inconclusive_2`，尾部仍有不确定和坏抽取。 | events 2728-2936；`source_catalog_v8_cache_saved saved_true marks_46 catalog_1417` | 优化 target planning、早停和缓存复用；把 `NON_STORY/BAD_EXTRACTION/INCONCLUSIVE` 分别展示到调试报告，避免把最终尾部问题只归因于 V8 detector。 |
| AUD-010 | P1 | 性能/维护性 | 网络瀑布 | 源引擎有多层并发/超时常量：搜索 64 并发、detail fallback 搜索 48、probe 32、V8 30min 总超时、validation 24 并发；网络门控还用阻塞 sleep 轮询。 | `SourceEngineReaderContentProvider.kt:9631-9780`；`OkHttpSourceEngineFetcher.kt:410-501/564-610/861-862` | 收敛为统一预算模型；用协程挂起替代阻塞 sleep；按 foreground/background/low priority 分别设置可观测指标和取消策略。 |
| AUD-011 | P1 | 设计/API 契约 | 听书播放 | 对 HTTP 错误会重写 header、移除 referer/origin 重试；TLS 允许 `pp.ting55.com` 接受 Aliyun 证书别名。符合可用性目标，但属于隐式兼容/fallback。 | `AudioPlaybackService.kt:450-483`；`MediaPlaybackHeaders.kt:7-37`；`MediaPlaybackTlsPolicy.kt:5-15` | 把每个 fallback 写成明确源规则或播放契约，避免全局静默替代；失败原因应进入可观测事件。 |
| AUD-012 | P1 | 代码质量/发布阻断 | lint 其他 error | lint 还报告 suspicious indentation、Media3 UnsafeOptIn、字符串格式不一致、AppLink URL 缺失、系统权限等。 | `AudioPlaybackService.kt:481`；`AudioPlaybackService.kt:233-238/261/343/345/346`；`values-zh-rTW/strings.xml:94`；`AndroidManifest.xml:85` | 分组修复后把 lint 纳入发版门禁；先修可能影响运行时的 indentation/API/format。 |
| AUD-013 | P2 | 性能 | 漫画 | 漫画核心链路可用，但 24 源、1 本、3 章 flow 用时 51.3s，搜索/详情/目录/图片探测耗时偏高。 | `media_flow_summary comic books_1 ok_1 durationMs_51265` | 对漫画按源质量和历史成功率缩小首屏源池；首屏通过后取消尾部慢源。 |
| AUD-014 | P2 | 功能/可观测性 | 听书 | 听书 flow 可用，但样本中先出现 `chapter_url_unplayable`，再从 raw 提取 m4a 成功。用户看到的是可播，调试上容易隐藏规则质量问题。 | audio flow events `media_audio_resolve stage_chapter_url_unplayable` 后 `stage_raw playable_true` | UI/trace 区分“规则直出成功”和“raw fallback 成功”；源评分应惩罚依赖 raw fallback 的规则。 |
| AUD-015 | P2 | 代码质量 | MediaSourceRepository | 搜索任务大量 `runCatching...getOrDefault(emptyList())`，源异常会退化成空结果。 | `MediaSourceRepository.kt:213-242`、`257-269` | 改为结构化失败事件和源级错误计数；空结果与异常分开统计。 |
| AUD-016 | P2 | 生命周期/稳定性 | 阅读/音频 UI | `ReadActivity` 大量使用 `mPageLoader!!`；`AudioPlayerActivity` controller future/listener 与 handler 生命周期耦合。 | `ReadActivity.kt` 多处 `mPageLoader!!`；`AudioPlayerActivity.kt:381-427` | 把 loader 初始化状态显式化；音频 controller listener 在 destroy 后不再投递 UI 更新。 |
| AUD-017 | P2 | 安全/入口 | 本地文档 | `DocumentOpenRouterActivity` exported 且接受 `*/*`，`takePersistableUriPermission` 失败被忽略。 | `AndroidManifest.xml:83-88`；`DocumentOpenRouterActivity.kt:44` | 缩窄 MIME 类型，校验 scheme/size/type；URI 权限失败要给用户可恢复提示。 |
| AUD-018 | P2 | 测试覆盖 | 全 App | 现有 JVM 测试不少，但缺少覆盖真实搜索、详情、阅读、听书播放、漫画阅读、本地文件导入的 instrumentation/E2E。 | `@Test/class Test` 命中 732 行；instrumentation 仍缺核心流 | 把本轮 bridge flow 固化成可重复 smoke/E2E；至少覆盖搜索到阅读、audio flow、comic flow、文档导入失败路径。 |

## 模块结论

### 小说瀑布与阅读

主链路可以跑通：搜索 `诡秘之主` 后约 5.7s 出结果，详情打开正常，阅读页约 3s 出首章。主要问题不是“完全不可用”，而是可见结果和后台稳定化之间脱节：用户已经能看到一本书，但验证/tier 仍长时间占用进度和网络。详情与阅读切源导致目录数量不一致，是最容易被用户感知的功能问题。

### V8

V8 现在有完整运行和缓存保存证据，说明链路不是断的。问题集中在性能、模型更新和尾部结果解释：扩展阶段跑到 66.1s，ONNX run 达 1297 次，最终仍保留 `NON_STORY/BAD_EXTRACTION/INCONCLUSIVE`。另外 BGE 模型 copy-if-missing 会让“升级 APK 但保留数据”的场景存在旧模型继续使用的风险。

### 听书

听书搜索/解析/播放 URL 验证样本通过，9.5s 完成，比漫画快。但服务导出面和播放 fallback 是高风险：外部 intent 控制服务是安全问题，header/TLS/raw fallback 是设计契约问题。它们能提高可播率，但必须显式化，否则后续很难判断是规则质量好还是 fallback 掩盖了问题。

### 漫画

漫画 flow 样本通过，首/中/尾和前后章验证都成功。主要问题是性能：单本、24 源、3 章用时 51.3s，说明首屏源池和慢源取消策略还需要压缩。代码上目录按 `chapter.index` 找前后章，若恢复的 compact snapshot 丢失完整兄弟目录，前后章体验会受影响。

### 配置、入口与代码质量

Manifest 过宽、lint 失败、`ReadActivity`/音频生命周期风险、媒体源吞错，都是后续稳定性问题。建议先按 P0/P1 修阻断项，再把本轮 bridge flow 自动化，避免每次只靠人工重跑。

## 建议处理顺序

1. 先修 P0：单测契约、minSdk 25 Base64、导出的 `AudioPlaybackService`。
2. 再修 P1：Manifest 过宽配置、SourceEngineSwitch 契约、BGE 模型版本迁移、瀑布后台长尾、详情/阅读目录一致性、V8 性能。
3. 最后固化测试：把小说搜索到阅读、V8 marks、听书 flow、漫画 flow、本地文档入口失败路径做成可重复 smoke。

## 残余风险

- 网络源状态会随时间变化，本轮运行时结果是 2026-06-12 当前源状态，不代表所有源长期稳定。
- 没有真实账号凭据，登录/账号态/远端个人数据路径未做端到端验证。
- 没有 Android 25 真机，API 26 Base64 风险来自 lint 和代码证据，尚未在 25 设备上复现崩溃。
- 没有清空 app 数据，BGE 旧模型风险是代码路径推断；需要用“保留数据安装升级包”单独复现。
