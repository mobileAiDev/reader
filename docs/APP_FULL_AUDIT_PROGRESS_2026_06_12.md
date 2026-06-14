# Reader 全 App 审计进度台账

Date: 2026-06-12

## 目标

在不修改业务代码的前提下，对 reader 做全 App 功能测试和代码审核，覆盖小说搜索/详情/阅读、源引擎瀑布、V8、听书、漫画、书架、本地文档、登录/设置、数据持久化、网络和性能路径，并输出设计问题、性能问题、功能问题、代码问题报告。

## 边界

- 不修改业务代码、资源、构建配置和测试代码。
- 允许新增/更新本审计文档和最终报告文档。
- 以现有单元测试、构建、静态扫描、代码走读、AI App Bridge 真机/模拟器证据作为结论来源。
- 发现问题只记录证据、影响面、复现/验证方式和建议，不直接修复。
- 本轮没有真实账号凭据，也没有清空用户数据；登录闭环和本地文档导入只做静态/配置/入口审核，未做破坏性或账号态验证。

## 适用约束

- `C:\AGENTS.md` 要求代码保持直接、简单、清晰，禁止未经需求或 API 契约要求的 fallback、兼容映射、替代映射和备份逻辑。本轮审核把隐式 fallback、吞错、过宽导出入口和契约漂移作为重点风险。

## 覆盖矩阵

| 模块 | 代码范围 | 自动化 | 运行时 | 代码审核 | 结论状态 |
| --- | --- | --- | --- | --- | --- |
| 构建与基础配置 | root/app/source-engine Gradle、Manifest、assets | `:app:assembleDebug` 通过；`:app:lintDebug` 失败 | 已安装当前 debug APK | 已审 Manifest、lint、权限、导出面 | 有 P0/P1 问题 |
| 首页/书架 | `ui/fragment`、ObjectBox/MMKV、封面加载 | `app:testDebugUnitTest` 覆盖书架单测一部分 | 启动到首页，书架可见，封面事件可捕获 | 已审入口和空封面/Glide 事件 | 基本可用，有覆盖缺口 |
| 小说搜索瀑布 | `SearchActivity`、`SearchViewModel`、`BookContentProviderRouter`、`SourceEngineReaderContentProvider` | 单测套件失败 1 个契约测试 | `诡秘之主` 搜索 5.7s 首次发布，后台验证超过 88s | 已审并发、超时、源选择、发布门 | 有性能/契约问题 |
| 小说详情/阅读 | `BookDetailActivity`、`ReadActivity`、`ReadViewModel`、`widget/page` | 阅读相关 JVM 单测参与套件 | 详情可打开，阅读页 3s 左右出首章 | 已审阅读 loader、目录切换、`!!` 生命周期风险 | 可用但有一致性风险 |
| V8 章节完整性 | `content/v8`、`SourceEngineV8*`、缓存/registry | `:source-engine:test` 通过 | V8 初始 39.6s，扩展 66.1s，marks 保存 | 已审 planner、mark cache、BGE 模型复制 | 有性能/模型更新问题 |
| 源质量/内置源 | `source`、`source-engine`、`assets/source-engine` | 源质量相关单测参与套件 | 搜索/详情/阅读均产生质量事件 | 已审质量路由、tier fill、网络摘要 | 有瀑布尾部 UX 问题 |
| 漫画 | `media`、`Comic*Activity`、`MediaProbeActivity` | 媒体相关单测参与套件 | `斗破苍穹` flow 通过，1 本 51.3s | 已审目录、首/中/尾/前后章路径 | 功能通，性能偏慢 |
| 听书 | `audio`、`Audio*Activity/Service`、media audio resolver | 媒体/音频单测参与套件 | `三体` flow 通过，9.5s，URL 可播 | 已审服务、header/TLS、fallback | 功能通，有安全/设计问题 |
| 本地文档 | `DocumentOpenRouterActivity`、`FileSystemActivity`、PDF/EPUB/TXT | 未单独跑导入样本 | 未做真实文件导入 | 已审 `*/*` VIEW 入口和 URI 权限处理 | 有入口边界问题 |
| 登录/我的/设置 | `Login*`、`MineFragment`、`SettingsActivity`、Mob/Bugly | 未做真实账号自动化 | 未登录验证 | 已审 Manifest/权限/通用入口 | 覆盖不足，需账号态补测 |
| 网络/远端接口 | `RemoteHelper`、Retrofit、OkHttp/source fetcher | source-engine 网络门控测试参与 | 桥事件捕获慢请求、错误、优先级等待 | 已审 `OkHttpSourceEngineFetcher`、并发门控 | 有性能复杂度问题 |
| 通用工具/组件 | `utils`、`widget`、base activity/fragment | lint 扫描覆盖 | UI 树/首页/阅读页验证 | 已审高风险 `!!`、handler、adapter 入口 | 有生命周期风险 |

## 执行日志

| 时间 | 动作 | 证据 | 结果 |
| --- | --- | --- | --- |
| 2026-06-12 | 建立审计目标和边界 | 本文档 | 完成 |
| 2026-06-12 | 检查 AGENTS 作用域 | `C:\AGENTS.md` | 审核重点包含隐式 fallback/吞错风险 |
| 2026-06-12 | 跑构建、source-engine 测试、app 单测 | `.\gradlew.bat :source-engine:test :app:testDebugUnitTest :app:assembleDebug --offline --no-daemon --stacktrace` | `:app:testDebugUnitTest` 失败 1 个契约测试 |
| 2026-06-12 | 分开验证构建 | `.\gradlew.bat :app:assembleDebug --offline --no-daemon --stacktrace` | 通过 |
| 2026-06-12 | 分开验证 source-engine 测试 | `.\gradlew.bat :source-engine:test --offline --no-daemon --rerun-tasks --stacktrace` | 通过 |
| 2026-06-12 | 静态扫描 | `.\gradlew.bat :app:lintDebug --offline --no-daemon --stacktrace` | 失败，60 errors / 815 warnings |
| 2026-06-12 | 覆盖安装当前 debug APK | AI App Bridge `install-apk` | 成功，运行时与当前构建对应 |
| 2026-06-12 | 首页/书架启动验证 | AI App Bridge `launch-app`、`tree`、events | 进入 `MainActivity`，书架可见 |
| 2026-06-12 | 小说搜索/详情/阅读 | 搜索 `诡秘之主`，点击结果，进入阅读 | 首次发布 5.7s；阅读首章 3s 左右；后台 tier/V8 长尾明显 |
| 2026-06-12 | 漫画 flow | `MediaProbeActivity mode=flow kind=comic queries=斗破苍穹 maxBooks=1 maxChapters=3 maxSources=24` | 成功 1/1，用时 51.3s |
| 2026-06-12 | 听书 flow | `MediaProbeActivity mode=flow kind=audio queries=三体 maxBooks=1 maxChapters=3 maxSources=24` | 成功 1/1，用时 9.5s |
| 2026-06-12 | V8 后台验证 | AI App Bridge events | 初始阶段 39.6s，扩展阶段 66.1s，最终保存 46 个 marks |
| 2026-06-12 | 代码走读 | `rg`、关键文件审计 | Manifest、瀑布、V8、音频、漫画、文档入口、网络门控均形成问题条目 |

## 待确认问题池

| 编号 | 分类 | 模块 | 摘要 | 证据 | 状态 |
| --- | --- | --- | --- | --- | --- |
| AUD-001 | 功能/测试契约 | 搜索瀑布 | app 单测要求 tail probe 5s，当前代码是 2s | `SourceEngineIsolationContractTest.java:135`、`SourceEngineReaderContentProvider.kt:9780` | 已确认 |
| AUD-002 | API 兼容 | media/source route | minSdk 25 下使用 `java.util.Base64` API 26 | lint report lines 114-174 | 已确认 |
| AUD-003 | 安全/设计 | 听书服务 | `AudioPlaybackService` 导出且接受外部 URL/action | Manifest 109-110，service 120-140/516-527 | 已确认 |
| AUD-004 | 配置/安全/性能 | App 配置 | 系统权限、`persistent`、`largeHeap`、全局 cleartext 过宽 | Manifest 10-16、34-40 | 已确认 |
| AUD-005 | 设计/回滚 | SourceEngineSwitch | switch 永远 true，测试名仍要求 switchable/rollback | `SourceEngineSwitch.kt:8-14` | 已确认 |
| AUD-006 | V8/发布 | BGE 模型 | 模型 asset 只 copy-if-missing，升级可能不生效 | `SourceEngineV8BgeModelProvider.kt:23-50` | 已确认 |
| AUD-007 | 性能/UX | 小说瀑布 | 首次结果快，但后台验证/tier 长尾超过 88s | bridge events `source_search_first_publish_profile`、`source_content_tier_prepare_finished` | 已确认 |
| AUD-008 | 功能一致性 | 详情/阅读 | 详情目录 1230 章，阅读锚点切到 101 看书 1417 章 | bridge events `source_detail_activity_render`、`source_catalog_resolved` | 已确认 |
| AUD-009 | 性能/设计 | V8 | V8 扩展阶段 66s，1297 次 ONNX run，尾部仍有非故事/坏抽取/不确定 | bridge events 2728-2936 | 已确认 |
| AUD-010 | 设计/维护性 | 听书 | header/TLS/referer fallback 和 raw URL fallback 需要明确契约 | `AudioPlaybackService.kt:450-483`、runtime audio flow | 已确认 |
| AUD-011 | 性能 | 漫画 | 24 源 1 本 flow 51.3s，功能通过但慢 | `media_flow_summary comic` | 已确认 |
| AUD-012 | 代码质量 | media/source | `MediaSourceRepository` 多处 `runCatching...getOrDefault` 会吞源异常 | `MediaSourceRepository.kt:213-242` | 已确认 |
| AUD-013 | 代码质量 | 阅读/音频 | `ReadActivity` 大量 `mPageLoader!!`，音频 controller future 生命周期风险 | `ReadActivity.kt` 多处、`AudioPlayerActivity.kt:381-427` | 已确认 |
| AUD-014 | 安全/入口 | 本地文档 | `DocumentOpenRouterActivity` exported + `*/*`，URI 权限失败被忽略 | Manifest 83-88，`DocumentOpenRouterActivity.kt:44` | 已确认 |
| AUD-015 | 测试覆盖 | 全 App | 缺少覆盖搜索/详情/阅读/媒体播放/漫画/导入的 instrumentation/E2E | 现有 instrumentation 只有基础样例 | 已确认 |

## 当前状态

审计完成，详细结论见 `docs/APP_FULL_AUDIT_REPORT_2026_06_12.md`。
