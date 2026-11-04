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
version = rootProject.version
description = "RWML example: a sample extension (entry point + ASM mixin)."

dependencies {
    compileOnly(project(":core"))

    // mixin 注解由字节码框架提供。扩展只针对这些注解编译，不会携带框架；宿主类路径中已有框架，若扩展再带一份，
    // 它们会注册到不同于实际执行补丁的注册表中。
    compileOnly(libs.asm.framework)

    // 示例 mixin 引用了游戏类，因此编写 mixin 时需针对游戏编译。游戏依赖仅用于编译：扩展入口由宿主加载，宿主类路径
    // 已包含游戏，所以扩展无需携带游戏。依赖坐标指向构建专用仓库，那里也发布了游戏的反编译源码。
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

// Mapping changes must be published before a new Gradle sync/build invocation.
