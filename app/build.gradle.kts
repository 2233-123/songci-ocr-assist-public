import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// 签名配置
//   1) keystore.properties（不入库）→ release 签名；CI 从 Secrets 生成该文件
//   2) 工程根目录的 debug.jks → 本机 assembleDebug 也能出可安装包
//      （默认 debug keystore 在 %USERPROFILE%\.android 下，受限环境可能写不了）
// ⚠️ release keystore 与 360 加固重签名必须用**同一个**，否则被判二次打包而闪退
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile")?.isNotBlank() == true

val debugKeystoreFile = rootProject.file("debug.jks")
val hasDebugKeystore = debugKeystoreFile.exists()

fun prop(name: String, fallback: String): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: fallback

android {
    namespace = "com.songci.assist"
    compileSdk = prop("songci.compileSdk", "34").toInt()

    defaultConfig {
        applicationId = "com.songci.assist"
        minSdk = prop("songci.minSdk", "26").toInt()
        targetSdk = prop("songci.targetSdk", "34").toInt()
        versionCode = prop("songci.versionCode", "1").toInt()
        versionName = prop("songci.versionName", "0.1.0")

        // ML Kit 模型随 APK 打包（离线可用）；不开这个开关会退化为联网下载模型
        resourceConfigurations += listOf("zh", "en")
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasDebugKeystore) {
            create("debugLocal") {
                storeFile = debugKeystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (hasReleaseKeystore) {
            create("release") {
                // storeFile 是相对**工程根目录**的路径（keystore.properties 与 CI 都这么写）；
                // 用 file() 会相对 app/ 解析，所以必须走 rootProject.file()
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            if (hasDebugKeystore) {
                signingConfig = signingConfigs.getByName("debugLocal")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES",
        )
    }

    // 纯 JVM 单测要读 assets/verses.json，因此把 assets 与 test 资源都挂到 classpath
    sourceSets {
        getByName("test") {
            resources.srcDirs("src/test/resources", "src/main/assets")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 离线中文 OCR（bundled 模型，不需要 Play 服务，不需要联网）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")

    testImplementation("junit:junit:4.13.2")
    // JVM 单测里 org.json 不在 classpath（Android 的 android.jar 只是 stub），显式补上
    testImplementation("org.json:json:20240303")
}
