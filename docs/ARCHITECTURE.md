# Flitr 技术架构与开发方案 (Technical Design)

> **包名规划**：`com.aios.flitr`  
> **版本矩阵**：`minSdk = 30` (Android 11) / `targetSdk = 37` (Android 17) / `compileSdk = 37`  
> **为什么选择 `minSdk = 30`？**  
> 1. Android 11+ 在 2026 年 10 月的全球活跃设备覆盖率约 `93.5%`（Statcounter），国内主流机型覆盖率更高；  
> 2. 原生支持 `AccessibilityService.takeScreenshot()`（**免 `MediaProjection` 录屏弹窗即可静默获取屏幕帧**，是解决自绘无节点广告的杀手锏）；  
> 3. 原生完善支持多段 `GestureDescription` 曲线轨迹与 `TYPE_ACCESSIBILITY_OVERLAY`。  
>
> **为什么选择 `targetSdk = 37`？**  
> 1. Android 17（API 37）是 2026 年的当前稳定版，Android 16（API 36）设备占比已达 `41.6%`；  
> 2. Google Play 自 **2026-08-31** 起要求新应用与更新 `targetSdk ≥ 36`。该限制**只约束 `targetSdk`，不约束 `minSdk`**，因此 `minSdk = 30` 不影响上架。Flitr 虽以侧载分发，但保留后续上架可能，不宜把门槛写死；  
> 3. 无障碍自动化对系统行为变更极度敏感。主动跟随最新 `targetSdk` 并在开发期暴露问题，比在用户设备上被动踩坑更可控。  
>
> **版本策略**：`targetSdk` 与 `compileSdk` 保持同步；每次 Android 大版本发布后，先在当前稳定版上完成一轮真机回归，再决定是否跟进 `QPR` 级 minor 版本（如 37.1 / 37.2）。

---

## 1. 技术栈选型 (Tech Stack)

| 领域 | 选型方案 | 选型理由 |
| :--- | :--- | :--- |
| **开发语言** | **Kotlin 2.1+** (`Coroutines` + `Flow`) | 完美契合异步状态机、事件节流（`sample`/`debounce`）与并发取消控制 |
| **UI 与设计系统** | **Jetpack Compose + Material 3 (`1.3+`)** | 原生支持 Material You 动态取色、SegmentedButton、ModalBottomSheet 与弹簧动效 |
| **架构模式** | **MVI + Clean Architecture** | 单向数据流，确保后台 `AccessibilityService` 状态与前台 Compose UI 实时同步 |
| **依赖注入** | **Hilt (`dagger.hilt`)** | 统一管理 Service、Repository、Engine 单例生命周期 |
| **持久化存储** | **Room + Jetpack DataStore** | Room 存储触发日志与自定义规则；DataStore 存储高频读取的开关与手势参数 |
| **序列化与规则** | **Kotlinx Serialization (`json`)** | 零反射、编译期安全、毫秒级解析规则配置包 |
| **本地 OCR 增强** | **Google ML Kit Text Recognition v2 (Chinese)** | 纯端侧离线中文识别，裁剪局部 ROI 后单次推理仅需 `20~35ms`，无需联网 |
| **可选增强能力** | **Shizuku API** (`rikka.shizuku`) | 供进阶用户授权后，在国产系统杀后台时自动重写 `Secure` 设置无感拉起无障碍服务 |

---

## 2. 系统整体分层架构

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                        UI Layer (Jetpack Compose M3)                    │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌───────────────┐   │
│  │DashboardScreen│ │ RulesScreen  │ │  LogsScreen  │ │SettingsScreen │   │
│  └──────┬───────┘ └──────┬───────┘ └──────┬───────┘ └───────┬───────┘   │
│         └────────────────┴────────┬───────┴─────────────────┘           │
│                                   ▼                                     │
│                     ViewModels (MVI StateFlow / Intent)                 │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼─────────────────────────────────────┐
│                      Automation Core Engine Layer                       │
│                                                                         │
│  ┌──────────────────────┐         ┌──────────────────────────────────┐  │
│  │ EventRouter & Gate   │────────►│  SplashSkipEngine (开屏秒跳引擎)  │  │
│  │ (包名分流/时间窗/节流) │         └────────────────┬─────────────────┘  │
│  └──────────┬───────────┘                          │                    │
│             │                     ┌────────────────▼─────────────────┐  │
│             └────────────────────►│ VideoAdStateMachine (视频流状态机)│  │
│                                   │ - 无倒计时秒划                    │  │
│                                   │ - 有倒计时等待 & 中途弹窗处理      │  │
│                                   └────────┬───────────────┬─────────┘  │
│                                            │               │            │
│             ┌──────────────────────────────▼──┐   ┌────────▼─────────┐  │
│             │ DualPerceptionProvider (双模感知)│   │HumanGestureEngine│  │
│             │ L1: Accessibility UI Node Tree  │   │(贝塞尔曲线/随机延)│  │
│             │ L2: Silent Screenshot + ROI OCR │   └──────────────────┘  │
│             └─────────────────────────────────┘                         │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼─────────────────────────────────────┐
│                     System & Data Infrastructure Layer                  │
│  ┌─────────────────────────┐  ┌──────────────────┐  ┌────────────────┐  │
│  │ FlitrAccessibilitySvc   │  │ OverlayPillMgr   │  │ Room & DataStore│ │
│  │ (无障碍宿主 + 触摸避让)  │  │ (免权限状态胶囊)  │  │ (规则库/日志库) │  │
│  └─────────────────────────┘  └──────────────────┘  └────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 核心模块工程实现详解

### 3.1 无障碍事件风暴治理 (`EventRouter & Gate`)
**痛点**：视频播放时进度条跳动、弹幕滚动会每秒触发数十次 `TYPE_WINDOW_CONTENT_CHANGED`，若每次都遍历节点树会导致手机严重掉帧、发烫。

**工程方案**：
1. **双通道分流（Fast Path vs Throttled Path）**：
   - **包名变更检测**：记录 `currentForegroundPackage`（过滤输入法 `InputMethod`、系统状态栏 `com.android.systemui` 以及应用自身的悬浮层干扰）。
   - **开屏通道（6秒瞬态门控）**：仅当 `currentForegroundPackage` 发生变化后的 `0 ~ 6000ms` 内开启开屏检测（节流间隔 `120ms`）；一旦成功点击跳过或超过 6 秒，立即关闭开屏扫描器，平时功耗为零。
   - **视频流托管通道（目标白名单激活）**：仅当 `currentForegroundPackage` 命中用户已开启视频托管的包名（如红果短剧 `com.phoenix.read`）时才唤醒 `VideoAdStateMachine`，并通过 Kotlin `Channel(Channel.CONFLATED)` + `sample(300.milliseconds)` 进行采样限流。
2. **浅层剪枝遍历**：遍历 `AccessibilityNodeInfo` 时限制最大递归深度（默认 `maxDepth = 18`），跳过 `isVisibleToUser == false` 及宽高为 0 的不可见子树。

---

### 3.2 双模广告感知引擎 (`DualPerceptionProvider`)

红果短剧等字节系应用大量混用原生 View、穿山甲广告 SDK 以及 Lynx 自渲染引擎，有时会出现**节点树存在但 `text` 属性为空**的情况。为此设计 **L1 + L2 双模感知**：

#### L1：UI 节点树高速解析（主力模式，耗时 `< 2ms`）
配置无障碍服务标志位：
```xml
android:accessibilityFlags="flagDefault|flagReportViewIds|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews"
android:canPerformGestures="true"
android:canTakeScreenshot="true"
android:canRetrieveWindowContent="true"
```
提取当前屏幕快照结构 `ScreenSnapshot`：
- 收集所有可见文本列表（含 `node.text` 与 `node.contentDescription`）及其屏幕坐标 `Rect`；
- 匹配关键语义槽位：
  - `adBadges`：匹配 `^(广告|Sponsored|.*广告.*)$` 或特定商业组件 ID；
  - `ctaButtons`：匹配 `查看详情|立即下载|去看看|了解详情|点击安装|领券购买`；
  - `countdownToken`：正则提取 `(?:还剩|广告|观看)?\s*(\d{1,3})\s*(?:s|S|秒)(?:后可?(?:领取|解锁|跳过|划走|继续))?`；
  - `rewardDoneToken`：匹配 `已领取|领取成功|已完成|可划走|跳过广告`；
  - `interruptDialogBtn`：匹配中途挽留弹窗按钮 `继续观看|开心收下|去领取`；
  - `dramaEpisodeSafeGuard`：匹配正片保护特征（如同时存在 `第\s*\d+\s*集` + `选集` 且无 `adBadges`）。

#### L2：静默局部 ROI OCR 兜底（增强模式，耗时 `20~35ms`）
当在视频托管 App 中停留在一个疑似无正片标识的页面超过 `1.2s`，且 L1 节点树提取到的文本极少（疑似自绘 Canvas 广告）时自动触发：
1. 调用 Android 11 原生 `takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)` 获取 `HardwareBuffer` 转 `Bitmap`（注意系统限制最快 1 秒 1 次，完全满足倒计时采样需求）；
2. **ROI 局部裁剪优化（关键性能点）**：不识别整张大图！仅裁剪**顶部状态区（高 15%，含激励倒计时/跳过按钮）**与**底部信息栏（高 28%，含“广告”角标与转化按钮）**拼接成小图送入端侧 ML Kit 中文识别器；
3. 将识别出的文本块与坐标回填至统一的 `ScreenSnapshot` 数据模型，上层状态机无感复用同一套判定逻辑。

---

### 3.3 视频流广告智能状态机 (`VideoAdStateMachine`)

这是满足**“没有倒计时直接划过去，有倒计时等播完再划过去”**的核心大脑：

```kotlin
sealed interface VideoHostState {
    data object Idle : VideoHostState                                  // 非目标App或已暂停
    data object WatchingNormalVideo : VideoHostState                   // 正常剧集播放中
    data class DebouncingAd(val firstSeenAt: Long) : VideoHostState    // 疑似广告，等待450ms稳定帧
    data class WaitingCountdown(
        val initialSeconds: Int,
        val remainingSeconds: Int,
        val deadlineTimestamp: Long,
        val isModalOverlayAd: Boolean                                  // 区分全屏激励弹窗 vs 信息流内嵌广告
    ) : VideoHostState
    data class ExecutingAction(val actionType: ActionType) : VideoHostState
    data class UserTouchCooldown(val resumeAt: Long) : VideoHostState  // 用户手动触屏避让中
}
```

**关键边界情况（Corner Cases）处理**：
1. **为什么必须有 `450ms DebouncingAd` 观察期？**
   刚划入一个有倒计时的广告页时，第 0~200ms 往往底部的“广告 / 查看详情”先渲染出来，而右上角的“15秒后可领奖”异步加载慢半拍。如果不做 450ms 稳定帧二次确认，会把“有倒计时广告”误判为“无倒计时广告”直接划走，导致解锁剧集失败！
2. **倒计时卡住或文本消失怎么判断“播完了”？**
   双保险机制：
   - **显式信号**：检测到节点出现 `已领取 / 已完成 / 可划走` 或独立关闭按钮 `×` 变为可点击；
   - **隐式计时器推算 + 复核**：首次读到 `15s` 时同步设定本地协程计时器 `15.5s`；期间每 `1s` 刷新最新读数校准；当读数归零或倒计时控件消失且超过预计完成时间，判定播放完毕。
3. **播完后是“点关闭”还是“往上划”？**
   - 若当前页面属于**激励视频独立 Activity**（如穿山甲 `TTRewardVideoActivity` / 快手激励 Activity）或右上角存在明确的小尺寸关闭按钮 `[×]`：先执行点击关闭回到短剧主播放器；回到主播放器后再次检查，若仍停留在信息流广告卡片上，再补一次上滑！
   - 若当前页面本身就在短剧主播放器的上下滑 ViewPager 流中：倒计时结束后直接调用拟人上滑手势切入下一集！
4. **划完没划动（页面卡顿或手势被吞）怎么办？**
   记录上滑前的页面特征指纹（文本摘要 Hash）。上滑后 `700ms` 复核指纹：若完全一致且未检测到用户触屏，调整滑动起止跨度补偿重试 `1` 次；若连续 `2` 次未翻页，标记该页等待人工介入，绝不死循环狂划。

---

### 3.4 拟人化贝塞尔手势引擎 (`HumanGestureDispatcher`)

为防止被短视频/短剧平台的反自动化风控识别（固定坐标直线滑动极易被标记异常），所有自动上滑与坐标点击均经过拟人化处理：

```kotlin
class HumanGestureDispatcher(private val service: AccessibilityService) {

    /**
     * 生成拟人大拇指上滑轨迹（右手中下部自然微弧度上挑）
     */
    fun performNaturalSwipeUp(screenWidth: Int, screenHeight: Int, onComplete: () -> Unit) {
        val random = java.util.Random()

        // 1. 起点：屏幕中偏右下方自然拇指热区 (宽 55%~78%, 高 72%~82%)
        val startX = screenWidth * (0.55f + random.nextFloat() * 0.23f)
        val startY = screenHeight * (0.72f + random.nextFloat() * 0.10f)

        // 2. 终点：向上滑动约 48%~60% 屏幕高度，水平方向带轻微自然偏移
        val endX = startX + (random.nextFloat() - 0.35f) * (screenWidth * 0.08f)
        val endY = screenHeight * (0.18f + random.nextFloat() * 0.10f)

        // 3. 二阶贝塞尔控制点：模拟大拇指关节旋转产生的轻微弧线
        val controlX = (startX + endX) / 2f + (random.nextFloat() - 0.5f) * (screenWidth * 0.10f)
        val controlY = (startY + endY) / 2f

        val path = android.graphics.Path().apply {
            moveTo(startX, startY)
            quadTo(controlX, controlY, endX, endY)
        }

        // 4. 滑动时长：服从正态分布钳制在 185ms ~ 310ms 之间
        val durationMs = (235 + random.nextGaussian() * 30).toLong().coerceIn(185L, 310L)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onComplete()
        }, null)
    }
}
```

**用户手指触屏避让实现**：
在 `TYPE_ACCESSIBILITY_OVERLAY` 边缘胶囊外或利用 `AccessibilityEvent.TYPE_VIEW_SCROLLED`（非本服务发起手势期间触发的滚动）+ `flagRequestTouchExplorationMode` 替代方案（**注意：绝不开启 `TouchExplorationMode`，否则会变成盲人读屏双指操作模式！** 正确做法是在无障碍窗口添加 `1px×1px` 附带 `FLAG_WATCH_OUTSIDE_TOUCH` 的透明哨兵 View，每次用户手指触碰屏幕任意位置都会立即收到 `MotionEvent.ACTION_DOWN / ACTION_OUTSIDE`，零侵入感知用户真实触摸并刷新 `3秒` 冷却锁！）。

---

### 3.5 可扩展 JSON 规则 DSL 设计

支持内置规则与外部 JSON 导入，兼顾开屏跳过与视频流托管配置。以下为**红果短剧**预设规则样例：

```json
{
  "schemaVersion": 1,
  "videoScenarios": [
    {
      "packageName": "com.phoenix.read",
      "appName": "红果短剧",
      "enabled": true,
      "debounceMs": 450,
      "reactionDelayRangeMs": [380, 820],
      "ocrFallbackEnabled": true,
      "adIndicators": {
        "anyTextRegex": [
          "^广告$",
          "^查看详情$",
          "^立即下载$",
          "^去看看$",
          "^了解详情$",
          ".*秒后可?(领取|解锁|继续|跳过).*"
        ],
        "anyViewIdSuffix": [
          "tt_reward_ad_countdown",
          "ad_label",
          "commerce_ad_tag"
        ]
      },
      "normalDramaSafeguards": {
        "allTextRegex": ["第\\s*\\d+\\s*集", "选集"],
        "excludeWhenPanelOpenRegex": ["发表评论.*", "选集\\s*\\(\\d+.*"]
      },
      "countdownRules": {
        "extractRegex": "(?:还剩|观看|广告)?\\s*(\\d{1,3})\\s*(?:s|S|秒)",
        "completedTextRegex": ["已领取.*", "已完成", "可划走", "^跳过$"],
        "keepWatchingDialogBtnRegex": ["继续观看", "开心收下", "去领取"],
        "modalCloseViewIdSuffix": ["tt_video_ad_close_layout", "iv_close", "close_btn"],
        "maxWaitSeconds": 40
      }
    }
  ],
  "splashRules": {
    "globalSdkSkipViewIds": [
      "tt_splash_skip_btn",
      "splash_container_skip",
      "ksad_splash_circle_skip_view",
      "beizi_ad_splash_skip"
    ],
    "heuristicSkipRegex": "^(\\d+\\s*[sS秒]?\\s*)?(跳过|跳过广告|Skip)(\\s*\\d+[sS秒]?)?$",
    "maxNodeScreenAreaRatio": 0.12
  }
}
```

---

### 3.6 国产 ROM 保活与 Android 13+ 受限设置适配

这是决定工具类 App 能否长期稳定运行的关键工程细节：
1. **Android 13+「受限设置（Restricted Settings）」引导**：
   - 用户直接安装 APK 时，系统默认置灰无障碍开关。
   - App 内检测 `Build.VERSION.SDK_INT >= 33` 且无障碍无法开启时，提供一键跳转「应用详情页 (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`)」并展示动图指引点击右上角 `⋮ -> 允许受限设置`。
2. **ROM 差异化保活体检中心**：
   - 自动识别 `Build.MANUFACTURER`（OPPO/OnePlus `ColorOS`、Xiaomi `HyperOS`、vivo `OriginOS`、Honor `MagicOS`）：
     - 第一步：请求 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（电池设为“无限制/允许完全后台行为”）；
     - 第二步：引导开启系统「自启动」与最近任务列表「加锁」；
     - 第三步（可选）：常驻极简通知栏磁贴（前台服务 `foregroundServiceType="specialUse"`），以及为高级玩家提供 **Shizuku 保活守护**。
     - 注意：Android 14+ 使用 `specialUse` 类型时，必须在 Manifest 中声明 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 属性并说明用途，否则前台服务启动会抛异常。
3. **新系统行为变更核对清单（每升级一次 `targetSdk` 必做）**：
   - 前台服务：`foregroundServiceType` 声明是否完整、`specialUse` 子类型属性是否齐全；
   - 悬浮窗/无障碍 Overlay：`TYPE_ACCESSIBILITY_OVERLAY` 与新版本窗口策略是否冲突；
   - 截图能力：`takeScreenshot()` 在目标版本与国产 ROM 上的可用性、频率限制与失败回调；
   - 后台与自启动：后台活动限制、电池策略、JobScheduler 配额是否影响守护逻辑；
   - 界面适配：新版本默认强制边到边（edge-to-edge）后，Compose 页面与状态胶囊的 inset 处理。
   > 具体条目以官方 `Behavior changes: apps targeting Android N` 页面为准，本节仅列出与本项目强相关的类别。

---

## 4. 工程目录与模块结构规划

采用清晰的单 `app` 模块分包（或按需拆分为 Multi-module），便于快速迭代：

```text
flitr/
├── app/
│   ├── src/main/
│   │   ├── assets/
│   │   │   └── default_rules.json           # 内置开屏SDK特征库 + 红果短剧等默认视频规则
│   │   ├── java/com/aios/flitr/
│   │   │   ├── FlitrApp.kt                  # Hilt Application
│   │   │   ├── ui/                          # Jetpack Compose Material 3 界面层
│   │   │   │   ├── theme/                   # Color.kt (Dynamic Color), Type.kt, Theme.kt
│   │   │   │   ├── navigation/              # FlitrNavHost.kt (底部4栏导航)
│   │   │   │   ├── dashboard/               # 控制台页 & 保活引导 BottomSheet
│   │   │   │   ├── rules/                   # 视频托管配置页(红果短剧详情) & 开屏白名单
│   │   │   │   ├── logs/                    # 触发审计日志页 & 误触标记
│   │   │   │   └── settings/                # 拟人手势参数、OCR开关、主题设置
│   │   │   ├── service/                     # 系统服务宿主层
│   │   │   │   ├── FlitrAccessibilityService.kt
│   │   │   │   ├── TouchOutsideWatcher.kt   # 1x1 哨兵感知用户手指触屏避让
│   │   │   │   ├── OverlayPillController.kt # TYPE_ACCESSIBILITY_OVERLAY 状态胶囊
│   │   │   │   └── QuickTileService.kt      # 通知栏快捷开关/快照抓取磁贴
│   │   │   ├── engine/                      # 自动化核心业务层
│   │   │   │   ├── EventRouter.kt           # 包名切换门控与协程节流器
│   │   │   │   ├── splash/
│   │   │   │   │   └── SplashSkipEngine.kt  # 开屏三级匹配与防假按钮校验
│   │   │   │   ├── video/
│   │   │   │   │   ├── VideoAdStateMachine.kt # 视频流无倒计时/有倒计时状态机
│   │   │   │   │   └── ScreenSnapshot.kt    # 统一页面语义快照模型
│   │   │   │   ├── perception/
│   │   │   │   │   ├── NodeTreeParser.kt    # L1 节点树高速提取
│   │   │   │   │   └── SilentRoiOcrParser.kt# L2 takeScreenshot + MLKit 局部中文OCR
│   │   │   │   └── gesture/
│   │   │   │       └── HumanGestureDispatcher.kt # 二阶贝塞尔拟人滑动与微偏点击
│   │   │   └── data/                        # 数据持久层
│   │   │       ├── db/                      # Room (ActionLogDao, CustomRuleDao)
│   │   │       ├── prefs/                   # DataStore (SettingsRepository)
│   │   │       └── model/                   # Kotlinx Serialization JSON 规则实体
│   │   └── res/
│   │       └── xml/accessibility_service_config.xml
│   └── build.gradle.kts
├── docs/
│   ├── PRD.md
│   └── ARCHITECTURE.md
└── settings.gradle.kts
```

---

## 5. 开发实施里程碑 (Development Plan)

### Phase 1：工程脚手架与 Material Design 3 UI 骨架（预计 2~3 天）
- 初始化 Gradle Kotlin DSL（当前稳定版 `AGP`，需支持 `compileSdk = 37`；Kotlin 2.1+、Compose BOM、Hilt、Room、DataStore）；
- 实现完整 Material 3 动态取色主题（`Theme.kt`）与底部 4 栏导航框架；
- 完成控制台卡片、Android 13+ 受限设置与电池优化分步检测引导面板；
- 打通 `FlitrAccessibilityService` 生命周期与前台 UI `StateFlow` 状态实时联动。

### Phase 2：开屏广告秒跳引擎与日志审计（预计 2~3 天）
- 实现 `EventRouter` 的前台包名识别与 6 秒开屏时间窗门控；
- 落地 `SplashSkipEngine`（SDK ID 库 + 启发式跳过匹配 + 屏幕面积占比防欺诈校验 + 系统/支付白名单）；
- 接入 Room 数据库记录跳过日志，在「日志」Tab 实时展示并统计节省时间。

### Phase 3：红果短剧等视频流智能托管状态机（核心攻坚，预计 4~5 天）
- 实现 `NodeTreeParser` 与 `VideoAdStateMachine`：
  - 450ms 稳定帧防误判；
  - 无倒计时信息流广告 -> 拟人贝塞尔曲线秒划；
  - 有倒计时激励/解锁广告 -> 实时读秒追踪、中途“继续观看”弹窗自动点击、倒计时归零自动关闭或上滑；
- 实装 `TouchOutsideWatcher`（手指触屏 3 秒冷却避让）与 `OverlayPillController`（应用内边缘半透明倒计时状态胶囊）；
- 针对红果短剧真实广告样本做真机联调（覆盖推荐流穿插广告、剧集解锁激励视频广告、尾帧停留页）。

### Phase 4：静默局部 OCR 兜底与规则拾取器打磨（预计 2~3 天）
- 集成 `takeScreenshot` + 局部 ROI 裁剪 + 离线 ML Kit 中文识别，攻克部分自绘 Canvas 混淆广告漏检问题；
- 开发通知栏磁贴「一键抓取页面节点快照」，支持导出 JSON 以便快速适配更多短剧/视频 App（如河马剧场、番茄小说短剧、快手/抖音短剧流）。

---

## 6. 实现现状与设计偏差

本节记录**已经落地的实现**与本文档原始设计的差异。文档保留设计意图，偏差在此显式说明，避免后续维护者按旧假设理解代码。

### 6.1 实际构建配置

| 项 | 文档原始值 | 实际值 | 原因 |
| --- | --- | --- | --- |
| AGP | "AGP 8.7+" | **9.5.1** | AndroidX 最新版（core 1.19 / compose 1.12 / lifecycle 2.11）硬性要求 AGP ≥ 9.1 且 compileSdk ≥ 37 |
| Gradle | 未指定 | **9.9.1** | AGP 9.5 要求 |
| compileSdk | 未指定 | **37** | 同上；SDK 中对应 `platforms;android-37.0` |
| targetSdk | 37 | **37** | 与文档一致 |
| Kotlin 插件 | `org.jetbrains.kotlin.android` | **已移除** | AGP 9 内置 Kotlin 支持，再应用该插件会直接构建失败 |

> 实测提示：本机 SDK 仓库里没有不带小版本号的 `platforms;android-37`，只有 `android-37.0` / `37.1` / `37.2`。AGP 的 `compileSdk = 37` 会自动解析到 `37.0`。

### 6.2 架构层面的三处简化

| 原设计 | 实际实现 | 理由 |
| --- | --- | --- |
| Hilt 依赖注入 | 手写 `ServiceLocator` | 依赖图只有 3 个仓库 + 1 个应用作用域，手写容器更透明、启动更快，且省掉注解处理器这一构建脆弱点 |
| Room（日志 + 规则） | DataStore（设置）+ JSONL 追加文件（日志） | 审计日志是"只追加、倒序读取、有上限"的数据，JSONL 更简单；规则本身是 JSON 文件，无需关系存储 |
| navigation-compose | 手写 4 页签状态切换 | 只有 4 个固定页签、无深链需求，引入导航图是净负担 |

迁移成本都很低：调用点集中在 `ServiceLocator` 与 `FlitrRoot`，后续要换回 Hilt / Room / Navigation 不需要动引擎层。

### 6.3 引擎层的关键实现选择

1. **引擎零 Android 依赖**
   `engine/model`、`engine/video`、`engine/splash`、`engine/rules` 全部是纯 Kotlin，不 import 任何 `android.*`。
   这是能对状态机做离线单元测试的前提，也是后续做「广告样本回放」的基础。

2. **正片保护压过弱广告信号**
   只有「广告」两个字的文本属于弱信号。当页面同时存在集数 + 选集特征时，弱信号不触发动作；
   但 SDK 控件 id、倒计时、完成文案属于强信号，即使浮在正片之上也会正常处理。
   取舍依据：误划正片的代价远高于漏划一条广告。

3. **滑动复核的返回值语义**
   `verifyPendingSwipe` 返回 `Evaluation?` 而不是命令列表 —— 非 null 表示"本次事件已被复核消费"。
   早期实现返回空列表，导致标记 `NeedsManual` 之后状态被后续分支覆盖回 `DebouncingAd`，变成无限重试。
   该缺陷由单元测试发现并修复。

4. **首次动作不被冷却误挡**
   `lastActionAt` 用可空表示"从未执行过"。若用 `0L` 作初值，在 `now < 1000ms` 的场景（测试与刚开机）首次动作会被动作冷却吞掉。

### 6.4 尚未实现

| 项 | 现状 |
| --- | --- |
| 端侧 OCR | `OcrRecognizer` 接口 + `UnavailableOcrRecognizer` 占位；`SilentRoiOcrParser` 的截图、ROI 裁剪、坐标回填管线已完整，只缺识别引擎 |
| Shizuku 保活 | 未接入 |
| 快照拾取器 | 未做 UI；目前只能导入整份规则文件 |
| 误触自动生成豁免规则 | 只做标记与统计，未反写规则 |
| 真机联调 | 全部阈值（450ms 稳定帧、12% 面积比、倒计时正则）均来自设计推导，**未经真实广告样本校准** |

### 6.5 验证方式

```bash
./gradlew :app:testDebugUnitTest   # 19 个用例, 覆盖状态机 12 + 开屏引擎 7
./gradlew :app:assembleDebug
```

已通过 `aapt2 dump permissions` 确认最终 APK 不含 `INTERNET` 权限。
真机验证与真实样本校准仍是下一步的主要工作。

