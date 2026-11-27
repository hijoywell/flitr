# AGENTS.md — Flitr

面向在本仓库工作的 AI agent。**开工前先读完这一页。**

---

## 一、推送：本机推不了，必须走服务器

本机 git 凭据是 `daysneco`，对 `hijoywell/flitr` **只有读权限**，直推必然 403：

```
remote: Permission to hijoywell/flitr.git denied to daysneco.
```

**不要尝试本机 `git push`，不要配置本机 remote。** 写权限在构建服务器上。

完整流程见 [docs/PUSH-AND-RELEASE.md](docs/PUSH-AND-RELEASE.md)。最短版本：

```powershell
# 1. 本机打包
git bundle create "$env:TEMP\flitr.bundle" --all

# 2. 传上去
scp -P 2222 -o StrictHostKeyChecking=no "$env:TEMP\flitr.bundle" user@instabox-tjing:/data/home/flitr.bundle

# 3. 服务器侧对齐并推送
ssh -p 2222 -o StrictHostKeyChecking=no user@instabox-tjing '
  cd ~/flitr &&
  git fetch ~/flitr.bundle "refs/heads/main:refs/remotes/bundle/main" --force &&
  git checkout -B main refs/remotes/bundle/main &&
  git push origin main && git push origin --tags
'
```

推送 `v*` tag 后，CI（`.github/workflows/android.yml`）会自动跑单测、构建 release APK、
校验签名并创建 Release。查状态：`ssh -p 2222 … "bash ~/flitr_check.sh"`。

> SSH 连不上先重试一次 —— 2222 端口偶发 `Connection refused`，是抖动不是配置问题。

---

## 二、不可违背的工程约束

1. **宁可漏关，不可误关。** 误划正片、误点广告 CTA（进下单页/下载页）比漏划一条广告严重得多。
   任何放宽规则的改动，都要同时检查正片保护是否还压得住。
2. **零联网。** 不得引入需要网络的能力，也不得让依赖往清单里注入 `INTERNET`。
   改动依赖后用 `aapt2 dump permissions` 复验。
3. **低功耗。** 待机 CPU 目标 ~0%、日均耗电 < 0.8%。加采样/轮询前先算一算代价。
4. **不写公共存储。** 截图、XML、日志一律不进 `/sdcard`；诊断落盘放应用私有目录。

---

## 三、release 包特有的坑（debug 包复现不出来）

| 坑 | 后果 | 位置 |
|---|---|---|
| R8 剥掉反射加载的类 | ML Kit 完全失效，且**静默返回空结果** | `app/proguard-rules.pro` |
| ML Kit 模型文件被压缩 | native mmap 读到乱码 | `app/build.gradle.kts` 的 `androidResources.noCompress` |
| 屏幕宽高可能颠倒 | 滑动坐标落到屏幕外，页面不翻 | `FlitrAccessibilityService.measureScreen()` |

**改完感知/识别相关代码，必须验证 release 包**（`assembleRelease` + 装机），不能只看 debug。

---

## 四、发版清单

1. `app/build.gradle.kts`：`appVersionName` / `appVersionCode`（code 单调 +1）
2. `CHANGELOG.md`：顶部加新条目，并改开头那句「当前为 x.y.z」
3. 单测：`gradle :app:testDebugUnitTest --tests "com.aios.flitr.engine.*"`
4. `gradle :app:assembleRelease` → `adb install -r` → 真机验证
5. `git commit` → `git tag -a vX.Y.Z`
6. **按第一节的流程推送**（不是本机直推）
7. 确认 Release 页面出现 APK

---

## 五、排查真机问题的习惯

广告页只存在几秒，`uiautomator dump` 在视频页必然失败（`could not get idle state`）。
**必须靠引擎自己打日志**，相关开关已经内置在代码里：

| 日志 | 用途 |
|---|---|
| `TREE <n>n :: id@x,y*` | 无文本页面的控件结构，判断能否改用 id 识别 |
| `OCR trigger/shot ok/frame/raw/done` | OCR 全链路，区分黑帧、推理失败、识别为空 |
| `DIAG state=… isAd=… conf=…` | 状态机决策瞬间 |
| `ACT swipe/click …` | 实际下发的坐标 |

抓现场：`adb logcat -d -s FlitrEngine:*`。日志说「没识别」，先看 `TREE` 和 `OCR recognize blocks`。
