/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

import org.gradle.api.tasks.compile.JavaCompile

plugins {
    `java-library`
}

group = "io.github.rwpp-team"
version = "1.0.0"
description = "RWML example: a sample extension (entry point + ASM mixin)."

dependencies {
    compileOnly(project(":core"))

    // The mixin's annotations come from the bytecode framework. An extension compiles against them
    // and never carries them: the host has the framework on its class path, and a second copy inside
    // an extension would be registered against a different registry than the one that patches.
    compileOnly(libs.asm.framework)

    // The sample's mixin names a game class, and a mixin has to be written against it. The game is
    // compile-only: the entry point of an extension is loaded by the host, which already has the
    // game on its class path, so an extension never carries it. The coordinate is the build's own
    // repository, which is also where the decompiled source of that game is published.
    compileOnly(libs.game.lib)
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

// The game the mixin compiles against is :remapFromEnigma's output, and the coordinate it is read
// through is published with its sources by :publishGameLibForIde, so the compiler has to wait for
// both whenever the jar is going to be produced. On a checkout without work/game-lib.jar there is
// nothing to wait for, and the sample still builds its non-mixin half.
if (rootProject.layout.projectDirectory.file("work/game-lib.jar").asFile.isFile) {
    tasks.withType<JavaCompile>().configureEach {
        dependsOn(":publishGameLibForIde")
    }
}