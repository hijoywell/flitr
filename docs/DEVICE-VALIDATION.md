# 真机验证记录（Android 17 / ColorOS）

> 验证时间：2026-11-11  
> 设备：OnePlus PYB110 · Android 17（API 37）· ColorOS `PYB110PRE_17.0.1.202(CN01)` · 1272×2772 @560dpi  
> 被测应用：红果短剧 `com.phoenix.read`

这份文档记录**实测结论**，与 `PRD.md` / `ARCHITECTURE.md` 的设计假设对照。
凡是设计假设被真机推翻的地方，都在这里标注并说明改法。

---

## 1. 已确认可用的能力

| 能力 | 结论 | 证据 |
| --- | --- | --- |
| 无障碍服务绑定 | ✅ | `dumpsys accessibility` 显示 `Bound services: Flitr 广告智划` |
| 应用内 Overlay 胶囊（免悬浮窗权限） | ✅ | 截图可见「正片保护中 / 静默等待」胶囊正常渲染 |
| `FLAG_WATCH_OUTSIDE_TOUCH` 触摸哨兵 | ✅ | `adb shell input swipe` 后胶囊切到「已避让用户操作 · 3秒冷却」 |
| 正片保护判定 | ✅ | 第 4/5/6/7/8/9/92 集页面均 `dramaProtected=true`，连续 5 集零误触 |
| 节点树解析 | ✅ | 单页解析出 24～141 个节点 |
| APK 无 INTERNET 权限 | ✅ | `aapt2 dump permissions` 仅见 AndroidX 自动添加的 receiver 权限 |

## 2. 实测推翻的设计假设

### 2.1 `rootInActiveWindow` 在红果播放页稳定返回 null

**这是最严重的一条。** 原实现：

```kotlin
val root = rootInActiveWindow ?: return   // 静默退出, 引擎等于没运行
```

实测在 `ShortSeriesActivity` 上 `rootInActiveWindow` 为 null，导致整个引擎在红果上**从未真正工作过**，
而日志里没有任何报错——属于"静默失效"。

**改法**：增加退化路径，从 `windows` 里挑 active 窗口取 root。已在 `FlitrAccessibilityService.acquireRoot()` 实现。

### 2.2 父节点不可见 ≠ 子树不可见

原实现遇到 `isVisibleToUser == false` 就 `continue` 跳过整棵子树。实测红果的广告页在这样剪枝后
**一条文本都拿不到**。

**改法**：只过滤"是否记录本节点"，不影响继续下钻，规模由 `maxNodes` 兜底。

### 2.3 红果的 `resource-id` 大量被混淆

实测样本：

```text
com.phoenix.read:id/action_bar_root    ← 可读
com.phoenix.read:id/relate_reserve_tv  ← 可读
com.phoenix.read:id/ao5 / h1 / gg_ / kfs / aq9 / dq4   ← 混淆短 token
```

规则里 `anyViewIdSuffix` 配的 `tt_reward_ad_countdown` / `ad_label` 在红果上**永远不可能命中**。
红果只能靠文本匹配，这一点必须写进规则设计说明。

### 2.4 广告主体内容完全不在节点树里

广告页实测节点树（共 24 个节点）：

```text
009 | '返回'                | id=kfs | 154,143,1216,297
020 | '上滑继续观看短剧'      | id=aq9 | 482,2639,874,2705
017 | ''                    | id=dq4 | 1,0,1272,2576   ← 占屏 92.9% 的空白容器
```

截图里能看到的「广告」角标、商品卡、「立即领取」、价格、直播间按钮，**节点树里一条都没有**。

**结论**：红果的广告内容画在 SurfaceView / Canvas 里，**纯节点树方案必然漏检**。
PRD 里把 OCR 定位为 P1「增强」是低估了——在红果这类 App 上它是**必需能力**。

### 2.5 `继续观看` 用 containsMatchIn 会误命中滚动提示

广告页底部有一行「上滑继续观看短剧」，被 `keepWatchingDialogBtnRegex` 里的 `继续观看` 命中，
于是引擎会把这条**滚动提示**当成挽留弹窗按钮去点击。

**改法**：规则改为精确锚定 `^继续观看$` / `^立即继续观看$` / `^开心收下$`。

### 2.6 `allMatch` 的拼接匹配让锚点规则永久失效

原实现把整页文本拼成一个字符串再逐个 `containsMatchIn`，导致 `^返回$` 这类带锚点的规则
**在任何页面上都不可能命中**（拼接串的开头结尾不可能是目标文本）。

**改法**：改为"每个 pattern 命中任意一个节点"的语义。这也是新增的「组合特征」规则能工作的前提。

### 2.7 ColorOS 会过滤第三方应用的 DEBUG 日志

`Log.d` 打出去 logcat 里一条都没有，换成 `Log.i` 才可见。这会让所有基于日志的排查失效，
而且现象是"代码明明打了日志却没有输出"，很容易误判为代码没执行。

### 2.8 事件风暴实测速率

红果视频页 `TYPE_WINDOW_CONTENT_CHANGED` 实测 **3.9 ～ 11.6 次/秒**，`contentChangeTypes=1`（SUBTREE）。
仅靠采样间隔压不住，已加 `contentChangeTypes` 过滤（跳过明确非文本类变化）+ 视频通道 400ms 采样。

---

## 3. 页面样本对照表

| 维度 | 广告页（电商信息流） | 正片页（第 92 集） | 剧场列表页 | 推荐流页 |
| --- | --- | --- | --- | --- |
| Activity | ShortSeriesActivity | ShortSeriesActivity | MainFragmentActivity | MainFragmentActivity |
| 根节点类 | `FrameLayout` | `ViewGroup` | `FrameLayout` | — |
| 节点数 | **24** | **80** | 141 | 72～73 |
| `返回` | ✅ | ❌ | ❌ | ❌ |
| `上滑继续观看短剧` | ✅ | ❌ | ❌ | ❌ |
| `第 N 集` | ❌ | ✅ | ❌ | ❌ |
| `选集` | ❌ | ✅ | ❌ | ❌ |
| 视频/广告内容可见 | ❌ | 部分（标题/集数） | ✅（列表项） | ❌ |
| 判定结果 | 改规则前 `isAd=false` | `dramaProtected=true` | 无信号，静默 | 无信号，静默 |

**广告页判别组合**（已落地为规则）：

```json
"allTextRegex": ["^返回$", "^上滑继续观看短剧$"]
```

必须同时满足，且不能命中正片特征。单看其中任意一条都不够——`返回` 在很多页面都有，
`上滑继续观看短剧` 目前只在广告页观测到，但样本量仅 1，需要继续收集。

---

## 4. 尚未验证的部分

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 真实广告的自动划走 | ❌ 未复现 | 规则修复后没再遇到同一类广告页，`dispatchGesture` 的端到端执行未经真机验证 |
| 有倒计时解锁广告 | ❌ 未抓到 | 用户提到过倒计时页，但落盘样本是静态电商广告，倒计时文案格式仍未确认 |
| feed 内嵌广告 | ⚠️ 判定为不可检测 | 推荐流页节点树只有 tab 与备案号，视频内容不可见 |
| 开屏广告跳过 | ❌ 未验证 | 红果冷启动未出现开屏广告 |
| **OCR 真机运行** | ⚠️ 代码已接入，**未在设备上跑通** | 见下方 4.1 |
| 长时间功耗 | ❌ 未测 | 只在短时观察中看到 GPU 占用 0.2% |

### 4.1 OCR 接入情况（2026-11-11 追加）

已接入 ML Kit 内置中文模型 `com.google.mlkit:text-recognition-chinese:16.0.1`，管线为：

```text
takeScreenshot → HardwareBuffer → ARGB_8888
  → 裁顶部 15% + 底部 28% → 竖向拼接成一张小图
  → ML Kit 一次推理（按行输出）
  → 坐标换算回屏幕坐标系 → 合并进 ScreenSnapshot
```

接入过程中确认的两件事：

1. **ML Kit bundled 版会往合并清单里注入 `INTERNET` 与 `ACCESS_NETWORK_STATE`**。
   已用 `tools:node="remove"` 移除，并对 debug / release 两种产物用 `aapt2 dump permissions` 验证通过。
   这一步不做，「零联网」就成了假承诺。
2. **推理必须放在后台线程**。原实现的截图回调跑在主线程且内部用 `runBlocking` 等 OCR 结果，
   会把主线程卡住上百毫秒。已改为独立单线程 executor。

**尚未完成的验证**：设备在安装阶段反复进入休眠（`mWakefulness=Dozing`），
79MB 的 debug 包与 15.6MB 的 release 包都卡在 `adb install` 无法完成，
因此 OCR 在真机上的实际识别效果、耗时、以及能否正确识别出「广告」角标，都还没有数据。
下一轮需要设备保持亮屏解锁状态再重试安装。

---

## 5. 对设计的修正建议

1. **OCR 从 P1 提升到 P0**：红果广告内容不在节点树里，没有 OCR 就无法覆盖主要场景。
2. **规则 DSL 需要「组合特征」能力**：已新增 `AdIndicators.allTextRegex`（AND 语义）。
3. **规则作者必须知道红果 id 不可依赖**：规则文档里要明确「视频类 App 优先用文本特征」。
4. **诊断日志是必需品，不是可选项**：广告页只存在几秒，`uiautomator dump` 在视频页必然失败
   （`could not get idle state`），没有引擎侧落盘就无法做任何校准。`SnapshotDumper` 应保留为常驻能力，
   并作为 PRD 里「快照拾取器」的实现基础。
5. **`rootInActiveWindow` 不能作为唯一取根方式**：应默认走 `windows` 退化路径。

---

## 6. 复现命令
```bash
# 抓取当前页面快照（debug 包）
adb shell run-as com.aios.flitr.debug \
  cat /data/data/com.aios.flitr.debug/files/debug/last_snapshot.txt

# 引擎诊断日志
adb logcat -s FlitrEngine

# 用 adb 启用无障碍（免手动点击）
adb shell settings put secure enabled_accessibility_services \
  com.aios.flitr.debug/com.aios.flitr.service.FlitrAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

---

## 7. 第二轮修复（用户反馈的三个问题）

### 7.1 悬浮窗平时不该出现

**问题**：启动后悬浮胶囊一直挂在屏幕上，包括在看正片、什么都没发生的时候。

**原因**：`WatchingNormalVideo` 状态无条件显示绿色「正片保护中」胶囊；服务连接时还会主动 `pill.show()`。

**改法**：
- 服务连接时只准备触摸哨兵，**不主动显示胶囊**；
- 胶囊只在引擎真的在处理广告时出现（`DebouncingAd` / `WaitingCountdown` / `ExecutingAction` / `NeedsManual`）；
- `WatchingNormalVideo` 与 `UserTouchCooldown` 一律隐藏。

真机截图确认：红果剧场页已无任何悬浮层。

### 7.2 系统 UI 操作被误判为用户操作

**问题**：下拉通知栏、点状态栏也会触发「已避让用户操作」。

**原因**：`FLAG_WATCH_OUTSIDE_TOUCH` 的哨兵只上报「有触摸」，不区分位置；而且避让时还会弹一个胶囊提示。

**改法**：
1. 哨兵改为上报 `rawX / rawY`；
2. 服务侧按区域过滤：`y < 状态栏高度` 或 `y > 屏高 - 导航栏高度` 的触摸直接忽略；
3. 只对**正在托管的应用**避让（前台包名必须在 `videoPackages` 里）；
4. 避让时不再弹胶囊 —— 用户自己动手时不需要被告知"我检测到你在摸屏幕"。

### 7.3 开屏跳过为什么不生效

**这是一个真实的优先级 bug，不是"没实现"**。

`EventRouter.route()` 里视频通道排在开屏通道**前面**，而红果、快手都在视频托管列表里 ——
所以这些 App 的开屏窗口**永远轮不到检测**，等于开屏功能对它们完全失效。

**改法**：把开屏通道提到最前。开屏窗口只有 6 秒，是时间敏感的；视频托管是常驻的，
让它先跑不影响视频判定（节流期内事件继续落到视频通道，两条通道共存）。

真机验证：红果冷启动日志出现

```text
ROUTE pkg=com.phoenix.read route=Splash ... elapsed=744ms
ROUTE pkg=com.phoenix.read route=Splash ... elapsed=1261ms
```

说明开屏检测在冷启动前 6 秒确实在跑了。**但这一轮没有遇到真实开屏广告**，
点击动作仍未经端到端验证。

同时把跳过按钮的正则放宽了。真机上跳过文案形态比预期杂得多：

```text
跳过 / 跳过广告 / 跳过 3 / 3s跳过 / 3 秒 跳过 / 5秒 | 跳过 / 跳过 | 5 / 跳过广告 5s / Skip / Skip 5
```

以上 10 种形态已全部纳入单元测试，同时保留「正文含'跳过'二字不命中」的反例。

### 7.4 顺带发现：R8 会把类名混淆进日志

release 包里 `route::class.simpleName` 被混淆成 `kc1` / `ic1` / `jc1`，日志完全不可读。
已改为显式字符串映射。这类问题只在 release 包上出现，debug 调试时看不到。

### 7.5 安装卡住的真正原因

之前 `adb install` 反复挂起 13～17 分钟，不是包太大，而是**设备在传输过程中进入休眠**
（`mWakefulness=Dozing`），USB 传输被挂起。

解决：

```bash
adb shell svc power stayon usb
```

之后 15.6MB 的 release 包安装只用了几秒。

> **2026-11-12 更正**：休眠只是诱因，真正的原因是设备上残留的安装会话。见 8.6。

---

## 8. 构建环境限制：沙箱只允许写工作区（2026-11-12 追加）

这一条不是 Flitr 的代码问题，但它让本地构建整个上午完全不可用，
而且报错信息直指 Gradle 和 JDK，极具误导性，所以单独记下来。

### 8.1 现象

本地执行 `./gradlew :app:assembleRelease`，在**启动阶段**就失败：

```text
Could not initialize native services.
> Failed to load native library 'native-platform.dll' for Windows 11 amd64.

Caused by: java.io.IOException: 系统找不到指定的路径。
    at java.io.WinNTFileSystem.createFileExclusively0(Native Method)
    at java.io.File.createNewFile(File.java:1045)
    at net.rubygrapefruit.platform.internal.NativeLibraryLocator.find(NativeLibraryLocator.java:46)
```

注意堆栈：失败在 `File.createNewFile`，**不是** `System.loadLibrary`。
DLL 根本没走到「加载」那一步，是解压阶段就写不出文件。

### 8.2 排除项

按最容易怀疑的方向逐个排除，结论是**都不是**：

| 怀疑对象 | 验证方法 | 结论 |
| --- | --- | --- |
| Gradle 发行版损坏 | 从腾讯镜像重下 9.9.1 全新解压 | 同样报错，排除 |
| JDK 损坏 | 从清华镜像重下 Temurin 21.0.13+9 | 同样报错，排除 |
| Gradle 缓存损坏 | `GRADLE_USER_HOME` 指向全新空目录 | 同样报错，排除 |
| 路径含 `+` 号 | JDK 目录改名去掉 `+` | 同样报错，排除 |

### 8.3 根因

写一个最小 Java 程序，在几个目录里试 `File.createNewFile`：

```text
%TEMP%                  exists=true   ERROR=拒绝访问
<repo>\.tooling\jtmp    mkdirs=true   createNewFile=true
%USERPROFILE%\AppData\Local   exists=true   ERROR=拒绝访问
%USERPROFILE%           exists=true   ERROR=拒绝访问
```

**只有工作区内能写。** 而 Gradle 的两个默认写入位置都在工作区外：

```text
~/.gradle   →  GRADLE_USER_HOME
%TEMP%      →  java.io.tmpdir
```

Gradle 启动时要先把 `native-platform.dll` 解压到临时目录，写不进去就报「找不到路径」。

**为什么一开始没查出来**：PowerShell 自己写 `%TEMP%` 完全正常，
只有它拉起的子进程（`java.exe`）被拦。用 PowerShell 测文件系统看不出任何异常，
这一点值得记住——**验证权限要用目标进程去验，不要用当前 shell 代劳**。

### 8.4 修法

把两个位置都指到仓库内，已封装成 `build.ps1`：

```powershell
$env:GRADLE_USER_HOME = "$PWD\.tooling\gradle-home"
$env:GRADLE_OPTS      = "-Djava.io.tmpdir=$PWD\.tooling\jtmp"
```

```powershell
./build.ps1                              # 构建 release APK
./build.ps1 :app:testDebugUnitTest       # 只跑单元测试
./build.ps1 :app:assembleRelease -Clean  # 先 clean
```

改完立刻 `BUILD SUCCESSFUL`。

为避免重新下载 2.7 GB 依赖，把 `~/.gradle/caches` 复制进 `.tooling/gradle-home/caches`
（robocopy 20 秒）。`.tooling/` 已在 `.gitignore` 里。

**副作用**：构建时会打一行

```text
Warning: Unable to initialize metrics, ensure %USERPROFILE%\.android is writable
```

同一个原因，不影响产物。

### 8.5 附带结论：原来的 Gradle 目录从来没坏过

排查过程中重下过 JDK 和 Gradle，事后验证 `.tooling/gradle-9.9.1` 原样可用。
唯一真损坏的是 JDK——`bin/` 整个目录消失只剩 `lib/`，那是真丢了，必须重装。

### 8.6 同时纠正 7.5：安装卡死的真因

7.5 把 `adb install` 挂起归因于设备休眠。2026-11-12 的复现推翻了这个结论。

**真正原因是设备上堆了 4 个卡死的安装会话**——每次手动 kill 掉挂起的 `adb install`，
都会在系统里留下一个孤儿 session，新的安装全部排在它们后面：

```text
Active Session 1029384588
Active Session 343070177
Active Session 1705092513
Session 979932414
```

清掉之后：

```bash
adb shell pm install-abandon <sessionId>
```

```text
Performing Streamed Install
Success
安装耗时 21.1 秒
```

同一个包，之前挂了 40 分钟；清完会话后 21 秒装完。

`svc power stayon usb` 仍然值得保留（避免传输被挂起），但它不是主因。

**下次遇到 `adb install` 长时间无输出，先查会话列表，别急着重启设备：**

```bash
adb shell dumpsys package | grep -i "Active Session"
```
