/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

// ------------------------------------------------------------------------------------------------
// RWML desktop: the JVM host.
//
// It adds what only a JVM can do - a live class-file transformer, a class loader per extension, a
// Java version requirement that can actually be acted on - and it ends in the game's own launcher,
// which owns the single window and the single GUI engine.
//
// The artifact is the drop-in replacement for the game's game-lib.jar, which is why the jar bundles
// :core, the ASM framework and the ASM libraries into itself. It does not carry the game: that is
// added by :packageGameJar, which merges the remapped game in.
// ------------------------------------------------------------------------------------------------

plugins {
    `java-library`
}

description = "RWML desktop: the JVM host that turns RWML into the game's drop-in game-lib.jar."

// The artifact keeps the name the drop-in is known by, so a build output and a released game-lib.jar
// are recognisably the same thing.
base { archivesName.set("rwml") }

// The Java 8 bootstrap. It starts the game before anything modern can be loaded, so it is compiled
// separately and against an empty class path.
val bootstrap = sourceSets.create("bootstrap")

tasks.named<JavaCompile>(bootstrap.compileJavaTaskName) {
    options.release.set(8)
    // A hard guarantee, not a convention: no dependency may leak into the Java 8 layer.
    classpath = files()
}

sourceSets["main"].compileClasspath += bootstrap.output
sourceSets["test"].compileClasspath += bootstrap.output
sourceSets["test"].runtimeClasspath += bootstrap.output

dependencies {
    api(project(":core"))
    implementation(project(":game-adapter"))
    api(libs.asm.framework)
    runtimeOnly(libs.kotlin.stdlib)
    implementation(libs.org.ow2.asm.asm3)
    implementation(libs.org.ow2.asm.asm.tree)
    implementation(libs.org.ow2.asm.asm.commons3)
    implementation(libs.org.ow2.asm.asm.util)

    // The desktop states its platform choices through the game's statics and hands over to the
    // game's own launcher, so it is written against the game. It never carries it: the runnable jar
    // is :packageGameJar's, which merges the remapped game in. The coordinate is the build's own
    // repository, which is also where the decompiled source of that game is published, so the
    // interface can open it.
    compileOnly(libs.game.lib)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Jar>("jar") {
    // The fat jar unpacks the runtime class path, which is a file collection: unpacking it hides
    // which tasks produced those files, so the producers are named here.
    dependsOn(configurations.runtimeClasspath)

    // The Java 8 bootstrap's own `main` is the jar's entry point, so it ships in the same archive.
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

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    withJavadocJar()
    withSourcesJar()
}

// The Java 8 bootstrap is part of the artifact, so its sources are part of the sources jar too.
tasks.named<Jar>("sourcesJar") {
    from(bootstrap.allSource)
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// The game the desktop compiles against is :remapFromEnigma's output, and the coordinate it is read
// through is published with its sources by :publishGameLibForIde, so the compiler has to wait for
// both whenever the jar is going to be produced. On a checkout without work/game-lib.jar there is
// nothing to wait for, and the desktop still builds its own half.
if (rootProject.layout.projectDirectory.file("work/game-lib.jar").asFile.isFile) {
    tasks.withType<JavaCompile>().configureEach {
        dependsOn(":publishGameLibForIde")
    }
}
