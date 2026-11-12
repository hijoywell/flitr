import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 版本号集中在这里, 避免 versionName 与产物文件名不一致。
val appVersionName = "1.0.2"
val appVersionCode = 15

// 当前只打包 arm64-v8a（见 defaultConfig.ndk.abiFilters），产物文件名需要带上它。
val apkAbi = "arm64-v8a"

// ---------------------------------------------------------------------------
// release 签名
//
// 优先级：环境变量（CI）> keystore.properties（本地）> 回退 debug 签名
//
// 回退分支是为了让任何人 clone 下来都能直接 assembleRelease 出可安装的包。
// 但 debug 密钥是 AGP 在每台机器上现生成的，每次 CI 构建都不一样，
// 用它发布的包无法升级安装，也不能上架——所以正式分发必须走上面两条路径。
// ---------------------------------------------------------------------------
val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { stream -> keystoreProps.load(stream) }
}

fun signingValue(propKey: String, envKey: String): String? =
    System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(propKey)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "FLITR_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "FLITR_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "FLITR_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "FLITR_KEY_PASSWORD")

val hasReleaseSigning = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null &&
    file(releaseStoreFile).exists()

android {
    namespace = "com.aios.flitr"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aios.flitr"
        minSdk = 30
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // ML Kit 为 4 种 ABI 各带一份原生库, 全打进去会让 APK 体积翻几倍。
        // 2026 年 minSdk 30 的在用设备基本都是 arm64, 因此只保留 arm64-v8a。
        // 正式分发走 AAB 时由 Play 按 ABI 自动拆分, 不需要这个限制。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.lifecycle(
                    "[Flitr] 未找到 release 签名配置（keystore.properties 或 FLITR_* 环境变量），" +
                        "回退 debug 签名——产物仅可用于本机自测。"
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES"
        )
    }

    lint {
        abortOnError = false
    }
}

// 产物命名对齐常见开源项目的分发规范: Flitr-<版本>-android-<abi>.apk
// 默认的 app-release.apk 从文件名看不出是哪个版本、哪个架构, 下载下来容易混淆。
androidComponents {
    onVariants { variant ->
        if (variant.buildType == "release") {
            variant.outputs.forEach { output ->
                (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                    ?.outputFileName
                    ?.set("Flitr-$appVersionName-android-$apkAbi.apk")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // 端侧中文 OCR, 内置模型, 全程离线
    implementation(libs.mlkit.text.recognition.chinese)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation("junit:junit:4.13.2")
}
