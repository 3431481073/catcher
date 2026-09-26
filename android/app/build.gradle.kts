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
        versionCode = 37
        versionName = "0.8.6"
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
}
