// 根构建脚本：只声明插件版本，不配置模块（配置都在 app/build.gradle.kts）。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
