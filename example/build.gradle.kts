import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    `java-library`
}

group = "io.github.rwpp-team"
version = "1.0.0"
description = "RWML example: a sample extension (entry point + service + ASM mixin) and a tiny host harness."

val asmFramework = rootProject.file("ASM-Framework.jar")

dependencies {
    compileOnly(project(":"))
    compileOnly(files(asmFramework))
    compileOnly(files(rootProject.layout.buildDirectory.file("libs/game-lib-named.jar")))
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("rwml-example-hello")
    manifest {
        attributes(
            "Implementation-Title" to "RWML Example Extension",
            "Implementation-Version" to project.version.toString(),
            "Implementation-Vendor" to "io.github.rwpp-team"
        )
    }
}