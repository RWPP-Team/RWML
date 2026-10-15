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
        // Android Gradle Plugin and com.android.tools:r8 live on Google Maven.
        google()
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.4.20"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // com.android.tools:r8 (the d8 dexer) is published to Google Maven.
        google()
        // The remapped game and the sources decompiled out of it. The build publishes both here
        // itself, which is what lets the modules depend on the game by coordinate rather than by
        // file: a file dependency carries no sources for the interface to show, and a coordinate
        // with a `-sources` classifier is how every other library's source is reached.
        maven {
            name = "RWML games"
            url = uri(rootDir.resolve("build/local-maven"))
            content {
                includeModule("io.github.rwpp-team", "game-lib")
            }
        }
    }
}

// The platform-neutral extension system and its two hosts. The desktop host and the Android app
// share everything that does not depend on where RWML runs, which is all of :core.
include(":core", ":game-adapter", ":desktop", ":android", ":example")
