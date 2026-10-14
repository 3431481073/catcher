pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        // sherpa-onnx 的 Android AAR（com.github.k2-fsa:sherpa-onnx）。
        // 放最后：只有它一个 artifact 在这，前面的镜像能命中的就别去打扰 JitPack。
        // 🔴 JitPack 是**按需构建**的 —— 版本号在 maven-metadata 里不等于已经构建好。
        //    实测 v1.13.8 的 aar 是 404（没构建），v1.12.40 是 200（可下）—— 用后者。
        maven("https://jitpack.io")
    }
}

rootProject.name = "Catcher"
include(":app")
