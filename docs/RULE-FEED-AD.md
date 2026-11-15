# 信息流直播间广告 · 规则特征设计与落地

> 状态：**已落地（1.0.3）**，真机验证待补
> 样本：红果短剧真机抓取，2026-11-15
> 相关：`docs/ARCHITECTURE.md` 3.3 / 3.5、`docs/DEVICE-VALIDATION.md`、`CHANGELOG.md` 1.0.3

---

## 0. 摘要

红果短剧的信息流广告有两个变体：

| | 变体 A（贴片广告） | 变体 B（直播间带货） |
|---|---|---|
| 页面文本 | 有，「返回」+「上滑继续观看短剧」 | 屏幕上有「上滑继续看短剧」 |
| 无障碍事件 | 有（出现时即被采样） | **零**（20s 观测） |
| 修复前 | ✅ 已能自动划走 | ❌ 完全不介入 |

变体 A 能工作、变体 B 不能，说明**规则链路本身是通的**。变体 B 卡在两处叠加：

1. **页面零事件** —— 视频通道纯事件驱动，不采样就永远不会判定；
2. **提示语差一个字** —— 规则要求「上滑继续**观看**短剧」，屏幕上写的是「上滑继续**看**短剧」。

```text
广告出现瞬间 → 有事件 → 采样到 → 规则要求「观看」→ 屏幕是「看」→ 不匹配 → 什么都不做
之后页面静止 → 零事件 → 再也不看 → 永远卡住
```

缺任一条修复都不动，所以两条一起改。

---

## 1. 变体 A：现有规则命中，自动划走成功

```text
01:10:46.828  D FlitrEngine: PAGE -1755233 | [NORMAL] 72n 返回 / 清屏 / 选集 · 已完结 · 全109集 /
                              · 已完结 · 全109集 / 选集 / 分享 / 3200 / 115 /
                              作者声明：内容由AI生成 / 苍生斩妖录第一季 / 全屏观看
01:10:48.192  D FlitrEngine: PAGE 62160050 | [AD] 24n 返回 / 上滑继续观看短剧
01:10:48.192  I FlitrEngine: AD conf=0.55 countdown=null completed=false drama=false
                              panel=false hit='返回' close='-' keep='-'
01:10:48.193  I FlitrEngine: ACT swipe (978,2259)->(1015,726) dur=185ms boost=1.00
                              reason=无倒计时广告, 拟人上滑
```

命中的是 `adIndicators.allTextRegex = ["^返回$", "^上滑继续观看短剧$"]`，置信度 0.55，
无倒计时 → `performSwipe()` → 划走。**这条链路没动过。**

---

## 2. 变体 B：页面停留 20 秒，零事件

广告停在屏幕上、不做任何操作，清空 logcat 后观察 20 秒，引擎只输出了插屏定时器的日志：

```text
01:02:07  I FlitrEngine: POPUP scan skip: not in popupPackages: com.phoenix.read
01:02:17  I FlitrEngine: POPUP scan skip: not in popupPackages: com.phoenix.read
```

`EVENT rate=` 一条都没有，`ROUTE` 一条都没有 —— 这两个都是 INFO 级、有事件必然打印。
对照同一台设备上的正片页：**4.0 事件/秒**，`ROUTE Video` 每 ~500ms 一次。

而视频通道的入口是 `onAccessibilityEvent` → `route()` → `handleVideo()`。
事件不响，`AdClassifier` 一次都不会跑。

`POPUP scan skip` 那条日志恰好是反证：全 App 唯一的定时器是插屏通道的（2.5s），
它每 10 秒醒一次、看见红果短剧、因为不在 `popupPackages` 里退出。
**唯一在转的定时器，恰好被包名门控挡在了外面。**

### 门控错位的位置

`FlitrAccessibilityService.scanPopupOnce()`：

```kotlin
if (pkg in prefs.whitelist)      return skip("whitelisted: $pkg")
if (pkg !in prefs.popupPackages) return skip("not in popupPackages: $pkg")   // ← 红果在这里退出
if (prefs.videoEnabled && pkg in prefs.videoPackages) return skip("video app: $pkg")
```

第 302 行那句「视频应用交给状态机」永远走不到。而它想交给的那个状态机，没有定时器。

变体 B 长这样（左）与紧随其后的正片（右），黄线是 `SilentRoiOcrParser` 的两条裁剪边界：

![变体 B 与正片对比](samples/feed-live-ad/01-ad-vs-normal.jpg)

当时用 `uiautomator dump` 抓的原始层级留在
[`samples/feed-live-ad/01-tree.xml`](samples/feed-live-ad/01-tree.xml)，
但**这份文件不能用来判断引擎看得见什么**（见 §3），仅作页面结构参考。

---

## 3. 一条踩过的坑：不能用 `uiautomator dump` 判断引擎可见性

| 手段 | 结果 |
|---|---|
| `uiautomator dump` | 广告页 47 节点 / 0 text；正片页 45 节点 / 0 text；重跑时直接 `ERROR: could not get idle state` |
| 引擎自报的 `PAGE` 行 | 正片页 **72 节点**且文本齐全；变体 A 广告 **24 节点**，2 条文本 |

两者差得很远。原因：`uiautomator` 走压缩层级（会滤掉 `importantForAccessibility=false` 的节点），
而引擎用 `AccessibilityNodeInfo` 原始遍历；加上视频页窗口永不空闲，dump 经常直接失败。

> **结论**：判断"引擎看得见什么"，只能以引擎自己打的 `PAGE` 行为准。

顺带更正另一条：`Log.d` **没有**被 ColorOS 过滤 —— `D FlitrEngine: PAGE ...` 在 logcat 里正常可见。

---

## 4. 落地内容

### 4.1 提示语两种措辞都兼容

`assets/default_rules.json`，红果场景的 `adIndicators.anyTextRegex` 增加一条：

```json
"上滑继续(?:观看|看)短剧"
```

等价于同时写「上滑继续看短剧」和「上滑继续观看短剧」两条，一个正则吃掉。
不锚定首尾，容忍 OCR 或节点文本里的前后缀噪声。

之所以放进 `anyTextRegex`（OR 语义、单条即成立）而不是加弱特征阈值：
这行字是**红果自己贴的跳转提示**，不是画面内容，正片页不会出现。
剧情画面里恰好出现这七个字的概率按零处理，不需要第二条佐证。

正片保护照旧优先：`normalDramaSafeguards.allTextRegex = ["第\d+集", "选集"]` 全中时压掉判定。
万一某个正片页也渲染出这行提示，我们选择不动 —— 符合「宁可漏关，不可误关」。

### 4.2 视频通道心跳

`FlitrAccessibilityService`，新增 `videoHeartbeatTask`：

| 条件 | 行为 |
|---|---|
| 距上次无障碍事件 < 1.5s | 不采样（有事件时事件本身就是心跳） |
| 1.5s ~ 30s 无事件 | 每 1.2s 补一次采样 |
| > 30s 无事件 | 降到 3s（用户大概率在安静看正片） |
| 息屏 / 锁屏 / 已暂停 / 用户 3s 内触过屏 | 直接跳过 |
| 前台不在 `videoPackages` 或在白名单 | 直接跳过 |

关键点：心跳走的**和事件完全相同的入口** `handleVideo(pkg, null, now)`，
包括 OCR 兜底与状态机，不新增第二条判定路径。

正片页实测 4 事件/秒，心跳基本不启动，所以日常观看没有额外开销。

### 4.3 无文本页面的指纹退化为结构指纹

`ScreenSnapshot.fingerprint()` 原来只取节点文本：

```kotlin
val tokens = nodes.map { it.normalized }.filter { it.isNotEmpty() }.sorted().joinToString("|")
return tokens.hashCode().toString()
```

无文本时 `"".hashCode() == 0`，**广告页和正片页的指纹会坍缩成同一个 `"0"`**。
后果在 `VideoAdStateMachine.verifyPendingSwipe()`：

```text
划走广告 → 700ms 后复核 → 指纹还是 "0" → 判定"滑动未生效"
→ 带 boost 补偿再划一次 → 这次划掉的是正片
→ 再复核还是 "0" → NeedsManual 停止介入
```

净效果：跳过 1 条广告、误杀 1 集正片、引擎罢工。**比不动更糟。**
这也是为什么这条必须和 4.1 / 4.2 一起上 —— 单独上规则，第一次运行就会踩到。

修法：文本为空时用「节点数量 + 每个节点的 viewId 与位置」生成结构指纹。
位置必须参与，因为红果的广告页与正片页**共用同一批 viewId**
（`ttlive_player_render_view` 等 22 个完全相同），只用 id 区分不开。

有文本时行为完全不变，不影响既有场景。

---

## 5. 未做 / 待办

| # | 事项 | 说明 |
|---|---|---|
| V1 | 真机验证 | 本次改动落地时设备 USB 掉线，只做了构建 + 离线单测 + 签名校验 |
| H1 | `recheck()` 用 `rootInActiveWindow ?: return` | 与 `acquireRoot()` 的注释矛盾（红果上它恒为 null）。正片页有 4 事件/秒，复核常被普通事件顶替，所以未必现；心跳上线后更不容易暴露 |
| H2 | `recheck()` 绕过 OCR 兜底 | 直接 `buildSnapshot()` + `coordinator.handleVideo()`，跳过了服务层 `handleVideo()` 里的 OCR 分支 |
| H3 | `AdClassifier` 把"看不见"当成"不是广告" | 建议加 `isBlind`，让观察期扛过 OCR 的 1.1s 延迟。心跳上线后暂不构成阻塞 |
| C1 | 盲页取证开关 | 把盲页的 `PAGE` 行与 OCR 文本块落盘导出，方便下次遇到新广告形态时定稿规则 |

H1–H3 是读代码发现的，**证据不足以断定它们是变体 B 的成因**，所以没有混进本次修复。

---

## 6. 验证记录

### 6.1 离线单测（已通过）

新增 `FeedAdMarkerTest` 8 条：

| 用例 | 期望 | 结果 |
|---|---|---|
| `上滑继续看短剧` | 判定为广告 | ✅ |
| `上滑继续观看短剧` | 判定为广告 | ✅ |
| 正片页（第85集 + 选集） | 不判定为广告 | ✅ |
| 正片特征齐全时压掉提示语 | 不判定为广告 | ✅ |
| 无文本时广告页与正片页指纹不同 | 不同 | ✅ |
| 无文本页面指纹不等于 `"0"` | 不等于 | ✅ |
| 同一页面重复采样指纹稳定 | 相同 | ✅ |
| 有文本时仍按文本计算指纹 | 行为不变 | ✅ |

全套 **42 条**用例通过。

### 6.2 构建与签名（已通过）

```text
Flitr-1.0.3-android-arm64-v8a.apk   15.66 MB
V2 Signer SHA-256: d121e3afccd6ed0da2e40398fe6e2557979077a91b5afc4187e3486b165f3c0c
```

与 1.0.2 同一把 release 密钥，可直接覆盖升级。

### 6.3 真机验收（待补）

1. 打开红果短剧，进入短剧 tab，连续上滑直到刷出直播间广告；
2. `adb logcat -s FlitrEngine` 应依次看到
   `PAGE ... hit='上滑继续看短剧'` → `AD conf=…` → `ACT swipe`；
3. 划走后必须停在**正片**上，且日志里**没有**第二次 `ACT swipe`（结构指纹生效的判据）；
4. 连续刷 20 条，确认正片零误划、变体 A 仍正常。
