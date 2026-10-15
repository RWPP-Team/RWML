/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

plugins {
    `java-library`
}

description = "Shared game-backed UI services; core and UI frameworks do not depend on game classes."

dependencies {
    api(project(":core"))
    compileOnly(libs.game.lib)
    compileOnly(libs.asm.framework)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

if (rootProject.layout.projectDirectory.file("work/game-lib.jar").asFile.isFile) {
    tasks.withType<JavaCompile>().configureEach {
        dependsOn(":publishGameLibForIde")
    }
}
