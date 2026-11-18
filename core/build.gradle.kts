/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.api.JavaVersion

// ------------------------------------------------------------------------------------------------
// RWML 核心模块：包含所有与 RWML 运行平台无关的内容。
//
// 包含扩展使用的 API、发现并排序扩展的加载器，以及由宿主补充平台专属步骤的启动流程。内置界面位于各宿主模块。
// 此处不包含游戏本身或任何宿主：其他模块要么提供 HostPlatform 来承载本模块，要么扩展本模块。
//
// 本模块没有引用任何游戏类，因此不依赖游戏 jar。若字节码引用游戏类，所有使用者的类路径都会包含游戏，
// 这会导致游戏 mixin 在 Android 上悄然失效。
//
// RWML 对外发布此模块，扩展以它作为编译依赖。桌面宿主不会单独发布，因为它是宿主而非依赖；它会随
// `:packageGameJar` 构建的替换版 game-lib.jar 一起发布。
// ------------------------------------------------------------------------------------------------

plugins {
    `java-library`
    `maven-publish`
    signing
}

description = "RWML core: the platform-neutral extension system (API, loader, boot)."

// 构件名称与发布坐标一致，便于辨认本地构建和下载所得的文件。
base { archivesName.set("rwml-core") }

group = "io.github.rwpp-team"
version = rootProject.version

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withJavadocJar()
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE.md")) { into("META-INF") }
}

tasks.processResources {
    val rwmlVersion = project.version.toString()
    inputs.property("rwmlVersion", rwmlVersion)
    filesMatching("META-INF/rwml/version.properties") {
        expand("rwmlVersion" to rwmlVersion)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

val verifyPlatformBoundary = tasks.register("verifyPlatformBoundary") {
    group = "verification"
    description = "Rejects host-specific and Android-unavailable JDK references in the shared core's class files."
    dependsOn(tasks.named("classes"))
    inputs.files(sourceSets["main"].output.classesDirs)
    doLast {
        val forbidden = listOf(
            "java/awt/", "javax/swing/", "java/net/URLClassLoader", "java/lang/instrument/",
            "java/net/http/", "java/util/HexFormat",
            "android/", "io/github/rwml/desktop/", "com/corrodinggames/"
        )
        for (directory in sourceSets["main"].output.classesDirs) {
            for (file in directory.walkTopDown().filter { it.isFile && it.extension == "class" }) {
                val constants = file.readBytes().toString(Charsets.ISO_8859_1)
                val reference = forbidden.firstOrNull { constants.contains(it) }
                check(reference == null) { "${file.name} references host-specific code: $reference" }
                check(!file.relativeTo(directory).invariantSeparatorsPath.startsWith("io/github/rwml/builtin/")) {
                    "Builtin UI implementations belong to host modules: ${file.name}"
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyPlatformBoundary)
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

// ------------------------------------------------------------------------------------------------
// 发布
//
// POM 不声明依赖：使用本模块无需 JDK 以外的库，桌面版打包的两个框架也不是扩展 API 的组成部分。
// 因此本模块单独发布。
// ------------------------------------------------------------------------------------------------

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "rwml-core"

            pom {
                name.set("RWML core")
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
                    connection.set("scm:git:git://github.com/RWPP-Team/RWML.git")
                    developerConnection.set("scm:git:ssh://github.com/RWPP-Team/RWML.git")
                    url.set("https://github.com/RWPP-Team/RWML")
                }
            }
        }
    }

    repositories {
        // 先将所有文件暂存到此处，确保交给 Central Portal 的包与构建并审核过的内容完全一致。
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
            // 仅在提供凭据时才附加它们，因此本地构建无需凭据；未提供凭据的发布会在上传时失败，而非配置阶段。
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

// 已声明但为空的属性按未设置处理：`gradle.properties` 中列出了签名密钥属性名但没有填写值，空字符串并非密钥。
fun gradlePropertyOrNull(name: String): String? =
    providers.gradleProperty(name).orNull?.takeIf { it.trim().isNotEmpty() }

fun environmentOrNull(name: String): String? =
    System.getenv(name)?.takeIf { it.trim().isNotEmpty() }

signing {
    val inMemoryKey = gradlePropertyOrNull("signingInMemoryKey") ?: environmentOrNull("GPG_PRIVATE_KEY")
    val inMemoryPassword =
        gradlePropertyOrNull("signingInMemoryPassword") ?: environmentOrNull("GPG_PASSPHRASE")
    val keyId = gradlePropertyOrNull("signing.gnupg.keyName") ?: gradlePropertyOrNull("signing.keyId")

    // 快照版本以及未提供密钥的构建都不签名。门户检查的是签名；若在没有密钥的机器上强制签名，只会阻止本地构建。
    isRequired = (inMemoryKey != null || keyId != null) && !version.toString().endsWith("SNAPSHOT")
    if (inMemoryKey != null) {
        useInMemoryPgpKeys(inMemoryKey, inMemoryPassword)
        sign(publishing.publications["maven"])
    } else if (keyId != null) {
        useGpgCmd()
        sign(publishing.publications["maven"])
    }
}
