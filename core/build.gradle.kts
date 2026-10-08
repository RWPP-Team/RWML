/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

import org.gradle.jvm.toolchain.JavaLanguageVersion

// ------------------------------------------------------------------------------------------------
// RWML core: everything that does not depend on where RWML runs.
//
// The API an extension is written against, the loader that discovers and orders extensions, the
// boot a host fills in with its platform's steps. Built-in interfaces live in their host modules.
// The game itself is not here, and neither is any host: a module either hosts this one and supplies
// a HostPlatform, or extends it.
//
// Nothing here names the game, so the module has no dependency on any game jar - a class-file
// reference to one would put the game on the class path of every consumer, which is what makes the
// game's mixins silently stop applying on Android.
//
// This is the module RWML publishes. An extension is compiled against it; the desktop host is not
// published, because it is a host rather than a dependency - it ships as the drop-in game-lib.jar
// that :packageGameJar builds.
// ------------------------------------------------------------------------------------------------

plugins {
    `java-library`
    `maven-publish`
    signing
}

description = "RWML core: the platform-neutral extension system (API, loader, boot)."

// The artifact is named for the coordinate it is published under, so a local build and a downloaded
// one are recognisably the same thing.
base { archivesName.set("rwml-core") }

group = "io.github.rwpp-team"
version = rootProject.version

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    withJavadocJar()
    withSourcesJar()
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE.md")) { into("META-INF") }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

val verifyPlatformBoundary = tasks.register("verifyPlatformBoundary") {
    group = "verification"
    description = "Rejects game and host-specific references in the shared core's class files."
    dependsOn(tasks.named("classes"))
    inputs.files(sourceSets["main"].output.classesDirs)
    doLast {
        val forbidden = listOf(
            "java/awt/", "javax/swing/", "java/net/URLClassLoader", "java/lang/instrument/",
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
// Publishing
//
// The POM states no dependency: nothing outside the JDK is needed to use this module, and the two
// frameworks the desktop bundles are not part of the API an extension is written against. That is
// also why the module is published alone.
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
        // Everything is staged here first, so the bundle the Central Portal is handed is exactly
        // what was built and reviewed.
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
            // Credentials are only attached when they were given, so a local build never asks for
            // them and a publish without them fails at the upload rather than at configuration.
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

// A property that was declared but left empty is treated as absent: `gradle.properties` ships the
// signing key names with no value, and an empty string is not a key.
fun gradlePropertyOrNull(name: String): String? =
    providers.gradleProperty(name).orNull?.takeIf { it.trim().isNotEmpty() }

fun environmentOrNull(name: String): String? =
    System.getenv(name)?.takeIf { it.trim().isNotEmpty() }

signing {
    val inMemoryKey = gradlePropertyOrNull("signingInMemoryKey") ?: environmentOrNull("GPG_PRIVATE_KEY")
    val inMemoryPassword =
        gradlePropertyOrNull("signingInMemoryPassword") ?: environmentOrNull("GPG_PASSPHRASE")
    val keyId = gradlePropertyOrNull("signing.gnupg.keyName") ?: gradlePropertyOrNull("signing.keyId")

    // A snapshot is not signed, and neither is a build with no key: the signature is what the
    // portal checks, and requiring it from a machine that has no key would only stop local builds.
    isRequired = (inMemoryKey != null || keyId != null) && !version.toString().endsWith("SNAPSHOT")
    if (inMemoryKey != null) {
        useInMemoryPgpKeys(inMemoryKey, inMemoryPassword)
        sign(publishing.publications["maven"])
    } else if (keyId != null) {
        useGpgCmd()
        sign(publishing.publications["maven"])
    }
}
