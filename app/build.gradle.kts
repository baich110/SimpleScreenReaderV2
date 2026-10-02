plugins {
    // AGP 9.0+ 内置 Kotlin 支持，不需要单独的 kotlin.android 插件
    id("com.android.application")
}

android {
    namespace = "com.baicha.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.baicha.reader"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        // 只做 Debug，先不碰 R8/混淆
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        // 注意：不开 aidl —— ARM64 环境没有可用的 aidl 二进制（都是 x86_64）
        // 改为手写 Binder 类（等价于 AIDL 生成的代码）
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // 让未 mock 的 Android 框架方法（Log/SystemClock 等）返回默认值而非抛异常，
            // 这样纯逻辑类可以在 JVM 单测里直接构造与验证。
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}