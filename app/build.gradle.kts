plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.mcstation.download"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mcstation.download"
        // Android 5.0 起：依赖 core library desugaring 补齐 java.time/Optional/nio.file 等 API
        minSdk = 21
        targetSdk = 34
        versionCode = 26
        versionName = "1.3.4"
    }

    // 输出 APK 文件名：MCDLAPP {版本号}.apk（如 MCDLAPP 1.1.0-beta2.apk）
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "MCDLAPP $versionName.apk"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 核心库脱糖：okhttp 用 java.time、jsoup 用 Optional/stream、okio 用 java.nio.file，
        // 这些在 Android 5~7 系统里缺失，需要 D8 在打包时用 desugar_jdk_libs 补齐
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // JSON 序列化：用于 API 响应解析与本地缓存
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")

    // 核心 Java API 脱糖库（minSdk 21 必需，编译期由 D8 引用；首次需联网拉取，之后可离线构建）
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Wiki 摘要抓取：OkHttp 请求 + Jsoup 解析（本地 jar，离线构建可用）
    implementation(files("libs/jsoup-1.17.2.jar"))
    implementation(files("libs/okhttp-4.12.0.jar"))
    implementation(files("libs/okio-jvm-3.6.0.jar"))

    debugImplementation("androidx.compose.ui:ui-tooling")
}
