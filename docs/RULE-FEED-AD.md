# 信息流直播间广告 · 规则特征设计

> 状态：**设计稿，未落地**
> 样本：红果短剧 1.0.2 真机抓取，2026-11-15
> 相关：`docs/ARCHITECTURE.md` 3.3 / 3.5、`docs/DEVICE-VALIDATION.md`

---

## 0. 摘要

红果短剧的信息流广告有**两个变体**，行为完全不同：

| | 变体 A（贴片广告） | 变体 B（直播间带货） |
|---|---|---|
| 页面文本 | 有，「返回」+「上滑继续观看短剧」 | 未知（见 §4） |
| 无障碍事件 | 有（出现时即被采样） | **零**（20s 观测） |
| 现状 | ✅ **已能自动划走**（01:10:48 实测） | ❌ 完全不介入 |

所以问题**不是规则特征缺失** —— 变体 A 证明现有规则链路是通的。
真正卡住的是：**变体 B 这个页面不产生任何无障碍事件，事件驱动的视频通道连采样机会都没有。**

规则特征的设计因此分两步：

1. **先补触发**（§3）—— 没有这一步，任何规则都不会被执行；
2. **再校准特征**（§5）—— 这一步依赖变体 B 的实测 PAGE 行，目前还缺样本（§6）。

---

## 1. 两个变体的实测证据

### 变体 A：现有规则命中，自动划走成功

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
无倒计时 → `performSwipe()` → 划走。**这条链路是好的，不要动它。**

### 变体 B：页面停留 20 秒，零事件

广告停在屏幕上、不做任何操作，清空 logcat 后观察 20 秒，引擎只输出了插屏定时器的日志：

```text
01:02:07  I FlitrEngine: POPUP scan skip: not in popupPackages: com.phoenix.read
01:02:17  I FlitrEngine: POPUP scan skip: not in popupPackages: com.phoenix.read
```

`EVENT rate=` 一条都没有，`ROUTE` 一条都没有 —— 这两个都是 INFO 级、有事件必然打印。

对照同一台设备上的正片页：**4.0 事件/秒**，`ROUTE Video` 每 ~500ms 一次，`PAGE` 行正常产出。

结论：**变体 B 的页面不产生无障碍事件。** 而视频通道是纯事件驱动的
（`onAccessibilityEvent` → `route()` → `handleVideo()`），没事件就永远不采样。

`POPUP scan skip` 那条日志恰好是反证：全 App 唯一的定时器是插屏通道的（2.5s），
它每 10 秒醒一次、看见红果短剧、因为不在 `popupPackages` 里而退出。
**唯一在转的定时器，恰好被包名门控挡在了外面。**

变体 B 长这样（左）与紧随其后的正片（右），黄线是 `SilentRoiOcrParser` 的两条裁剪边界：

![变体 B 与正片对比](samples/feed-live-ad/01-ad-vs-normal.jpg)

注意「点击进入直播间」在 72% 线**上方**，落在 OCR 盲区里 —— 这条文本永远读不到，
不能作为规则特征（详见 §5.2）。

当时用 `uiautomator dump` 抓的原始层级留在
[`samples/feed-live-ad/01-tree.xml`](samples/feed-live-ad/01-tree.xml)，
但按 §4 的说明，**这份文件不能用来判断引擎看得见什么**，仅作页面结构参考。

---

## 2. 为什么 `POPUP scan` 挡在红果上

`FlitrAccessibilityService.scanPopupOnce()` 的判定顺序（第 298–302 行）：

```kotlin
if (pkg in prefs.whitelist)      return skip("whitelisted: $pkg")
if (pkg !in prefs.popupPackages) return skip("not in popupPackages: $pkg")   // ← 红果在这里退出
if (prefs.videoEnabled && pkg in prefs.videoPackages) return skip("video app: $pkg")
```

`popupPackages` 默认只有脉脉和携程（按包名 opt-in，防止误点），红果不在里面，
所以在第 300 行就返回了，**根本走不到第 302 行那句"视频应用交给状态机"**。
而它想交给的那个状态机，没有定时器。

这就是"两边都不管"的完整成因。

---

## 3. 设计一：零事件页面的兜底采样

### 3.1 约束

- 不能对全部应用开定时器 —— PRD 要求待机 CPU ≈ 0%；
- 不能高频 —— 红果是常驻场景，1 秒一次的节点遍历 + OCR 会明显耗电；
- 不能破坏"用户触屏即避让"的既有语义。

### 3.2 方案：分档心跳

只对 `videoPackages` 生效，且只在"本该有事件却没有事件"时启动：

| 条件 | 心跳间隔 | 理由 |
|---|---|---|
| 距上一次无障碍事件 < 1.5s | 不启动 | 有事件时事件本身就是心跳，重复采样是浪费 |
| 1.5s ~ 30s 无事件 | 1200ms | 覆盖"进入广告页后静默"这一段，也是用户能容忍的识别延迟 |
| > 30s 无事件 | 3000ms | 大概率用户就是在安静看正片，降频省电 |
| 屏幕熄灭 / 锁屏 / 已暂停 / 用户 3s 内触过屏 | 不采样 | 与现有语义一致 |

**关键设计**：心跳不是"每 1.2 秒重新判定一次"，而是"补一次本该由事件触发的采样"。
所以它走的是**和事件完全相同的入口** `handleVideo(pkg, null, now)`，
包括 OCR 兜底、状态机、胶囊更新 —— 不新增第二条判定路径。

### 3.3 为什么不是"加个 1.2s 的全局轮询"

那样会在正片页也持续 OCR（正片页 `textCount` 足够时不触发 OCR，但节点遍历仍在跑）。
分档心跳的核心是**用"无事件时长"当信号**：有事件说明页面在动，引擎看得见；
没事件才需要怀疑"内容画在 TextureView 里"。这样正片页的心跳会自然降到 3s，
而且大部分时间根本不启动。

### 3.4 代价

红果短剧连续观看时，若页面真的长期无事件，会退到 3s 一次节点遍历。
按 72 节点、单次 < 2ms 估算，占用可以忽略。OCR 只在 `textCount < 4` 时触发，
且已有 `MIN_INTERVAL_MS=1100` + `OCR_REPEAT_MS=3000` 双重节流。

---

## 4. 一条必须更正的前提

我最初用 `uiautomator dump` 判断"广告页节点树 0 文本"，**这个方法是错的**，不能用。

| 手段 | 结果 |
|---|---|
| `uiautomator dump` | 广告页 47 节点 / 0 text；正片页 45 节点 / 0 text；重跑时直接 `ERROR: could not get idle state` |
| 引擎自报的 `PAGE` 行 | 正片页 **72 节点**且文本齐全；变体 A 广告 **24 节点**，2 条文本 |

两者差得很远。原因：`uiautomator` 走的是压缩后的层级（会滤掉
`importantForAccessibility=false` 的节点），而引擎用 `AccessibilityNodeInfo` 原始遍历；
加上视频页窗口永不空闲，dump 经常直接失败。

> **结论**：判断"引擎看得见什么"，只能以引擎自己打的 `PAGE` 行为准。
> 变体 B 的节点树到底有没有文本，**目前没有可信样本** —— 因为没事件就没采样，
> 没采样就没 `PAGE` 行。这正是 §3 要解决的，也是 §5 的前置条件。

---

## 5. 设计二：规则特征

### 5.1 已知约束

1. **变体 A 已经工作，新特征不能破坏它。** 它的判据是 `allTextRegex` 全中，置信度 0.55。
2. `anyTextRegex` 是 **OR** 语义 —— 任一条单独成立。像「直播中」这种词在短剧画面里
   并非不可能出现（剧情里的直播桥段），单独成立就有划走正片的风险。
3. `allTextRegex` 是 **AND** 语义，表达不了「三条里中两条就行」。
4. **同一句提示语在不同变体里措辞不同**：变体 A 是「上滑继续**观看**短剧」，
   变体 B 截图上是「上滑继续**看**短剧」。正则必须容忍这一字之差。

### 5.2 三档特征

| 档位 | 特征 | 语义 | 单独成立 |
|---|---|---|---|
| **强** | `上滑继续(?:观看\|看)短剧` | App 自己贴的跳转提示，属于应用 chrome 而非画面内容 | ✅ 是 |
| **弱** | `^直播中$`、`^@\S+$`、`已售\s*\d+\s*件` | 直播间通用元素，画面内容也可能有 | ❌ 需 ≥2 条 |
| **反向** | `第\s*\d+\s*集` + `选集`（沿用 `normalDramaSafeguards`） | 正片特征 | 命中即压掉广告判定 |

强特征单条成立的理由：它是红果自己的 UI 文案，剧情画面里恰好出现这七个字的概率按零处理。
变体 A 已经用 `^上滑继续观看短剧$` 证明了这个思路可靠，这里只是放宽一个字的容错。

弱特征取 2 条的理由：「直播中」是画面里最可能撞车的词；再加「@账号」或「已售N件」，
要求"剧情里刚好同时出现直播角标 + 商家账号 + 销量"才误判。

> ⚠️ 弱特征里的 `^@\S+$` 与 `已售\s*\d+\s*件` 都落在 OCR 底部 ROI 内（y≈2334 / 2539），
> 可达。但**「点击进入直播间」（y≈1852）落在 OCR 盲区**（底条从 y=1996 开始），
> 不写进规则 —— 写进去只会制造"看起来覆盖了、实际永不触发"的假象。
> 要覆盖它得把 ROI 改成三段，那是另一个改动。

### 5.3 DSL 扩展

在 `VideoScenarioRule` 下新增可选块，默认不配置 = 老行为完全不变：

```json
"feedItemAd": {
  "strongRegex": [
    "上滑继续(?:观看|看)短剧"
  ],
  "weakRegex": [
    "^直播中$",
    "^@\\S+$",
    "已售\\s*\\d+\\s*件"
  ],
  "minWeakHits": 2
}
```

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `strongRegex` | `List<String>` | `[]` | 任一命中即判定为信息流广告 |
| `weakRegex` | `List<String>` | `[]` | 命中数达到 `minWeakHits` 才判定 |
| `minWeakHits` | `Int` | `2` | 弱特征阈值；设为 `1` 等价于并入 `anyTextRegex`，不建议 |

三个字段全空时整块等于不存在。

### 5.4 判定顺序

```text
1. 正片保护优先：allTextRegex 全中 → isDramaProtected = true → 不动
      （除非同时命中强特征 —— 强特征是 App chrome，说明当前页是广告条目）
2. 强特征任一命中            → isAd = true，形态 = FeedItem，置信度 0.85
3. 弱特征命中数 ≥ minWeakHits → isAd = true，形态 = FeedItem，置信度 0.60
4. 其余                       → isAd = false
```

强特征计入 `strongAdSignal`（与 `anyViewIdSuffix` 同级），弱特征只计弱信号。
这样正片页的 `第N集 + 选集` 保护依然有效。

### 5.5 动作：上滑，不需要改代码

信息流广告没有倒计时、没有关闭控件，`VideoAdStateMachine.confirmAd()` 现有分支
会自动落到 `performSwipe()`。

已实测：手动上滑一次，从广告页翻到《苍生斩妖录》第 85 集，动作选型正确。

---

## 6. 变体 B 的取证机制（设计的必要组成）

§5 的弱特征是基于截图肉眼读出的文本，**没有经过 OCR 实测确认**。
在拿到变体 B 的真实 `PAGE` 行之前，规则只能算"待校准"。

建议随 §3 的心跳一起加一个取证开关（默认关，仅在设置页手动开启 10 分钟）：

1. 心跳采样到 `textCount < 4` 且发生 OCR 时，把 `PAGE` 行与 OCR 文本块写入
   `files/debug/last_blind_snapshot.txt`（`SnapshotDumper` 已有落盘通道，扩一个字段即可）；
2. 设置页「导出诊断快照」把该文件通过系统文件选择器导出 —— 不引入网络权限；
3. 拿到文件后再回来定稿 §5.2 的正则。

这样"下一次遇到就一定能抓到"，不用靠人守在那儿。

---

## 7. 需要复核的代码隐患

以下 4 条是读代码发现的，**但当前证据不足以断定它们是变体 B 的成因**，
所以单列，不与主因混在一起。

| # | 位置 | 问题 | 影响 |
|---|---|---|---|
| H1 | `FlitrAccessibilityService.recheck()` L500 | 用 `rootInActiveWindow ?: return`，而 `acquireRoot()` 的注释明确写着红果上它恒为 null | 稳定帧复核可能恒空转。但正片页有 4 事件/s，复核常被普通事件顶替，所以未必现 |
| H2 | 同上 | `recheck()` 直接 `buildSnapshot()` + `coordinator.handleVideo()`，绕过服务层 `handleVideo()` 里的 OCR 兜底 | 即使 H1 修好，复核快照也不带 OCR 结果 |
| H3 | `ScreenSnapshot.fingerprint()` | 只取节点文本；无文本时 `"".hashCode() == 0`，广告页与正片页指纹都是 `"0"` | `verifyPendingSwipe()` 会误判"没翻页"→ 补偿重划 → 可能误杀正片。仅在页面真无文本时成立 |
| H4 | `AdClassifier.assess()` | 「看不见」和「不是广告」都返回 `isAd=false` | 状态机把 `DebouncingAd` 重置掉，扛不过 OCR 的 1.1s 延迟 |

H3 的数学：`fingerprint()` = `nodes.map{normalized}.filter{isNotEmpty}.sorted().joinToString("|").hashCode()`，
空列表时是 `"".hashCode()`，JVM 规范恒为 `0`。**只要变体 B 真的无文本，这一条就必然触发。**

> 上一版设计稿里我写的「`Log.d` 被 ColorOS 过滤」是**错的**，已删除：
> 变体 A 的 `D FlitrEngine: PAGE ...` 明确出现在 logcat 里。

---

## 8. 风险与回归

| 风险 | 触发条件 | 缓解 |
|---|---|---|
| 划走正片 | 正片画面里同时出现「直播中」+「@账号」/「已售N件」 | 弱特征阈值 2；正片保护优先级最高 |
| 破坏变体 A | 新特征改动 `allTextRegex` 语义 | 新块独立，不动 `allTextRegex` |
| 连划 | H3 未修时指纹恒等导致补偿重试 | 先修 H3 再上规则 |
| 心跳耗电 | 长视频页长期无事件 | 30s 后降频到 3s；锁屏/暂停/触屏即停 |
| 误判变体 B 的措辞 | 正则写死「观看」 | 用 `(?:观看\|看)` 容错 |

---

## 9. 验证方案

### 9.1 离线单测（`engine` 模块，无 Android 依赖）

| 用例 | 输入 | 期望 |
|---|---|---|
| 强特征-变体B措辞 | 仅 `上滑继续看短剧` | `isAd=true`, `FeedItem` |
| 强特征-变体A措辞 | 仅 `上滑继续观看短剧` | `isAd=true` |
| 弱特征不足 | 仅 `直播中` | `isAd=false` |
| 弱特征达标 | `直播中` + `已售794件` | `isAd=true` |
| 正片保护 | `第85集` + `选集` + `直播中` | `isAd=false` |
| 变体A不回归 | `返回` + `上滑继续观看短剧` | 仍 `isAd=true`, conf≈0.55 |
| 空白页 | 0 节点 | `isBlind=true`，状态不重置 |
| 指纹区分 | 48 节点 vs 45 节点 | 指纹不同 |
| 指纹稳定 | 同页两次采样 | 指纹相同 |

### 9.2 真机验收

1. 打开红果短剧，进入短剧 tab，连续上滑直到刷出直播间广告；
2. `adb logcat -s FlitrEngine` 应依次看到
   `PAGE ... [BLIND]` → `OCR n blocks: …` → `AD conf=… hit='…'` → `ACT swipe`；
3. 划走后必须停在**正片**上，且日志里**没有**第二次 `ACT swipe`（H3 生效的判据）；
4. 连续刷 20 条，确认正片零误划、变体 A 仍正常。

---

## 10. 落地清单

| # | 文件 | 改动 | 类型 | 依赖 |
|---|---|---|---|---|
| S1 | `service/FlitrAccessibilityService.kt` | 新增分档心跳（§3） | 触发 | — |
| S2 | `service/FlitrAccessibilityService.kt` | 心跳走 `handleVideo()` 统一入口 | 触发 | S1 |
| H3 | `engine/model/ScreenSnapshot.kt` | `fingerprint()` 空文本时退化为结构摘要 | 缺陷 | — |
| H4 | `engine/video/AdClassifier.kt` + `VideoAdStateMachine.kt` | 新增 `isBlind`，观察期不被重置 | 缺陷 | — |
| H1/H2 | `service/FlitrAccessibilityService.kt` | `recheck()` 改用 `acquireRoot()` 并转调 `handleVideo()` | 缺陷 | — |
| C1 | `engine/SnapshotDumper.kt` | 盲页快照落盘 + 导出（§6） | 取证 | S1 |
| R1 | `engine/rules/RuleModels.kt` | 新增 `FeedItemAdRules` 块 | DSL | — |
| R2 | `engine/video/AdClassifier.kt` | 接入强/弱特征判定 | DSL | R1 |
| R3 | `assets/default_rules.json` | 红果场景加 `feedItemAd` 块 | 规则 | R2 |
| T1 | `engine` 单测 | §9.1 的 9 条用例 | 测试 | R2 |

**建议顺序**：S1+S2+H3+H4 → C1 → 抓变体 B 真实样本 → 校准 R3 → T1。
R3 在拿到真实样本前只写 `strongRegex`，`weakRegex` 留空。
