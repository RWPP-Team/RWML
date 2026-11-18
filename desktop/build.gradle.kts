/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.toolchain.JavaLanguageVersion

// ------------------------------------------------------------------------------------------------
// RWML 桌面版：JVM 宿主。
//
// 这里实现只有 JVM 能提供的能力：运行时类文件转换器、每个扩展独立的类加载器，以及可实际执行的 Java 版本要求。
// 最终交由游戏自身启动器管理唯一窗口和 GUI 引擎。
//
// 此构件用于替换游戏的 game-lib.jar，因此 jar 会将 :core、ASM 框架和 ASM 库一并打包。游戏本身不包含在此处，
// 而由 `:packageGameJar` 合并映射后的游戏。
// ------------------------------------------------------------------------------------------------

plugins {
    `java-library`
    `maven-publish`
    signing
}

description = "RWML desktop: the JVM host that turns RWML into the game's drop-in game-lib.jar."

// 构件沿用替换包的名称，便于辨认构建输出和发布的 game-lib.jar。
base { archivesName.set("rwml") }

// Java 8 引导程序会在加载现代代码前启动游戏，因此单独编译且使用空类路径。
val bootstrap = sourceSets.create("bootstrap")

tasks.named<JavaCompile>(bootstrap.compileJavaTaskName) {
    options.release.set(8)
    // 这是强制约束而非惯例：任何依赖都不得进入 Java 8 层。
    classpath = files()
}

sourceSets["main"].compileClasspath += bootstrap.output
sourceSets["test"].compileClasspath += bootstrap.output
sourceSets["test"].runtimeClasspath += bootstrap.output

dependencies {
    implementation("com.googlecode.soundlibs:vorbisspi:1.0.3.3")
    api(project(":core"))
    implementation(project(":game-adapter"))
    api(libs.asm.framework)
    runtimeOnly(libs.kotlin.stdlib)
    implementation(libs.org.ow2.asm.asm3)
    implementation(libs.org.ow2.asm.asm.tree)
    implementation(libs.org.ow2.asm.asm.commons3)
    implementation(libs.org.ow2.asm.asm.util)

    // 桌面端通过游戏静态成员声明平台选项，并交由游戏自身启动器启动，因此需针对游戏编译，但不会打包游戏。
    // 可运行 jar 由 `:packageGameJar` 生成，负责合并映射后的游戏。依赖坐标指向构建专用仓库，其中也发布了
    // 游戏的反编译源码，供 IDE 界面查看。
    compileOnly(files(rootProject.extra["rwmlMappedGameJar"]))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    withJavadocJar()
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("host") {
            groupId = "io.github.rwpp-team"
            artifactId = "rwml-desktop"
            version = rootProject.version.toString()
            artifact(tasks.named<Jar>("jar"))
            artifact(tasks.named<Jar>("sourcesJar"))
            artifact(tasks.named<Jar>("javadocJar"))
            pom {
                name.set("RWML desktop host")
                description.set("The fat desktop host used by the RWML native server launcher.")
                url.set("https://github.com/RWPP-Team/RWML")
                licenses {
                    license {
                        name.set("RWPP-Team Shield License 1.0.0")
                        url.set("https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md")
                    }
                }
                developers {
                    developer {
                        id.set("RWPP-Team")
                        name.set("RWPP Team")
                        email.set("1225327866@qq.com")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/RWPP-Team/RWML.git")
                    developerConnection.set("scm:git:ssh://git@github.com/RWPP-Team/RWML.git")
                    url.set("https://github.com/RWPP-Team/RWML")
                }
            }
        }
    }
    repositories {
        maven {
            name = "localStaging"
            url = uri(layout.buildDirectory.dir("central-staging"))
        }
    }
}

if (providers.gradleProperty("signing.gnupg.keyName").isPresent) {
    signing {
        useGpgCmd()
        sign(publishing.publications["host"])
    }
}

tasks.named<Jar>("jar") {
    // Fat jar 会解包运行时类路径。类路径是文件集合，解包会丢失这些文件由哪些任务生成的信息，因此在此显式声明生产任务。
    dependsOn(configurations.runtimeClasspath)

    // Java 8 引导程序自身的 `main` 是 jar 入口，因此也一并打包。
    from(bootstrap.output)

    from(configurations.runtimeClasspath.map { config ->
        config.files.map { if (it.isDirectory) it else zipTree(it) }
    }) {
        exclude(
            "META-INF/*.MF",
            "META-INF/maven/**",
            "META-INF/*.kotlin_module",
            "META-INF/versions/**",
            "module-info.class"
        )
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Premain-Class" to "io.github.rwml.asm.RwmlAgent",
            "Agent-Class" to "io.github.rwml.asm.RwmlAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Implementation-Title" to "RWML",
            "Implementation-Version" to version.toString(),
            "Implementation-Vendor" to "io.github.rwpp-team"
        )
    }
}

// Java 8 引导程序属于该构件，因此其源码也包含在 sources jar 中。
tasks.named<Jar>("sourcesJar") {
    from(bootstrap.allSource)
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.register<Zip>("bundleForCentral") {
    group = "publishing"
    dependsOn("check", "publishHostPublicationToLocalStagingRepository")
    val coordinates = "io/github/rwpp-team/rwml-desktop/$version"
    from(layout.buildDirectory.dir("central-staging")) {
        include("$coordinates/**")
        exclude("**/*.asc.*")
    }
    archiveFileName.set("rwml-desktop-$version-central-bundle.zip")
    destinationDirectory.set(rootProject.layout.buildDirectory)
}
