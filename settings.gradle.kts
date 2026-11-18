/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

rootProject.name = "rwml"

pluginManagement {
    repositories {
        mavenCentral()
        // Android Gradle Plugin 和 com.android.tools:r8 发布在 Google Maven。
        google()
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // com.android.tools:r8（d8 dex 转换器）发布在 Google Maven。
        google()
    }
}

// 与平台无关的扩展系统及其两个宿主。桌面宿主和 Android 应用共享所有与 RWML 运行平台无关的部分，
// 这些内容都位于 :core。
include(":core", ":game-adapter", ":desktop", ":android", ":example", ":setup")
