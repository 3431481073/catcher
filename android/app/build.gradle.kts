plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.clip"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.clip"
        minSdk = 29
        targetSdk = 34
        versionCode = 65
        versionName = "0.13.8"

        // sherpa-onnx 的 AAR 带 4 个 ABI 的原生库（libonnxruntime.so + libsherpa-onnx-jni.so）。
        // 只留真机（arm64-v8a）和模拟器（x86_64）—— 砍掉 armeabi-v7a / x86 能把每个 ABI
        // 约 13MB 的原生库省下来。要换设备再动这里。
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
    }

    // 模型是 .onnx，压缩它既省不了多少、又会让 sherpa 的内存映射读法失去意义。
    androidResources {
        noCompress += "onnx"
    }
}

// Room 的 schema 导出目录：每次构建产出 app/schemas/<库名>/<版本>.json。
// 它是**期望 schema 的比对物**（见 AppDatabase 里 exportSchema 的说明），要提交进仓库。
ksp {
    arg("room.schemaLocation", "${projectDir}/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    // ViewTree 三个 owner 类在 -android 变体里，Kotlin 侧因 KMP 元数据可见性 import 不到，
    // 改用 Java helper（OverlayViewTreeHelper.java，javac 只看字节码）调用；显式声明确保 javac 解析到
    implementation("androidx.lifecycle:lifecycle-runtime-android:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-android:2.8.6")
    implementation("androidx.savedstate:savedstate:1.2.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // M2: DataStore / Retrofit + OkHttp / lifecycle-process
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")

    // M4: WorkManager（上传重试队列）
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // 本地 ASR：sherpa-onnx（包名固定 com.k2fsa.sherpa.onnx，JNI 绑定写死不能改）
    // 版本锁 v1.12.40 —— JitPack 是按需构建，v1.13.8 的 aar 实测 404（还没构建）
    implementation("com.github.k2-fsa:sherpa-onnx:v1.12.40")
}
