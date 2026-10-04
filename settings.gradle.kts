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
    }
}

include(":example", ":android")
