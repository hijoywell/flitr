# Flitr

> 纯本地运行、零联网权限的 Android 广告场景自动化工具。  
> 聚焦两件事：**开屏广告秒跳** 与 **短剧/短视频流广告智能托管**。

## 当前状态

| 项 | 状态 |
| --- | --- |
| 编译 | ✅ `assembleDebug` / `assembleRelease` 均通过 |
| Lint | ✅ `lintVitalRelease` 通过 |
| 单元测试 | ✅ 22 个用例全部通过（状态机 16 + 开屏引擎 7，含真机样本回归） |
| 真机验证 | ⚠️ 部分完成 —— 见 [docs/DEVICE-VALIDATION.md](docs/DEVICE-VALIDATION.md) |
| 端侧 OCR | ✅ 已接入 ML Kit 内置中文模型（完全离线） |
| 产物 | debug `78.93 MB` / release `15.64 MB`（R8 收缩 + arm64 only） |
| INTERNET 权限 | ✅ 不存在（ML Kit 注入的权限已在清单中移除并验证） |

> debug 包体积大是因为未做代码收缩且带了 Compose 调试工具链；release 走 R8 后只有 2.46 MB，
> 这才是真实分发体积。release 产物目前未签名（`app-release-unsigned.apk`），需要自己的 keystore 签名后才能安装。

构建产物实测元信息：

```text
package: com.aios.flitr.debug  versionName 0.1.0-debug
minSdkVersion   30   (Android 11)
targetSdkVersion 37  (Android 17)
compileSdkVersion 37
uses-permission: 仅 AndroidX 自动添加的 DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
```

**没有 `android.permission.INTERNET`** —— 这不是文档承诺，而是可以用 `aapt2 dump permissions` 直接核验的事实。

## 环境要求

- JDK 21
- Android SDK Platform 37（`platforms;android-37.0`）+ Build-Tools 37.0.0
- Gradle 9.9.1（仓库内已带 wrapper）
- AGP 9.5.1

> AGP 9 起 Kotlin 支持已内置，**不要再应用 `org.jetbrains.kotlin.android` 插件**，否则构建会直接报错提示移除。

## 构建

```bash
# 1. 指定 SDK 位置（或写进 local.properties 的 sdk.dir）
export ANDROID_HOME=/path/to/android-sdk

# 2. 编译
./gradlew :app:assembleDebug

# 3. 跑核心逻辑回归（不需要设备）
./gradlew :app:testDebugUnitTest
```

国内网络如果卡在 Gradle 发行版下载，可把 `gradle/wrapper/gradle-wrapper.properties` 里的
`distributionUrl` 换成镜像，例如
`https\://mirrors.cloud.tencent.com/gradle/gradle-9.9.1-bin.zip`。

## 上手步骤

1. 安装 APK
2. 打开 App → 仪表盘点「激活」→ 跳转系统无障碍设置，开启 **Flitr 广告智划**
3. Android 13+ 若提示「受限制的设置」，需到应用信息页右上角允许（设置页有直达入口）
4. 建议把电池策略设为「无限制」，否则国产 ROM 会杀后台
5. 回到 App，仪表盘状态应变为「运行中」

## 已实现

**引擎**

- 开屏三级匹配：广告 SDK 控件 id → 启发式「跳过」语义 → 面积占比防欺诈
- 视频广告状态机：稳定帧确认 → 无倒计时秒划 / 有倒计时读秒 → 播完点关闭或上滑
- 中途挽留弹窗自动点击「继续观看」
- 正片保护：集数 + 选集特征存在时绝不介入
- 滑动后页面指纹复核，无效则补偿重试一次，连续两次失败停止介入
- 用户触屏避让：1×1 `FLAG_WATCH_OUTSIDE_TOUCH` 哨兵 + 3 秒冷却
- 拟人贝塞尔上滑：随机起止坐标、弧度微偏、正态分布时长

**界面**（4 个页签，Flitr 视觉体系）

- 仪表盘：守护状态、拦截总数、双引擎统计与趋势曲线、工作模式、托管卡片
- 场景规则：托管策略说明、应用级开关、开屏规则详情、白名单搜索
- 触发日志：按类型筛选、动作标签、耗时、一键标记误触
- 工具设置：3 种明暗模式 × 6 套强调色、手势参数滑块、服务体检、规则导入导出

**其它**

- 规则 JSON 可导入/导出/恢复默认，内置红果短剧、快手极速版、抖音三套场景
- 无障碍 Overlay 状态胶囊（免悬浮窗权限）
- 通知栏快捷磁贴（一键暂停）
- 常驻通知

## 未实现 / 后续

| 项 | 说明 |
| --- | --- |
| 端侧 OCR | `OcrRecognizer` 接口已就位，`UnavailableOcrRecognizer` 为占位。接入时只需提供实现，感知管线无需改动 |
| Shizuku 保活 | 未接入 |
| 快照拾取器 UI | 目前只能通过规则文件导入适配新 App |
| 误触自动生成豁免规则 | 目前只做标记，未自动生成规则 |
| 真机联调 | 红果短剧等真实广告样本尚未验证，阈值与正则需按实际节点调整 |

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

`app/src/test/java/com/aios/flitr/engine/VideoAdStateMachineTest.kt` 覆盖：

- 无倒计时广告：先观察稳定帧再上滑
- 有倒计时广告：进入读秒，绝不提前划走
- 倒计时归零：有弹窗点关闭、无弹窗上滑
- 正片保护：正片特征存在时，即使出现「广告」字样也不动作
- 用户触屏冷却期内完全静默，冷却结束恢复判断
- 中途挽留弹窗自动点击「继续观看」
- 滑动未生效 → 补偿一次 → 仍无效则停止介入
- 开屏引擎：SDK id 优先、面积超阈值拒绝、输入框拒绝、长文本拒绝

## 目录

```text
app/src/main/java/com/aios/flitr/
├── engine/            自动化核心（无 Android 依赖，可离线测试）
│   ├── model/         屏幕语义快照
│   ├── rules/         规则模型与仓库
│   ├── splash/        开屏跳过引擎
│   ├── video/         广告判定 + 视频状态机
│   ├── gesture/       拟人贝塞尔手势
│   ├── perception/    节点树解析 / 静默截图 OCR
│   └── EventRouter.kt 事件分流与功耗门控
├── service/           无障碍服务 / 状态胶囊 / 触摸哨兵 / 快捷磁贴
├── data/              DataStore 设置 + JSONL 日志
├── ui/                Compose 界面（theme / components / 4 个页面）
└── di/                轻量服务定位器
```

设计文档见 [docs/PRD.md](docs/PRD.md) 与 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，
实现与原始设计的偏差记录在 ARCHITECTURE 第 6 节。
