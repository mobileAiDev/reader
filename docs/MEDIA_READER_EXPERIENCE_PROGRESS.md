# 漫画和听书 Legado 源兼容实施进度

## 当前状态

- 状态：2026-06-04 已补齐媒体书架、进度恢复、悬浮条和听书 UI 体验；最新通用漫画内容适配修复后，完整离线 Gradle 回归通过。
- 最新结论：漫画加载失败的根因不是某个漫画源要打补丁，而是媒体内容适配器会把已经可解析的 Legado `<img ...>` 内容再次包装，导致图片 URL 被污染；已在媒体解析层加通用保护。
- 当前主线：媒体源、媒体模型、媒体 Legado 解析器、媒体 UI 和媒体测试继续保持独立，降低漫画/听书兼容改动对小说链路的风险。
- 小说侧要求：不删除小说内置源，不改写小说搜索/详情/阅读链路；媒体兼容风险留在 `com.ldp.reader.media.*` 和 `media-source-engine`。

## 2026-06-04 体验修复进度

- 新增媒体 route 快照：`MediaRouteRegistry` 可保存/恢复媒体书籍、详情、候选源和章节 route。
- 修复媒体章节 route 漂移：同一 `bookRoute + chapter` 重复拉目录时复用原章节 route，降低上一话/下一话和进度 key 失效概率。
- 新增独立媒体书架：`MediaShelfStore` 持久化漫画/听书书架项、当前章节、漫画页码、听书位置、音频时长和 route 快照，不复用小说 `BookRepository`。
- 听书 nowPlaying 改为持久化：保存章节 route、标题、封面、音频 URL、headers、播放进度和时长。
- 听书播放器从悬浮条/书架返回时优先使用已保存音频 URL 和进度，不再必须实时重新解析。
- 漫画阅读页保存页码，从媒体书架或 App 重启后可恢复到上次章节和页码。
- 听书详情页、漫画详情页新增“加入书架”按钮；首页新增独立“媒体书架”区域。
- 首页听书悬浮条支持封面旋转、拖动位置保存、图标播放/暂停和关闭。
- 听书播放器移除“下载”“优惠购买”等未实现入口，主播放控制改用从 Legado 复制的播放/暂停/上一集/下一集/快进/快退图标。
- 新增测试计划：`docs/MEDIA_UI_EXPERIENCE_TEST_PLAN.md`。
- 新增单测：`MediaRouteRegistryTest` 覆盖章节 route 复用和 route 快照恢复。
- 新增漫画内容适配回归：`MediaExtractorTest.mediaContentAdapterDoesNotRewrapLegadoImageMarkup` 覆盖“Legado 脚本已经返回可解析图片标记时不能再包装”。

## 2026-06-04 漫画加载失败根因

复现信号：

- AI App Bridge 真机截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-001139-150-23504-zpu63o.png` 显示漫画页是加载失败占位图，不是正常漫画图片。
- 控件树里存在 `PhotoView` 和 `2 / 36` 页码，但这不能证明图片成功加载。

根因：

- 一部分 Legado 漫画源的 `ruleContent` 脚本已经返回 `<img src="url,{headers...}">` 这类可被 `ComicPageExtractor` 直接解析的内容。
- `MediaContentJsAdapter.adaptComicRawContent()` 之前只看源规则形态，看到规则里有 `split(...).map(<img...>)` 后，会把已经成型的 `<img ...>` 行当成“裸 URL 行”再次包装。
- 二次包装后，完整 `<img ...>` 标记会被塞进新的 `src`，图片加载器拿到的是污染后的 URL，最终显示 `ic_load_error` 占位图。

修复：

- 在媒体适配器入口先调用 `ComicPageExtractor.extractRequests(...)`，如果 raw content 已经能解析出图片请求，就原样返回。
- 这个修复只位于 `app/src/main/java/com/ldp/reader/media/MediaContentJsAdapter.kt`，不是针对 `webmota` 或某一个源的特例补丁。
- 回归单测覆盖同类 Legado 输出，防止以后再次把可解析图片标记二次包装。

## 已完成事实

- 媒体内置源已拆到 `app/src/main/assets/media-source-engine/media-sources.json`。
- 小说内置源保留在 `app/src/main/assets/source-engine/book-sources.json`。
- 媒体模型已独立到 `app/src/main/java/com/ldp/reader/media/MediaSourceModels.kt`。
- 媒体 Legado 解析和脚本运行时位于 `app/src/main/java/com/ldp/reader/media/legado/`。
- 媒体导入使用 `ImportedMediaSourceStore`，小说导入使用 `ImportedSourceStore`。
- 媒体搜索已支持漫画和听书单独搜索入口。
- 需要浏览器登录、真人验证、跳转登录页的源按失败处理；`startBrowser*` 保持失败。
- 无用户操作的 `java.webView` 已按隐藏 WebView/inline script 方向兼容。
- `Packages.okhttp3.*` 直接走当前 OkHttp 5，OkHttp 5 包名仍是 `okhttp3`。
- 媒体脚本层已补 `Packages.cn.hutool.*` 常见 shim：`ZipUtil`、`Base64`、`DigestUtil`、`RandomUtil`、`StrUtil`。
- 媒体加密兼容已覆盖 JS array / Java byte array 的 encrypt/decrypt 传参。
- 媒体源选择已加入漫画体验评分，优先选择首章/中间章/尾章/相邻章节图片数更稳定的源。
- 媒体包反向依赖检查通过：`com.ldp.reader.media.*` 和 `MediaProbeActivity` 未直接引用小说 `BookSource` / `sourceengine`。

## 构建和单测记录

| 时间 | 命令 | 结果 | 备注 |
| --- | --- | --- | --- |
| 2026-06-03 | `.\gradlew.bat :app:compileDebugKotlin --offline --no-daemon --stacktrace` | 通过 | 媒体源评分改动后离线编译通过。 |
| 2026-06-03 | `.\gradlew.bat :app:testDebugUnitTest --tests "com.ldp.reader.media.*" :app:assembleDebug --offline --no-daemon` | 通过 | 媒体单测和 debug APK 构建通过。 |
| 2026-06-03 | `.\gradlew.bat :source-engine:test :app:testDebugUnitTest :app:assembleDebug --offline --no-daemon` | 通过 | 完整离线回归通过；初次 `BUILD SUCCESSFUL in 1m 45s`，最终复跑 `BUILD SUCCESSFUL in 30s`。 |
| 2026-06-03 | AI App Bridge 安装 `app/build/outputs/apk/debug/app-debug.apk` | 通过 | 设备重装成功，桥可用。 |
| 2026-06-04 | `.\gradlew.bat :app:compileDebugKotlin --offline --no-daemon --stacktrace` | 通过 | 媒体书架、route 快照、听书持久化和图标 UI 改动后 Kotlin 编译通过。 |
| 2026-06-04 | `.\gradlew.bat :app:testDebugUnitTest --tests "com.ldp.reader.media.MediaExtractorTest" :app:compileDebugKotlin --offline --no-daemon --stacktrace` | 通过 | 漫画内容二次包装回归单测和 Kotlin 编译通过。 |
| 2026-06-04 | `.\gradlew.bat :app:assembleDebug --offline --no-daemon --stacktrace` | 通过 | 通用漫画适配修复后 debug APK 构建通过。 |
| 2026-06-04 | `.\gradlew.bat :source-engine:test :app:testDebugUnitTest :app:assembleDebug --offline --no-daemon --stacktrace` | 通过 | 最新完整离线回归通过；`BUILD SUCCESSFUL in 2m 22s`。 |

## 漫画后端流程验收

执行方式：`MediaProbeActivity mode=flow kind=comic`，覆盖搜索、聚合结果、详情/目录、源选择、首章/中间章/尾部章、相邻上一话/下一话内容解析。

| 作品 | 源 | 目录 | 首章 | 中间章 | 尾部章 | 上一话 | 下一话 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 斗破苍穹 | 漫画100 | 637 | 36 图 | 22 图 | 20 图 | 21 图 | 23 图 | 通过 |
| 斗罗大陆 | 我的漫神（优+） | 631 | 24 图 | 36 图 | 39 图 | 16 图 | 31 图 | 通过 |
| 一人之下 | 漫画100 | 809 | 17 图 | 11 图 | 17 图 | 21 图 | 17 图 | 通过 |
| 元尊 | 我的漫神（优+） | 1337 | 2 图 | 11 图 | 1 图 | 11 图 | 11 图 | 通过；尾部为公告类章节 |
| 吞噬星空 | 漫画100 | 84 | 12 图 | 11 图 | 12 图 | 13 图 | 11 图 | 通过 |

后端汇总：

- 5 本漫画全部通过：`books_5_ok_5_durationMs_445573`。
- 追加复跑 `元尊,吞噬星空`：`books_2_ok_2_durationMs_159176`。
- 2026-06-04 通用内容适配修复后复跑 5 本：`books_5_ok_5_durationMs_309821`。
- 当前评分能避开部分只有单页或尾部不稳的候选源，选择 `漫画100` / `我的漫神（优+）`。

## 漫画真机 UI 验收

执行方式：AI App Bridge 打开章节 route，真机阅读页检查真实截图、图片页数、控制层、下一话、上一话；有需要时打开目录页检查目录总数。

旧记录里只根据页码和控件树判断的项目不能继续算最终 UI 通过，必须用截图确认没有失败占位图。2026-06-04 已按新口径复核 5 本。

| 作品 | 中间章节 | 初始 | 下一话 | 上一话 | 目录/UI | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| 斗破苍穹 | 第208回 天火尊者 | `1 / 22`，截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-012527-735-16956-k08l89.png` | 首章链路已点下一话，截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-010137-790-18200-1bjxdf.png` | 首章链路已点上一话，截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-010320-462-15548-7jno9n.png` | 控制层可用 | 通过 |
| 斗罗大陆 | 241（2）森罗万象（2） | `1 / 36`，截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-012712-177-2752-v5vx2f.png` | 点下一话到 `1 / 31`，截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-020711-080-18292-lil89i.png` | 点上一话回 `1 / 36`，截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-021227-977-20428-0tflqa.png` | 控制层可用 | 通过 |
| 一人之下 | 391 杀不掉张楚岚就去死吧 | `1 / 11`，截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-012931-833-18640-0h0itd.png` | 点下一话到 `1 / 17`，截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-022907-613-13848-b75i2v.png` | 点上一话回 `1 / 11` | 目录页此前显示 `全部 809 话`，控制层可用 | 通过 |
| 元尊 | 第327话上 宿敌之战 | `1 / 11`，截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-013235-360-15584-05nf6f.png` | 点下一话到第327话下，截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-024607-191-24904-x1rcl5.png` | 点上一话回 `1 / 11` | 控制层可用 | 通过 |
| 吞噬星空 | 第二十二话上（上）：锁定目标 | `1 / 11`，截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-013637-560-4728-ft9rdg.png` | 点下一话到第二十二话（下），截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-014405-200-10812-fekclf.png` | 点上一话回第二十二话上，截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-014935-139-6488-oh9hny.png` | 控制层可用 | 通过 |

注意：

- `tap-text` 可能匹配到旧 Activity window 的不可见同名节点；后续真机 UI 验证应先用 `visibleOnly` 确认当前控制层，再用当前坐标点击。
- 漫画尾部抽样里 `元尊` 的最后一章是公告类 1 图，不影响中间阅读流程，但后续可增加尾部故事章节选择策略。
- 旧记录的“页码存在”不再作为图片加载成功证据；必须保存截图或做像素检查。

## 听书后端流程验收

执行方式：`MediaProbeActivity mode=flow kind=audio`，覆盖搜索、聚合结果、详情/目录、源选择、首集/中间集/尾部集、相邻上一章/下一章音频地址解析。

| 作品 | 源 | 目录 | 首集 | 中间集 | 尾部集 | 上一章 | 下一章 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 凡人修仙传 | 懒人听书（优+++） | 1703 | 1 个可播地址 | 1 | 1 | 1 | 1 | 通过 |
| 三体 | 天下书音（优） | 313 | 1 个可播地址 | 1 | 1 | 1 | 1 | 通过 |
| 斗破苍穹 | 懒人听书（优+++） | 1645 | 1 个可播地址 | 1 | 1 | 1 | 1 | 通过 |
| 诡秘之主 | 懒人听书（优+++） | 3214 | 1 个可播地址 | 1 | 1 | 1 | 1 | 通过 |
| 庆余年 | 懒人听书（优+++） | 730 | 1 个可播地址 | 1 | 1 | 1 | 1 | 通过 |

后端汇总：

- 5 本听书全部通过：`books_5_ok_5_durationMs_271243`。
- 解析过程中会先探测部分 JSON/HTML 或页面地址为不可播，再继续从 raw/content 里解析真实音频 URL；只有 `audio/mpeg` 等可播结果计入通过。

## 听书真机 UI 验收

执行方式：AI App Bridge 打开中间集 route，真机播放器检查标题、播放态、时长、章节切换、进度拖动、书架悬浮条返回。

| 作品 | UI 验证 | 结果 |
| --- | --- | --- |
| 三体 | 中间集打开后 `正在播放`，时长 `20:49`；点下一章切到第 101 集并播放；点上一章回第 100 集并播放；拖进度从几十秒跳到 15 分钟后仍播放；回主界面显示书架迷你播放条，点击迷你条返回播放器，标题和进度保留 | 通过 |
| 凡人修仙传 | 中间集 route 打开，播放器标题显示 `凡人修仙传`，状态 `正在播放`，时长 `03:32` | 通过 |
| 斗破苍穹 | 中间集 route 打开，播放器标题显示 `斗破苍穹`，状态 `正在播放`，时长 `03:32` | 通过 |
| 诡秘之主 | 中间集 route 打开，播放器标题显示 `诡秘之主`，状态 `正在播放`，时长 `03:32` | 通过 |
| 庆余年 | 中间集 route 打开，播放器标题显示 `庆余年`，状态 `正在播放`，时长 `03:32` | 通过 |

注意：

- 当前听书 UI 的完整控制闭环只在三体上做了下一章、上一章、拖进度和悬浮条返回；其他 4 本已证明中间集可进入播放器并播放。
- 本轮测试结束后已暂停当前播放器，避免设备持续播放。

## 小说链路烟测

执行方式：AI App Bridge 从主界面搜索入口进入 `SearchActivity`，保持默认小说 tab，使用热词 `诡秘之主`，再进入详情和阅读页。

| 步骤 | 证据 | 结论 |
| --- | --- | --- |
| 小说搜索 | `SearchActivity` 默认小说 tab；搜索结果第一条 `诡秘之主`，作者摘要为 `爱潜水的乌贼` | 通过 |
| 小说详情 | `BookDetailActivity` 显示 `诡秘之主`、`爱潜水的乌贼`、`最新章节  第二百零四章 狂奔`、`开始阅读` | 通过 |
| 小说阅读 | `ReadActivity` 打开；桥状态 `source_read_page_open_finished.诡秘之主=parsed_true_status_2_chapterPos_0_current_不是诈尸，不是遭了阿蒙_pages_2`；截图可见正文 | 通过 |
| 小说源链路 | `source_search_requests.诡秘之主=sources_358_queries_1_started_358_completed_358_success_358_candidates_1505`，`source_detail_verified_catalog.诡秘之主=chapters_1161...` | 通过 |
| 2026-06-04 最新书架烟测 | 从小说书架点击 `方寸道主` 进入 `ReadActivity`；桥状态 `source_read_page_open_finished.方寸道主=parsed_true_status_2_chapterPos_87_current_第88章_牧家牧无咎_pages_15`；截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-011042-092-16164-1bnbyc.png` 可见正文 | 通过 |

## 后续可扩展项

- 如果后续继续扩大验收，听书可把三体的完整控制闭环复制到另外 2 到 4 本，漫画可增加尾部故事章节而不是尾部公告章节。
- 番茄等仍失败源需要单独分类，不能把需要登录/真人验证/浏览器跳转的源算作 reader 兼容失败。

## 回归保护

- 每轮媒体运行时改动后跑 `rg` 检查媒体包不反向依赖小说源引擎。
- 每轮关键改动后跑媒体单测。
- 最终验收前跑 `:source-engine:test :app:testDebugUnitTest` 和 `:app:assembleDebug`。
- 最终验收前用 AI App Bridge 做小说搜索、详情、阅读烟测，确认小说链路未回归。
