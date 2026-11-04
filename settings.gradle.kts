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

fun resolveCurrentGameLibraryRepository(baseRepository: java.io.File): java.io.File {
    val base = baseRepository.toPath().toAbsolutePath().normalize()
    val pointer = base.resolve("current-repository.txt")
    if (!java.nio.file.Files.isRegularFile(pointer)) return base.toFile()

    val relative = java.nio.file.Files.readString(pointer, Charsets.UTF_8).trim()
    require(relative.isNotEmpty()) { "${pointer.toFile()} is empty." }
    val relativePath = java.nio.file.Path.of(relative)
    require(!relativePath.isAbsolute) { "${pointer.toFile()} must contain a relative repository path." }
    val current = base.resolve(relativePath).normalize()
    require(current.startsWith(base)) { "${pointer.toFile()} escapes the local game repository." }
    require(java.nio.file.Files.isDirectory(current)) {
        "The current game library repository does not exist: $current"
    }
    return current.toFile()
}

val currentGameLibraryRepository = resolveCurrentGameLibraryRepository(rootDir.resolve("build/local-maven"))

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // com.android.tools:r8（d8 dex 转换器）发布在 Google Maven。
        google()
        // 映射后的游戏及其反编译源码。构建会在此发布两者，因此各模块可以通过坐标而不是文件依赖游戏：
        // 文件依赖不包含供界面查看的源码，而带有 `-sources` 分类器的坐标正是其他库提供源码的常见方式。
        exclusiveContent {
            forRepository {
                maven {
                    name = "RWML games"
                    url = uri(currentGameLibraryRepository)
                }
            }
            filter {
                includeModule("io.github.rwpp-team", "game-lib")
            }
        }
    }
}

// 与平台无关的扩展系统及其两个宿主。桌面宿主和 Android 应用共享所有与 RWML 运行平台无关的部分，
// 这些内容都位于 :core。
include(":core", ":game-adapter", ":desktop", ":android", ":example", ":setup")
