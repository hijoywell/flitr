# Flitr

[![Build APK](https://github.com/hijoywell/flitr/actions/workflows/android.yml/badge.svg)](https://github.com/hijoywell/flitr/actions/workflows/android.yml)

纯本地运行的 Android 广告自动化工具：自动跳过开屏广告，智能托管短剧 / 短视频流广告。

> Flitr = *Filter*（过滤）+ *Flit*（轻快掠过）

## 功能

- **开屏广告秒跳** —— 三级匹配：广告 SDK 控件 id → 「跳过」语义 → 控件面积防欺诈，切入应用后 6 秒窗口内生效
- **短剧广告智划** —— 无倒计时的直接划走；有倒计时的实时读秒，播完自动关闭或上滑
- **正片保护** —— 识别到剧集 / 选集特征时绝不介入，误划正片的代价远高于漏划一条广告
- **用户优先** —— 检测到手指触屏立刻让路，进入 3 秒冷却
- **端侧 OCR 兜底** —— 内容画在 SurfaceView 里的自绘广告，用离线中文 OCR 识别
- **规则可导入** —— JSON 规则，内置红果短剧等场景，支持导入导出

## 特点

- **零联网** —— 不声明 `INTERNET` 权限，识别、判定、日志全部在本地完成
- **免悬浮窗权限** —— 状态胶囊基于无障碍 Overlay
- **Material 3 界面** —— 3 种明暗模式 × 6 套强调色

## 下载

到 [Releases](https://github.com/hijoywell/flitr/releases) 取最新的
`Flitr-<版本>-android-arm64-v8a.apk`，直接安装（仅 arm64 设备）。

## 环境

Android 11+（`minSdk 30`），`targetSdk 37`（Android 17），仅 `arm64-v8a`

## 构建

```bash
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/Flitr-<版本>-android-arm64-v8a.apk`

需要 JDK 21 与 Android SDK Platform 37。

## 签名

正式分发用项目专用 release 密钥。本地构建时在仓库根目录放 `keystore.properties`
（已在 `.gitignore` 中，不会提交）：

```properties
storeFile=/absolute/path/to/flitr-release.jks
storePassword=...
keyAlias=flitr
keyPassword=...
```

> 路径请用正斜杠。`.properties` 里 `\` 是转义字符，Windows 的反斜杠路径会被吃掉。

CI 从 GitHub Secrets 读取 `FLITR_KEYSTORE_BASE64` / `FLITR_KEYSTORE_PASSWORD` /
`FLITR_KEY_ALIAS` / `FLITR_KEY_PASSWORD`。两者都没有时回退 debug 签名，
产物仍可安装，但**不能用于分发**——debug 密钥每台机器不同，装不了更新。

## 文档

- [产品需求](docs/PRD.md)
- [技术架构](docs/ARCHITECTURE.md)
- [真机验证记录](docs/DEVICE-VALIDATION.md)
- [更新记录](CHANGELOG.md)
