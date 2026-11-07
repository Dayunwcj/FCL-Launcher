
// ============================================================
// FCL 模块（已从 application 转为 library）
// ------------------------------------------------------------
// 只改构建配置，Java/Kotlin/C 源码一行不动。
// 目的：把它当成 :app 的“启动后端”依赖，让 :app 拥有 applicationId 和桌面入口。
// ============================================================

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.tungsten.fcl"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // library 模块不再支持 targetSdk/versionName（AGP 8 起弃用，由 :app 提供）

        // 只编 arm64：GL4ES / JRE 都以 arm64 为主，4 个 ABI 全编既慢又没必要
        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        configureEach {
            resValue("string", "app_version", "1.3.4.5")
            resValue("string", "curse_api_key", "")
            resValue("string", "oauth_api_key", "")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("**/libbytehook.so")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
        }
    }

    ndkVersion = "27.0.12077973"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(project(":ZipFileSystem"))
    implementation(project(":Terracotta"))
    implementation(libs.commons.io)
    implementation(libs.jelf)
    implementation(libs.nanohttpd)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.opennbt)
    implementation(libs.lz4)
    implementation(libs.gson)
    implementation(libs.tomlj)
    implementation(libs.constant.pool.scanner)
    implementation(libs.jsoup)
    implementation(libs.chardet)
    implementation(libs.junrar)
    implementation(libs.bytehook)
    implementation(libs.appcompat)
    implementation(libs.androidx.viewpager2)
    implementation(libs.core.splashscreen)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.recyclerview)
    implementation(libs.coroutines.android)
    implementation(libs.glide)
    implementation(libs.touchcontroller)
    implementation(libs.palette.ktx)
    implementation(libs.gamepad.remapper)
    implementation(libs.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
