# 漫画和听书 Legado 源兼容实施进度

## 当前状态

- 状态：2026-06-04 已补齐统一书架媒体卡片、进度恢复、悬浮条和听书 UI 体验；最新漫画末页计数/加载/翻章根因复核后，离线 debug APK 构建通过。
- 最新结论：漫画加载失败的根因不是某个漫画源要打补丁，而是媒体内容适配器会把已经可解析的 Legado `<img ...>` 内容再次包装，导致图片 URL 被污染；已在媒体解析层加通用保护。
- 末页结论：书架漫画 `斗破苍穹` / `包子漫画` 第 1 话解析为 36 张，第 36 张 `36.jpg` 真机 Glide 回调为 `success_true`，旧体验里的“最后一张像失败并进下一话”不是数量少或末图请求失败，而是阅读页状态、边界翻章时序和全局漫画页码恢复互相污染。
- 听书自然续播结论：`STATE_ENDED` 已接入服务层下一集切换；有下一集时会解析下一集音频、更新 now playing / 书架当前章节，并继续播放。最新真机复测用 `有声小说三体` 从 `002.《三体》有声小说预告` 拖到自然结束，桥事件 `media_audio_auto_next` 切到 `003.三体 第1集 会议` 并继续 `playing_true`；`斗罗大陆|网文之王|再铸辉煌` 从 `第000集` 自然结束后桥事件 `media_audio_auto_next` id `6215` 切到 `第001集_斗罗大陆`，id `6221` 继续 `playing_true`；末集 `314.刘慈欣获奖演讲...` 到结尾后事件为 `state_ended_no_next`，UI 停在 `播放完成`。
- 听书内容唯一性结论：旧 `庆余年` 通过记录已撤回。此前只验证到“能播放”，没有验证不同分集的音频是否不同；最新复测确认 LRTS 固定占位音乐已被拒绝，酷我样本中不同章节复用同一 mp3 时会被源选择阶段 `media_audio_route_rejected ... duplicate_audio_signature` 拒绝，不能算通过。
- 当前主线：媒体源、媒体模型、媒体 Legado 解析器、媒体 UI 和媒体测试继续保持独立，降低漫画/听书兼容改动对小说链路的风险。
- 小说侧要求：不删除小说内置源，不改写小说搜索/详情/阅读链路；媒体兼容风险留在 `com.ldp.reader.media.*` 和 `media-source-engine`。

## 2026-06-04 体验修复进度

- 新增媒体 route 快照：`MediaRouteRegistry` 可保存/恢复媒体书籍、详情、候选源和章节 route。
- 修复媒体章节 route 漂移：同一 `bookRoute + chapter` 重复拉目录时复用原章节 route，降低上一话/下一话和进度 key 失效概率。
- 新增独立媒体书架：`MediaShelfStore` 持久化漫画/听书书架项、当前章节、漫画页码、听书位置、音频时长和 route 快照，不复用小说 `BookRepository`。
- 听书 nowPlaying 改为持久化：保存章节 route、作品名、章节标题、封面、音频 URL、headers、播放进度和时长。
- 听书播放器从悬浮条/书架返回时优先使用已保存音频 URL 和进度，不再必须实时重新解析。
- 听书自然播完后会通过 `AudioAutoAdvance` 查找同一书籍 route 下的下一集，有下一集时服务层自动解析并播放下一集；播放页通过 `MediaItem.mediaId` 同步当前章节标题和按钮状态。
- 漫画阅读页保存页码，从媒体书架或 App 重启后可恢复到上次章节和页码。
- 听书详情页、漫画详情页新增“加入书架”按钮；首页统一书架直接混排小说、漫画和听书，媒体卡片左上角显示类型 tag。
- 首页听书悬浮条支持封面旋转、拖动位置保存、图标播放/暂停和关闭。
- 听书播放器移除“下载”“优惠购买”等未实现入口；上一集/下一集/快进/快退使用 Legado 图标，中心播放/暂停改用播放页专用圆角图标。
- 新增测试计划：`docs/MEDIA_UI_EXPERIENCE_TEST_PLAN.md`。
- 新增单测：`MediaRouteRegistryTest` 覆盖章节 route 复用和 route 快照恢复。
- 新增漫画内容适配回归：`MediaExtractorTest.mediaContentAdapterDoesNotRewrapLegadoImageMarkup` 覆盖“Legado 脚本已经返回可解析图片标记时不能再包装”。
- 漫画阅读页新增页数、末三张 URL、每张图片 Glide 成功/失败、尾页是否已终态、边界翻章的 AI Bridge trace。
- 漫画阅读页从 first-visible 页码改为 dominant-visible 页码，避免长图交叠时页码长期停在上一页。
- 媒体书架漫画进度新增按章节 route 保存的页码映射；`comicPageIndex` 继续保留为书架展示字段，避免下一话把上一话页码 clamp 到目标章节尾页。
- 阅读页内上一话/下一话入口会显式按新章节从第一页打开；从书架/目录进入仍走该章节自己的历史页码。

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

## 2026-06-04 漫画末页数量和翻章根因

复现对象：

- 设备书架已有漫画：`斗破苍穹`，源 `包子漫画`，章节 `01`，route `media-chapter:461d40e5-e7ef-49d2-bbd1-6beefd0f45dc`。

日志证据：

- `media_comic_pages_resolved`：`count_36`，末三张为 `34.jpg|35.jpg|36.jpg`。
- `media_comic_image_load`：`position_36_count_36_success_true_detail_DATA_DISK_CACHE_url_s1.bzcdn.net|36.jpg`。
- 继续滚动后 `media_comic_page_state`：`page_36_count_36_canForward_false_lastSettled_true_lastLoaded_true_lastFailed_false`。
- 真机控件树显示 `comic_read_state = 36 / 36`。
- 在第 36 页再做边界上滑后才出现 `media_comic_boundary_sibling direction_next_page_36_count_36`，并进入第 2 话。

结论：

- 不是解析数量少：章节实际解析出 36 张。
- 不是第 36 张网络/解码失败：第 36 张真机回调成功。
- 旧体验问题来自两个 reader 侧问题叠加：长图交叠时页码用 first-visible 容易停在上一页；下一话打开后复用全局 `comicPageIndex`，会把上一话末页页码 clamp 到新章节末页。

修复：

- 保留所有解析页，不删除尾页，不用 trim 掩盖失败。
- 记录每张图成功/失败；边界翻章只要求边界页进入终态，失败页不会把用户永久锁死。
- 进度恢复改为按章节 route 保存；从上一话/下一话进入目标章节时从第一页开始。

复测：

- 第 1 话最终稳定显示 `36 / 36`，无自动跳章。
- 从第 1 话第 36 页上滑进入第 2 话，章节解析为 24 张。
- 安装新包后从第 2 话 `24 / 24` 再做边界上滑，进入第 3 话并显示 `1 / 24`，验证全局页码污染已修复。

## 2026-06-04 听书播放页 UI 复核

调整：

- 播放页顶部从单行章节名改为两行：第一行作品名，第二行当前章节名。
- `AudioPlaybackStateStore` 新增 `bookTitle` 持久化字段；详情页、书架卡片和悬浮条进入播放页时都会传递作品名。
- 唱片直径从 `312dp` 收到 `288dp`，父容器关闭裁剪，避免外圈在左右边缘被轻微截断。
- 摆臂提高层级并移到唱片上方，不再被唱片盖住。
- 中心播放/暂停按钮缩小到更接近网易云风格，并改成圆角播放三角和圆角暂停双竖条。

真机证据：

- 统一书架控件树显示漫画卡片 tag 为 `漫画`，听书卡片 tag 为 `▶ 听书`。
- 播放页控件树显示 `audio_player_title = 三体全集|刘慈欣 精读版 解读版`，`audio_player_episode = 《三体3：死神永生》：宇宙的尽头是什么样的？`。
- 暂停态截图：`C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-062445-989-18424-0nezqz.png`，唱片无左右裁切，播放按钮为较小圆角三角。
- 播放态截图：`C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-062934-842-24708-z5mj6y.png`，暂停按钮为同风格圆角双竖条。
- 桥事件显示从 `00:41` 附近恢复播放：`media_audio_player_state state_playing_true ... position_41044 duration_212520`，随后已手动暂停，避免设备持续播放。

## 2026-06-04 游玩式验收批次

执行口径：

- 安装当前 `app\build\outputs\apk\debug\app-debug.apk` 到真机后，用 AI App Bridge 按真实用户链路跑：搜索多本书、进详情/目录、抽首章/中间章/尾章/相邻章，再打开阅读页或播放器做连续操作。
- 本批次重点是漫画和听书核心链路；小说回归沿用同日 `ReadActivity` 烟测证据，并在本批次后确认应用回到书架且听书没有继续后台播放。

漫画链路：

- 后端 flow：`MediaProbeActivity mode=flow kind=comic queries="斗破苍穹,星辰变,完美世界" maxBooks=1 maxChapters=5 maxSources=16`，summary state id `952` / event id `953` 为 `books_3_ok_3_durationMs_158529`。
- `斗破苍穹 / 漫画100`：目录 `637`，首章 `36` 图，中间 `第208回 天火尊者` 为 `22` 图，上一话 `21` 图，下一话 `23` 图，尾部 `20` 图，state id `332` 为 `ok_true`。
- `星辰变 / 我的漫神（优+）`：目录 `453`，首章/中间/尾部/上一话/下一话均为 `8` 图，state id `801` 为 `ok_true`。
- `完美世界 / 我的漫神（优+）`：目录 `355`，首章 `1` 图为 `近期上线 敬请期待`，中间/上一话/下一话均为 `12` 图，尾部 `44` 图，state id `950` 为 `ok_true`。
- 真机连续阅读：打开 `斗破苍穹` 中间 route `media-chapter:e0888f22-75ef-414a-b105-a21424fa35d9`，UI 显示 `第208回 天火尊者` / `1 / 22`；点击 `下一话` 后切到 `第209回 一丘之貉` / `1 / 23`；再点 `上一话` 回到 `第208回 天火尊者` / `1 / 22`。对应图片加载事件 id `958` / `960` / `964` / `966` / `970` / `972` 均为成功加载或成功解析。

听书链路：

- 后端 flow：`MediaProbeActivity mode=flow kind=audio queries="斗罗大陆,三体,庆余年" maxBooks=1 maxChapters=5 maxSources=16`，summary state id `1873` / event id `1874` 为 `books_3_ok_2_durationMs_192306`。
- `斗罗大陆|网文之王|再铸辉煌 / 懒人听书（优+++）`：目录 `521`，首集/中间集/尾部集/上一集/下一集均解析到 1 个可播音频，state id `1308` 为 `ok_true`，首集 token 为 `C200001oFGn02d3Dfw.m4a`。
- `有声小说三体 / 天下书音（优）`：目录 `313`，首集/中间集/尾部集/上一集/下一集均解析到 1 个可播音频，state id `1565` 为 `ok_true`；其他 `三体` 候选有 `sample_unplayable` 拒绝事件，未误选。
- `庆余年 / 酷我听书`：最终行 state id `1871` 为 `ok_false error_detail_empty`；关键拒绝事件 id `1703` 为 `duplicate_audio_signature`，记录 `chapters_777 playableSamples_5 sampleCount_5`。这是正确失败：它能解析到音频，但不同分集复用同一音频签名，不能再算通过。
- 自然续播：用 `media-chapter:1268d478-48b7-44af-acbe-eae48d24ef5f` 打开 `斗罗大陆` 第 000 集并强制播放，首集 state id `1887` 为 `title_第000集_斗罗大陆 ... C200001oFGn02d3Dfw.m4a`，progress id `1979` 显示 `position_35008 duration_62229 playing_true`。等待自然结束后自动切到 `media-chapter:d87617b8-97fd-4051-809f-4c2311421205` / `第001集_斗罗大陆`，now playing id `2212` 为 `C200002r4dt81HeCkB.m4a`，progress 显示 `duration_1290797 playing_true`。两个分集的 URL token 和时长均不同。
- 收尾暂停：发 `KEYCODE_MEDIA_PAUSE` 后，state id `2451` 为 `media_audio_progress.media-chapter:d87617b8-97fd-4051-809f-4c2311421205 = title_第001集_斗罗大陆_position_200911_duration_1290797_playing_false`。重新启动到 `MainActivity` 后，书架悬浮条显示 `第001集_斗罗大陆`，播放按钮 contentDescription 为 `播放`，说明当前没有残留播放。

小说和书架烟测：

- 本批次未改小说链路；同日小说烟测仍保留为通过：`诡秘之主` 默认小说搜索、详情和 `ReadActivity` 正文打开通过，书架小说 `方寸道主` 进入 `ReadActivity` 的桥状态为 `parsed_true_status_2_chapterPos_87_current_第88章_牧家牧无咎_pages_15`。
- 本轮媒体测试结束后应用可正常回到书架，底部导航选中 `书架`，悬浮条处于暂停态；这覆盖了“媒体播放后返回书架不残留播放”的收尾要求。

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
| 2026-06-04 | `.\gradlew.bat --offline :app:assembleDebug` | 通过 | 漫画末页 trace、按章节漫画页码、统一书架和听书 UI 改动后 debug APK 构建通过。 |
| 2026-06-04 | `.\gradlew.bat --offline :source-engine:test :app:testDebugUnitTest :app:assembleDebug` | 通过 | 播放页作品名/章节名、唱片裁切和圆角播放/暂停图标调整后完整离线回归通过；`BUILD SUCCESSFUL in 1m 34s`。 |
| 2026-06-04 | `.\gradlew.bat --offline :source-engine:test :app:testDebugUnitTest :app:assembleDebug --no-daemon --stacktrace` | 通过 | 本轮漫画/听书核心链路测试前基线；`BUILD SUCCESSFUL in 2m 19s`。 |
| 2026-06-04 | `.\gradlew.bat --offline :app:testDebugUnitTest --tests "com.ldp.reader.audio.AudioAutoAdvanceTest" :app:compileDebugKotlin --no-daemon --stacktrace` | 通过 | 听书自然续播下一集选择单测和 Kotlin 编译通过；最终复跑 `BUILD SUCCESSFUL in 1m`。 |
| 2026-06-04 | `.\gradlew.bat --offline :app:assembleDebug --no-daemon --stacktrace` | 通过 | 听书自然续播和播放页 route 同步改动后 debug APK 构建通过；最终复跑 `BUILD SUCCESSFUL in 42s`。 |
| 2026-06-04 | `.\gradlew.bat --offline :source-engine:test :app:testDebugUnitTest :app:assembleDebug --no-daemon --stacktrace` | 通过 | 当前未提交代码的完整离线回归；`BUILD SUCCESSFUL in 2m 1s`。 |
| 2026-06-04 | `.\gradlew.bat --offline :app:testDebugUnitTest --tests "com.ldp.reader.media.legado.LegadoMediaRuleRuntimeTest.chaptersRenderBaseUrlMatchAndStoredVariablesInJsonTemplates" --tests "com.ldp.reader.media.MediaExtractorTest.audioExtractorRejectsKnownSourcePlaceholderAudio" --no-daemon --stacktrace` | 通过 | LRTS `entityId/entityType` 模板渲染和固定占位音频拒绝回归通过；先红后绿。 |
| 2026-06-04 | `.\gradlew.bat --offline :source-engine:test :app:testDebugUnitTest --tests "com.ldp.reader.media.MediaPlaybackSignatureTest" --tests "com.ldp.reader.media.legado.LegadoMediaRuleRuntimeTest.chaptersRenderBaseUrlMatchAndStoredVariablesInJsonTemplates" --tests "com.ldp.reader.media.MediaExtractorTest.audioExtractorRejectsKnownSourcePlaceholderAudio" --tests "com.ldp.reader.audio.AudioAutoAdvanceTest" --no-daemon --stacktrace` | 通过 | 内容唯一性、模板渲染、占位音频拒绝、自动下一集和 source-engine 回归通过；`BUILD SUCCESSFUL in 1m 24s`。 |
| 2026-06-04 | `.\gradlew.bat --offline :app:testDebugUnitTest --tests "com.ldp.reader.media.MediaPlaybackSignatureTest" --no-daemon --stacktrace` | 通过 | 混合样本中任意两个不同章节复用同一音频 URL 即判重复；`BUILD SUCCESSFUL in 1m 10s`。 |
| 2026-06-04 | `.\gradlew.bat --offline :app:assembleDebug --no-daemon --stacktrace` | 通过 | 内容唯一性检测收紧后 debug APK 构建通过；`BUILD SUCCESSFUL in 40s`。 |
| 2026-06-04 | `.\gradlew.bat --offline :app:testDebugUnitTest --tests "com.ldp.reader.media.MediaPlaybackSignatureTest" --tests "com.ldp.reader.media.MediaExtractorTest.audioExtractorRejectsKnownSourcePlaceholderAudio" --tests "com.ldp.reader.media.legado.LegadoMediaRuleRuntimeTest.chaptersRenderBaseUrlMatchAndStoredVariablesInJsonTemplates" --tests "com.ldp.reader.audio.AudioAutoAdvanceTest" :app:compileDebugKotlin --no-daemon --stacktrace` | 通过 | 听书源选择收紧、重复音频拒绝 trace、自动下一集单测和 Kotlin 编译通过；`BUILD SUCCESSFUL in 1m 36s`。 |
| 2026-06-04 | `.\gradlew.bat --offline :app:assembleDebug --no-daemon --stacktrace` | 通过 | 听书源选择收紧后最终 debug APK 构建通过；`BUILD SUCCESSFUL in 46s`。 |
| 2026-06-04 | `git diff --check` | 通过 | 漫画/听书扩样文档和最终代码 diff 无空白错误；仅有 Windows 行尾提示。 |
| 2026-06-04 | `.\gradlew.bat --offline :app:testDebugUnitTest --tests "com.ldp.reader.media.MediaPlaybackSignatureTest" --tests "com.ldp.reader.media.MediaExtractorTest.audioExtractorRejectsKnownSourcePlaceholderAudio" --tests "com.ldp.reader.media.legado.LegadoMediaRuleRuntimeTest.chaptersRenderBaseUrlMatchAndStoredVariablesInJsonTemplates" --tests "com.ldp.reader.audio.AudioAutoAdvanceTest" :app:compileDebugKotlin --no-daemon --stacktrace` | 通过 | 最终关键单测和 Kotlin 编译复跑通过；`BUILD SUCCESSFUL in 30s`。 |
| 2026-06-04 | `.\gradlew.bat --offline :source-engine:test :app:testDebugUnitTest :app:assembleDebug --no-daemon --stacktrace` | 通过 | 最终发布前组合回归通过，覆盖 source-engine 单测、app 单测和 debug APK；`BUILD SUCCESSFUL in 1m 48s`。 |
| 2026-06-04 | `git diff --check` | 通过 | 游玩式漫画/听书验收记录补充后 diff 无空白错误；仅有 Windows 行尾提示。 |
| 2026-06-04 | `.\gradlew.bat --offline :source-engine:test :app:testDebugUnitTest :app:assembleDebug --no-daemon --stacktrace` | 通过 | 游玩式漫画/听书验收批次后完整离线回归通过；`BUILD SUCCESSFUL in 32s`。 |

## 漫画后端流程验收

执行方式：`MediaProbeActivity mode=flow kind=comic`，覆盖搜索、聚合结果、详情/目录、源选择、首章/中间章/尾部章、相邻上一话/下一话内容解析。

| 作品 | 源 | 目录 | 首章 | 中间章 | 尾部章 | 上一话 | 下一话 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 斗破苍穹 | 漫画100 | 637 | 36 图 | 22 图 | 20 图 | 21 图 | 23 图 | 通过 |
| 斗罗大陆 | 我的漫神（优+） | 631 | 24 图 | 36 图 | 39 图 | 16 图 | 31 图 | 通过 |
| 一人之下 | 漫画100 | 809 | 17 图 | 11 图 | 17 图 | 21 图 | 17 图 | 通过 |
| 元尊 | 我的漫神（优+） | 1337 | 2 图 | 11 图 | 1 图 | 11 图 | 11 图 | 通过；尾部为公告类章节 |
| 吞噬星空 | 漫画100 | 84 | 12 图 | 11 图 | 12 图 | 13 图 | 11 图 | 通过 |
| 武动乾坤 | 我的漫神（优+） | 393 | 8 图 | 12 图 | 17 图 | 12 图 | 12 图 | 通过 |
| 大主宰 | 包子漫画 | 0 | 0 图 | 0 图 | 0 图 | 0 图 | 0 图 | 失败：`detail_empty` |
| 全职法师 | 我的漫神（优+） | 1199 | 29 图 | 15 图 | 14 图 | 14 图 | 15 图 | 通过 |
| 百炼成神 | 包子漫画 | 1309 | 42 图 | 26 图 | 6 图 | 17 图 | 17 图 | 通过 |
| 妖神记 | 我的漫神（优+） | 989 | 16 图 | 10 图 | 9 图 | 10 图 | 10 图 | 通过 |
| 镇魂街 | 我的漫神（优+） | 467 | 26 图 | 20 图 | 25 图 | 19 图 | 20 图 | 通过 |
| 斗罗大陆2绝世唐门 | 漫画100 | 587 | 11 图 | 12 图 | 16 图 | 12 图 | 15 图 | 通过 |
| 灵剑尊 | 我的漫神（优+） | 533 | 11 图 | 11 图 | 41 图 | 11 图 | 11 图 | 通过 |
| 星辰变 | 我的漫神（优+） | 453 | 8 图 | 8 图 | 8 图 | 8 图 | 8 图 | 通过 |
| 完美世界 | 我的漫神（优+） | 355 | 1 图 | 12 图 | 44 图 | 12 图 | 12 图 | 通过；首章为预告/敬请期待页 |

后端汇总：

- 5 本漫画全部通过：`books_5_ok_5_durationMs_445573`。
- 追加复跑 `元尊,吞噬星空`：`books_2_ok_2_durationMs_159176`。
- 2026-06-04 通用内容适配修复后复跑 5 本：`books_5_ok_5_durationMs_309821`。
- 2026-06-04 本轮复跑 5 本：`books_5_ok_5_durationMs_300597`；样本仍为 `斗破苍穹`、`斗罗大陆`、`一人之下`、`元尊`、`吞噬星空`，全部 `ok_true`。
- 2026-06-04 扩展批次一跑 `武动乾坤,大主宰,全职法师,百炼成神,妖神记`，summary state id `5313` / event id `5314` 为 `media_flow_summary.comic = books_5_ok_4_durationMs_214089`；`大主宰` 为 `detail_empty`，其余 4 本通过。
- 2026-06-04 扩展批次二跑 `镇魂街,斗罗大陆2绝世唐门,灵剑尊,星辰变,完美世界`，summary state id `6098` / event id `6099` 为 `media_flow_summary.comic = books_5_ok_5_durationMs_294771`，5 本全部通过。
- 当前累计漫画后端抽样 15 本，14 本通过、1 本失败分类；P1 的 10 本后端样本量目标已覆盖。
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
- 2026-06-04 本轮代表 UI 复测：先用 trace token 里的 `media-chapter_bcb...` 打开失败，`media_comic_pages_resolved.count_0`，确认 trace 会把真实 route 的冒号替换成下划线；改用真实 `media-chapter:bcb0326f-dadb-44eb-b91f-55d15784c6f1` 后，`斗破苍穹` 第 208 回显示 `1 / 22`，`media_comic_pages_resolved.count_22`，第 1、2 张 `media_comic_image_load success_true`，截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-080748-991-15692-ckh17e.png`。

## 听书后端流程验收

执行方式：`MediaProbeActivity mode=flow kind=audio`，覆盖搜索、聚合结果、详情/目录、源选择、首集/中间集/尾部集、相邻上一章/下一章音频地址解析。

注意：2026-06-04 起听书后端通过口径升级为必须校验不同章节的音频签名。旧记录中的 `ok_true` 只说明抽样章节解析到了可播 URL，不再单独作为“内容正确”通过依据。

| 作品 | 源 | 目录 | 首集 | 中间集 | 尾部集 | 上一章 | 下一章 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 三体 | 天下书音（优） | 313 | 1 个可播地址 | 1 | 1 | 1 | 1 | 通过 |
| 斗罗大陆 | 懒人听书（优+++） | 521 | 1 个可播地址 | 1 | 1 | 1 | 1 | 通过 |
| 雪中悍刀行 | 酷我听书 | 12 | 1 个可播地址 | 1 | 1 | 1 | 1 | 通过，后端候选；真机控制待补 |
| 剑来 | 酷我听书 | 1080 | 1 个可播地址 | 1 | 1 | 1 | 1 | 通过，后端候选；真机控制待补 |
| 庆余年 | 酷我听书 / 懒人听书候选 | 907 / 730 | 1 个可播地址 | 1 | 1 | 1 | 1 | 失败：不同章节复用同一音频 URL，`duplicate_audio_signature`；旧通过撤回 |
| 盗墓笔记 | 酷我听书 / 懒人听书候选 | 0 | 0 | 0 | 0 | 0 | 0 | 失败：重复音频或样本不可播，最终 `detail_empty` |
| 明朝那些事儿 | 酷我听书 | 18 | 1 个可播地址 | 1 | 1 | 1 | 1 | 失败：抽样章节复用同一音频 URL，`duplicate_audio_signature` |
| 凡人修仙传 | 懒人听书（优+++） | 1703 | 旧口径 1 个可播地址 | 旧口径 1 | 旧口径 1 | 旧口径 1 | 旧口径 1 | 旧通过撤回；最新复核为 `selected_chapter_unreadable` |
| 斗破苍穹 | 懒人听书（优+++） | 0 | 0 | 0 | 0 | 0 | 0 | 失败：候选源样本不可播，最终 `detail_empty`；旧通过撤回 |
| 诡秘之主 | 懒人听书（优+++） | 0 | 0 | 0 | 0 | 0 | 0 | 失败：候选源样本不可播，最终 `detail_empty`；旧通过撤回 |
| 大奉打更人 | 酷我听书 | 0 | 0 | 0 | 0 | 0 | 0 | 失败：候选源重复音频，最终 `detail_empty` |
| 遮天 | 酷我听书 / 懒人听书候选 | 0 | 0 | 0 | 0 | 0 | 0 | 失败：重复音频或样本不可播，最终 `detail_empty` |
| 完美世界 | 酷我听书 / 懒人听书候选 | 0 | 0 | 0 | 0 | 0 | 0 | 失败：候选源样本不可播，最终 `detail_empty` |

后端汇总：

- 历史旧口径曾记录 5 本听书全部通过：`books_5_ok_5_durationMs_271243`；该记录只保留为回溯证据，不能作为最终通过。
- 2026-06-04 旧口径复跑 5 本：`books_5_ok_5_durationMs_307987`；样本为 `凡人修仙传`、`三体`、`斗破苍穹`、`诡秘之主`、`庆余年`，当时全部 `ok_true`，但未校验分集音频唯一性，不能作为最终通过依据。
- 解析过程中会先探测部分 JSON/HTML 或页面地址为不可播，再继续从 raw/content 里解析真实音频 URL；只有 `audio/mpeg` 等可播结果计入通过。
- 2026-06-04 内容唯一性复测：安装新 debug 包后跑 `MediaProbeActivity mode=flow kind=audio query=庆余年 maxBooks=1 maxChapters=5 maxSources=12`，旧中间结果曾在 flow 行直接报 `duplicate_audio_signature`；源选择收紧后最终结果为 `media_flow_summary.audio = books_1_ok_0_durationMs_45138`，`media_flow_row.audio_庆余年_0 = ok_false error_detail_empty`。这不是通过失败原因丢失：桥事件 `media_audio_route_rejected` id `54`、`99`、`135` 均记录 `duplicate_audio_signature`，说明酷我不同章节复用同一 mp3；LRTS 候选的固定占位音乐已不再被识别为有效音频。
- 2026-06-04 正向复测：`MediaProbeActivity mode=flow kind=audio query=三体 maxBooks=1 maxChapters=5 maxSources=12` 返回 `media_flow_summary.audio = books_1_ok_1_durationMs_133230`；选中 `天下书音（优）`，`media_route_source_selected` id `1168` 为 `reason_audio_experience`，目录 `313`，first/middle/tail/previous/next 均为 `1`，不同章节音频签名不重复。另一个 `三体：地球往事` 候选因 `sample_unplayable` 被拒绝，未误选。
- 2026-06-04 负向扩样：`盗墓笔记` 返回 `media_flow_summary.audio = books_1_ok_0_durationMs_34784`，`media_flow_row.audio_盗墓笔记_0 = ok_false error_detail_empty`；桥事件 id `1902`、`1934` 为酷我候选 `duplicate_audio_signature`，id `1975`、`2025` 为懒人候选 `sample_unplayable`。`明朝那些事儿` 在批量 flow 中行结果为 `ok_false duplicate_audio_signature`，不能因为 18 个目录和可播 URL 就通过。
- 2026-06-04 正向扩样：批量跑 `MediaProbeActivity mode=flow kind=audio queries=明朝那些事儿,雪中悍刀行,斗罗大陆 maxBooks=1 maxChapters=5 maxSources=16` 返回 `media_flow_summary.audio = books_3_ok_2_durationMs_112962`。`雪中悍刀行 / 酷我听书` 目录 `12`，first/middle/tail/previous/next 均为 `1`；`斗罗大陆|网文之王|再铸辉煌 / 懒人听书（优+++）` 目录 `521`，`media_route_source_selected` id `1383` 为 `reason_audio_experience`，`experience_15300 samples_5 navigation_3`，first/middle/tail/previous/next 均为 `1`。
- 2026-06-04 继续扩样：批量跑 `MediaProbeActivity mode=flow kind=audio queries=斗破苍穹,诡秘之主,大奉打更人,剑来,遮天,完美世界 maxBooks=1 maxChapters=5 maxSources=16` 返回 `media_flow_summary.audio = books_6_ok_1_durationMs_368086`，summary state id `4885` / event id `4886`。`剑来|无删减多人剧|曲中人有故事演播|热播动漫原著|陈平安 / 酷我听书` 目录 `1080`，state id `3985` 显示 first/middle/tail/previous/next 均为 `1`，`media_route_source_selected` id `3917` 为 `reason_audio_experience`，`experience_15300 samples_5 navigation_3`。`斗破苍穹` state id `2565`、`诡秘之主` id `2965`、`大奉打更人` id `3284`、`遮天` id `4400`、`完美世界` id `4883` 均为 `ok_false error_detail_empty`；其中 `斗破苍穹` route reject id `2349`/`2417`/`2485`、`诡秘之主` id `2964`、`完美世界` id `4626`/`4736`/`4807`/`4882` 为 `sample_unplayable`，`大奉打更人` id `3039`/`3093`/`3140`、`遮天` id `4111` 为 `duplicate_audio_signature`。

## 听书真机 UI 验收

执行方式：AI App Bridge 打开中间集 route，真机播放器检查标题、播放态、时长、章节切换、进度拖动、书架悬浮条返回。

注意：旧记录中多个样本显示相同 `03:32` 时长，只能说明播放器启动成功，不能证明章节内容正确。未完成音频唯一性复核的样本不得继续按完整通过计算。

| 作品 | UI 验证 | 结果 |
| --- | --- | --- |
| 三体 | 中间集打开后 `正在播放`，时长 `20:49`；点下一章切到第 101 集并播放；点上一章回第 100 集并播放；拖进度从几十秒跳到 15 分钟后仍播放；回主界面显示书架迷你播放条，点击迷你条返回播放器，标题和进度保留 | 通过 |
| 斗罗大陆 | 第 000 集打开后 `正在播放`，时长 `01:02`，`media_audio_request_resolved` id `1622` / `6157` 为 `C200001oFGn02d3Dfw.m4a`；点下一集或自然结束后切到 `第001集_斗罗大陆` 并继续播放，时长 `21:30`，id `1660` / `6213` 为 `C200002r4dt81HeCkB.m4a`，id `1670` / `6221` 为 `playing_true duration_1290797` | 基础播放、手动下一集和自然下一集通过；完整控制闭环待补 |
| 凡人修仙传 | 中间集 route 打开，播放器标题显示 `凡人修仙传`，状态 `正在播放`，时长 `03:32` | 旧基础播放记录，待音频唯一性复核 |
| 斗破苍穹 | 中间集 route 打开，播放器标题显示 `斗破苍穹`，状态 `正在播放`，时长 `03:32` | 旧基础播放记录，待音频唯一性复核 |
| 诡秘之主 | 中间集 route 打开，播放器标题显示 `诡秘之主`，状态 `正在播放`，时长 `03:32` | 旧基础播放记录，待音频唯一性复核 |
| 庆余年 | 旧中间集 route 打开后显示 `03:32` 的记录已撤回；最新 flow 发现不同分集复用音频 URL | 不通过，需更换健康源或修复源选择 |

注意：

- 当前听书 UI 的完整长链路仍以 `三体` 为代表样本；`斗罗大陆` 已补为第二个健康增强播放样本并证明手动下一集、自然下一集都会更换 URL 和时长；`庆余年` 旧第二样本播放器核心控制闭环因音频唯一性失败已撤回，不再计入通过。
- 本轮测试结束后已暂停当前播放器，避免设备持续播放。
- 2026-06-04 本轮代表 UI 复测：用真实 `media-chapter:100ca9b3-78ad-47c4-92a7-7f8cc4ef7ea9` 打开 `三体` 第 100 集，播放页显示作品名 `有声小说三体`、章节 `三体第100集`、状态 `正在播放`、时长 `20:49`，`media_audio_request_resolved` 为 `audio_mpeg` 可播地址，截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-080951-925-26260-xubzv5.png`；随后点击下一集，切到 `175.三体Ⅱ：黑暗森林 第101集 生存死局？` 并 `playing_true`，最终已点暂停并确认 `已暂停`。
- 2026-06-04 第二健康样本 UI 复测：用真实 `media-chapter:48044f89-2404-46a9-80ad-e8d376ef87a3` 打开 `斗罗大陆|网文之王|再铸辉煌`，播放页显示章节 `第000集`、状态 `正在播放`、时长 `01:02`，`media_audio_request_resolved` id `1622` 为 `vb.stream.tencentmusic.com|C200001oFGn02d3Dfw.m4a`，id `1628` 为 `state_playing_true duration_62229`；点击下一集后切到 `第001集_斗罗大陆`，页面时长 `21:30`，id `1660` 为 `vb.stream.tencentmusic.com|C200002r4dt81HeCkB.m4a`，id `1670` 为 `state_playing_true duration_1290797`。两个分集的 URL token 和时长均不同。本项测试后曾暂停到 id `1695`；收尾复读桥状态时发现最新队列项 `第511集_斗罗大陆` 仍在播放，已发 `KEYCODE_MEDIA_PAUSE`，最新 state id `2133` 为 `playing_false`。
- 2026-06-04 第二健康样本自然续播复测：再次用真实 `media-chapter:48044f89-2404-46a9-80ad-e8d376ef87a3` 打开 `斗罗大陆|网文之王|再铸辉煌`，`media_audio_request_resolved` id `6157` 为 `vb.stream.tencentmusic.com|C200001oFGn02d3Dfw.m4a`，id `6165` 为 `state_playing_true duration_62229`；自然结束后事件 id `6215` 为 `media_audio_auto_next`，切到 `media-chapter:2b2162e9-b2d8-405b-9ad9-f5dc121d4b8b` / `第001集_斗罗大陆`，id `6213` 为 `vb.stream.tencentmusic.com|C200002r4dt81HeCkB.m4a`，id `6221` 为 `state_playing_true duration_1290797`；收尾已发暂停，事件 id `6245` 和 state id `6246` 均为 `playing_false`，控件树显示播放按钮为 `播放`。
- 2026-06-04 第二健康样本悬浮条和定时复测：重新跑 `MediaProbeActivity mode=flow kind=audio query=斗罗大陆 maxBooks=1 maxChapters=5 maxSources=16`，summary state id `377` 为 `books_1_ok_1_durationMs_39763`，flow row state id `375` 显示 `first/middle/tail/previous/next` 均为 `1`，首集 route 为 `media-chapter:b457f250-8bf7-4037-922f-6090b39055be`。打开首集后自然结束，事件 id `1061` 为 `media_audio_auto_next`，切到 `media-chapter:5d496797-cc48-4dde-b4fc-2980111be79e` / `第001集_斗罗大陆`，新 URL token 为 `C200002r4dt81HeCkB.m4a`，state id `1067` 为 `playing_true duration_1290797`。在播放页点击 `定时` 后，服务状态 state id `1075` 为 `state_scheduled_minutes_15_deadlineMs_1780581389169_title_第001集_斗罗大陆`；回到书架后悬浮条显示 `第001集_斗罗大陆` 和 `暂停`，点击暂停后事件 id `1310` 为 `state_playing_false`，按钮变为 `播放`；再点播放后事件 id `1312` 为 `media_audio_service_reuse forceStart_false`，id `1313` 为 `state_playing_true`，证明书架悬浮条能从已持久化的 audio URL 恢复服务播放。收尾已用服务 action 取消定时，state id `1370` / event id `1371` 为 `state_off_minutes_0_deadlineMs_0`，并暂停到 state id `1382` / event id `1381` 的 `playing_false`。
- 2026-06-04 追加扩样尝试：重新用最终 debug 包跑 `MediaProbeActivity mode=flow kind=audio query=凡人修仙传 maxBooks=1 maxChapters=5 maxSources=72`，结果为 `media_flow_summary.audio = books_1_ok_0_durationMs_974272`；`media_flow_row.audio_凡人修仙传_0` 显示首集/中间集/尾部集/相邻上一下一集均为 `0`，错误 `selected_chapter_unreadable`。桥事件 `media_audio_probe` 对 `https_m.lrts.me_ajax_getListenPath` 返回 `UnknownHostException`，因此本次不能把 `凡人修仙传` 计入第二个完整控制闭环样本。
- 2026-06-04 旧第二样本记录（撤回）：`MediaProbeActivity mode=flow kind=audio query=庆余年 maxBooks=1 maxChapters=3 maxSources=16` 曾返回 `media_flow_summary.audio = books_1_ok_1_durationMs_39323`，并完成第 364/365 集的播放器控制复核；该记录没有校验分集音频唯一性，最新复测已证明不能计入通过。

### 听书控制项复核

| 控制项 | 当前证据 | 结论 |
| --- | --- | --- |
| 播放/暂停 | `三体` 第 100 集打开后 `正在播放`，点击暂停后树显示 `已暂停`；`庆余年` 旧控制记录撤回，不计入通过 | 通过 |
| 手动下一集 | `三体` 点击 `下一集` 后切到第 101 集，`media_audio_request_resolved` 解析到新的 `audio_mpeg` 地址并 `playing_true`；`庆余年` 旧控制记录撤回，不计入通过 | 通过 |
| 手动上一集 | `三体` 完整 UI 验收已点上一章回第 100 集并播放；`庆余年` 旧控制记录撤回，不计入通过 | 通过 |
| 拖动进度 | 早前 `三体` 完整 UI 验收已从几十秒拖到 15 分钟后仍播放 | 通过 |
| 15 秒快进/快退 | `三体` 暂停态从 `02:38` 点 `快进15秒` 到 `02:53`，再点 `后退15秒` 回 `02:38`；`庆余年` 旧控制记录撤回，不计入通过 | 通过 |
| 倍速 | 暂停态点击 `倍速` 后按钮文本变为 `1.25x`，随后已切回默认 `倍速` | 通过 |
| 定时暂停 | 旧入口复核：暂停态点击 `定时` 后按钮文本变为 `15分`，随后切回默认 `定时`。最新实现已把定时从页面本地 handler 移到 `AudioPlaybackService`；`斗罗大陆` 播放页点击 `定时` 后 state id `1075` 为 `state_scheduled_minutes_15`，回书架后仍保持服务定时；收尾用服务 action 取消，state id `1370` / event id `1371` 为 `state_off_minutes_0_deadlineMs_0`。未等待真实 15 分钟到点触发 | 服务持有和取消通过，到点仍需长时验证 |
| 通知/MediaSession | `dumpsys media_session` 显示当前媒体按钮会话为 `com.ldp.reader/androidx.media3.session.id.reader_audio_session`，状态 `PAUSED`，metadata `description=三体第100集`；`dumpsys notification --noredact` 显示 reader 只有一条 transport 通知，`android.title=三体第100集` | 通过 |
| 进程重启恢复 | 从书架可见项 `三体全集\|刘慈欣 精读版 解读版` 进入播放器，显示章节 `《三体2：黑暗森林》：宇宙是一片“黑暗森林”，但地球人只能选择前进`、状态 `已暂停`、进度 `03:08 / 03:32`，截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-084820-519-5940-7ebwcn.png`；持久化 `reader_audio_progress.media-chapter:c6e86452-de4e-4b48-82f3-19701b10bb66` 为 `position_188844_duration_212520`。执行 `adb shell am force-stop com.ldp.reader` 后 `pidof` 无进程；重启到书架后悬浮条恢复同一章标题，截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-084932-371-24784-8fnx4d.png`；点悬浮条回播放器仍为同一书/同一章/`已暂停`/`03:08 / 03:32`，截图 `C:\project\reader\build\ai_app_bridge_artifacts\ai_app_bridge_screenshot-20260604-085028-722-26612-d4h58o.png` | 通过 |
| 自然播完自动下一集 | 先用旧包真机复现：`三体` 第 100 集从 `20:29 / 20:49` 播到自然结束后停在 `播放完成`，`reader_audio_session` 为 `STOPPED`，确认不是手动下一集可替代的路径。修复后安装最终 debug 包，用书架真实样本 `三体全集|刘慈欣 精读版 解读版` 回到《三体2：黑暗森林》，从结尾附近自然结束；桥事件 `media_audio_auto_next` id `679` 切到《三体3：死神永生》并 `state_playing_true`。最新复测用 `有声小说三体` 第一集 route `media-chapter:46b9e50d-d2ad-4c74-aa73-26d9778f7531` 打开播放器，UI duration `03:42`，拖到结尾后事件 id `1271` 为 `media_audio_auto_next`，从 `002.《三体》有声小说预告` 切到 `003.三体_第1集_会议`，事件 id `1277` 为 `state_playing_true`，控件树显示 `003.三体 第1集 会议`。第二健康样本 `斗罗大陆` 从 `第000集` 自然结束后事件 id `6215` 为 `media_audio_auto_next`，切到 `第001集_斗罗大陆`，id `6221` 为 `state_playing_true duration_1290797` | 通过 |
| 末集自然结束停住 | 最终 debug 包中，同一书架样本的《三体3：死神永生》从 `03:16 / 03:32` 播到自然结束；桥事件 id `556` 为 `state_ended_no_next`，id `557` 为 `state_playing_false`，UI 保持当前章节并显示 `播放完成`。最新复测用 `有声小说三体` 末集 route `media-chapter:23cb463f-52c7-4476-8577-f52f363a5186`，UI duration `08:20`，拖到结尾后事件 id `1403` 为 `state_ended_no_next`，id `1404` 为 `state_playing_false`，控件树显示 `播放完成`，未越界到不存在的下一集 | 通过 |

听书核心缺口更新：有下一集时自然播完自动进入下一集已实现并通过真机验证；当前尾部样本末集自然结束也已验证为停在播放完成且不越界。`斗罗大陆` 已作为第二个健康样本补完后端唯一性、真机基础播放、手动下一集、自然下一集、书架悬浮条暂停/恢复和服务持有定时的证据；仍需补进程重启恢复、倍速和末集停止，才能算第二个完整控制闭环。当前播放器验证结束后已保持暂停状态，测试定时也已取消。

### 本轮覆盖审计

| 范围 | 已有强证据 | 当前判定 | 待扩展项 |
| --- | --- | --- | --- |
| 漫画后端链路 | 累计 15 本完成搜索、详情、目录、首章/中间章/尾部章、上一话/下一话内容解析；当前 14 本通过、1 本 `大主宰` 按 `detail_empty` 失败分类；最新扩展 summary 为 `books_5_ok_4` 和 `books_5_ok_5` | P1 样本量通过 | 后续补更多真机 UI 截图、进程恢复和公告尾章独立证据 |
| 漫画真机链路 | 5 本样本均有真实阅读页截图；代表样本补了末图成功、末页边界翻章和按章节页码恢复证据 | P0 通过 | 继续补漫画进程重启恢复的独立截图证据 |
| 听书后端链路 | `三体`、`斗罗大陆`、`雪中悍刀行`、`剑来` 已按音频唯一性通过；`庆余年`、`盗墓笔记`、`明朝那些事儿`、`凡人修仙传`、`斗破苍穹`、`诡秘之主`、`大奉打更人`、`遮天`、`完美世界` 已按重复音频、样本不可播或详情空分类失败 | P0 后端样本数达标，健康样本 4 本 | 继续补健康听书真机 UI；保留失败分类：重复音频、源不可播、DNS/网络失败、详情空 |
| 听书真机基础播放 | `三体` 已有完整证据；`斗罗大陆` 已补真实播放、手动下一集、自然下一集、URL token 和时长变化；旧 03:32 样本需重跑唯一性验证 | P0 两个健康样本基础通过 | 每本补独立截图、`media_audio_request_resolved` 事件编号和音频唯一性对比 |
| 听书完整控制闭环 | `三体` 已覆盖播放/暂停、上一集、下一集、拖动进度、15 秒快进/快退、倍速、定时入口、悬浮条、进程重启恢复、自然续播和末集停止；`斗罗大陆` 已覆盖基础播放、手动下一集、自然下一集、书架悬浮条暂停/恢复和服务持有定时；`庆余年` 旧第二样本核心控制结论撤回；追加尝试 `凡人修仙传` 当前因 `m.lrts.me` 解析 `UnknownHostException` 未拿到可播地址，不能计入通过 | P0 代表样本完整通过；第二样本增强通过、完整闭环待补 | 给 `斗罗大陆` 或另一健康样本补进程重启恢复、倍速和末集停止证据 |
| 小说回归 | 默认小说搜索、小说详情、阅读页打开、书架小说阅读均有桥状态或截图证据 | P0 通过 | 发布前再跑一次默认小说搜索和书架阅读烟测 |

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
