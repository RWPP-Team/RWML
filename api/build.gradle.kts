/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.api.JavaVersion

// ------------------------------------------------------------------------------------------------
// RWML 扩展 API：事件、配置、网络与游戏对象模型。
//
// 这些内容不是加载器本身，因此不放进 :core。:core 有一条平台边界检查，禁止核心字节码出现 android/、
// com/corrodinggames/、java/awt/、javax/swing/ 的引用，而这里的 World 与 GUI 带有以 android.graphics
// 为参数的绘制方法；:core 的 POM 也不声明任何依赖，而这里的模型需要 okhttp、序列化与 Koin。
// 单独成模块后，扩展只需依赖本模块即可同时拿到 :core。
//
// 世界与界面的绘制方法以 android.graphics 的 Paint/RectF 为参数。这两个类由游戏自带（映射后的 game-lib 里就有），
// 不是 Android 框架：桌面端与安卓端都由游戏本体提供，因此只对游戏编译，且不以 compileOnly 之外的形式外泄，
// 使用者的类路径不会因此带上游戏。游戏实现由扩展各自提供。
// ------------------------------------------------------------------------------------------------

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    alias(libs.plugins.codeorigin)
    `java-library`
    `maven-publish`
    signing
}

description = "RWML api: events, configuration, networking, and the game object model."

base { archivesName.set("rwml-api") }

group = "io.github.rwpp-team"
version = rootProject.version

dependencies {
    api(project(":core"))
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    api(libs.tomlkt)
    api(libs.koin.core)
    api(libs.koin.annotations)
    api(libs.slf4j.api)
    api(libs.kotlin.reflect)
    api(libs.codeorigin)
    api(libs.protobuf.java)
    implementation(libs.minimal.json)
    implementation(libs.kotlin.stdlib)

    compileOnly(libs.game.lib)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE.md")) { into("META-INF") }
}

// 编译所针对的游戏由 `:remapFromEnigma` 生成，`:publishGameLibForIde` 会连同源码一起发布依赖坐标，
// 因此每次编译时都要等待这两个任务。若检出目录没有 work/game-lib.jar，则无需等待。
if (rootProject.layout.projectDirectory.file("work/game-lib.jar").asFile.isFile) {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        dependsOn(":publishGameLibForIde")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "rwml-api"

            pom {
                name.set("RWML api")
                description.set(project.description.toString())
                url.set("https://github.com/RWPP-Team/RWML")
                licenses {
                    license {
                        name.set("RWPP-Team Shield License 1.0.0")
                        url.set("https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md")
                    }
                }
                developers {
                    developer {
                        id.set("rwpp-team")
                        name.set("RWPP Team")
                        email.set("1225327866@qq.com")
                        organization.set("RWPP-Team")
                        organizationUrl.set("https://github.com/RWPP-Team")
                    }
                }
                scm {
                    connection.set("scm:git://github.com/RWPP-Team/RWML.git")
                    developerConnection.set("scm:git:ssh://github.com/RWPP-Team/RWML.git")
                    url.set("https://github.com/RWPP-Team/RWML")
                }
            }
        }
    }

    repositories {
        maven {
            name = "localStaging"
            url = uri(layout.buildDirectory.dir("central-staging").get().asFile)
        }
        maven {
            name = "centralPortal"
            val snapshot = version.toString().endsWith("SNAPSHOT")
            url = uri(
                if (snapshot) {
                    "https://central.sonatype.com/repository/maven-snapshots/"
                } else {
                    "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
                }
            )
            val user = providers.gradleProperty("centralUsername").orNull
            val secret = providers.gradleProperty("centralPassword").orNull
            if (user != null && secret != null) {
                credentials {
                    username = user
                    password = secret
                }
            }
        }
    }
}

fun gradlePropertyOrNull(name: String): String? =
    providers.gradleProperty(name).orNull?.takeIf { it.trim().isNotEmpty() }

fun environmentOrNull(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }

signing {
    val inMemoryKey = gradlePropertyOrNull("signingInMemoryKey") ?: environmentOrNull("GPG_PRIVATE_KEY")
    val inMemoryPassword =
        gradlePropertyOrNull("signingInMemoryPassword") ?: environmentOrNull("GPG_PASSPHRASE")
    val keyId = gradlePropertyOrNull("signing.gnupg.keyName") ?: gradlePropertyOrNull("signing.keyId")

    isRequired = (inMemoryKey != null || keyId != null) && !version.toString().endsWith("SNAPSHOT")
    if (inMemoryKey != null) {
        useInMemoryPgpKeys(inMemoryKey, inMemoryPassword)
        sign(publishing.publications["maven"])
    } else if (keyId != null) {
        useGpgCmd()
        sign(publishing.publications["maven"])
    }
}
