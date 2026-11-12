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

### 受限环境（只允许写工作区）

如果构建在启动阶段就失败：

```text
Could not initialize native services.
> Failed to load native library 'native-platform.dll' for Windows 11 amd64.
```

不是 Gradle 或 JDK 坏了。Gradle 默认把 user home 放在 `~/.gradle`、临时文件放在
`%TEMP%`，在只允许写工作区的环境里这两处都写不进去，于是加载原生库时失败。

仓库根目录的 `build.ps1` 已经把这两处都指到 `.tooling/` 下：

```powershell
./build.ps1                              # 构建 release APK
./build.ps1 :app:testDebugUnitTest       # 只跑单元测试
./build.ps1 :app:assembleRelease -Clean  # 先 clean
```

手工等价写法：

```powershell
$env:GRADLE_USER_HOME = "$PWD\.tooling\gradle-home"
$env:GRADLE_OPTS      = "-Djava.io.tmpdir=$PWD\.tooling\jtmp"
./gradlew :app:assembleRelease
```

同样的原因还会让 `.android/analytics.settings` 写不进去，构建时打一行
`Unable to initialize metrics` 警告——不影响产物。

## 签名

正式分发用项目专用 release 密钥。密钥放在仓库内的 `.signing/` 目录，
该目录已列入 `.gitignore`，只存在于本机和构建服务器，不会推到 GitHub：

```text
.signing/
├── flitr-release.jks
└── keystore.properties
```

```properties
storeFile=.signing/flitr-release.jks
storePassword=...
keyAlias=flitr
keyPassword=...
```

`storeFile` 用相对路径（按仓库根目录解析），同一份配置 Windows 和 Linux 通用。

> `.properties` 里 `\` 是转义字符，别写 Windows 反斜杠路径。

CI 从 GitHub Secrets 读取 `FLITR_KEYSTORE_BASE64` / `FLITR_KEYSTORE_PASSWORD` /
`FLITR_KEY_ALIAS` / `FLITR_KEY_PASSWORD`。两者都没有时回退 debug 签名，
产物仍可安装，但**不能用于分发**——debug 密钥每台机器不同，装不了更新。

> ⚠️ `git clean -xdf` 会连被忽略的文件一起删掉，**包括 `.signing/`**。清理前先备份。

## 文档

- [产品需求](docs/PRD.md)
- [技术架构](docs/ARCHITECTURE.md)
- [真机验证记录](docs/DEVICE-VALIDATION.md)
- [更新记录](CHANGELOG.md)
