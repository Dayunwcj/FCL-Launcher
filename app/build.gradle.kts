// ============================================================
// :app —— 我们的前端（Compose 界面 + 下载器 + 版本管理）
// 后端依赖 :FCL（FCL 的 JNI + JRE + GL4ES）
// ============================================================

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

android {
    namespace = "com.doubao.mclauncher"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.doubao.mclauncher"
        minSdk = 26            // FCL 的硬要求（Android 8.0+）
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk {
            // 只出 arm64：GL4ES / JRE 都以 arm64 为主
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
        getByName("release") { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "**/libbytehook.so",
                "**/libglxshim.so",     // FCL 自带 jniLibs 与 kopper-zink AAR 各有一份
                "**/libEGL_mesa.so",
                "**/libOSMesa.so"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 启动后端：FCL（libpojavexec.so / libfcl.so / libgl4es_114.so + JVMActivity）
    implementation(project(":FCL"))
    implementation(libs.appcompat)   // JVMActivity 继承自 AppCompatActivity

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.core:core-ktx:1.13.0")

    // 网络 / 协程 / 解压
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.coroutines.android)
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation(libs.xz)
}
