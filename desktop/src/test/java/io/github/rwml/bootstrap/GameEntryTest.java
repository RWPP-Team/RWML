/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link GameEntry} 是构建流程与运行时之间的命名约定，因此常量和回退值都很重要：通过 {@code packageGameJar} 生成 JAR 时应优先使用打包值；
 * RWML 未打包运行（例如测试、IDE 或演示程序）时则应使用编译时默认值。
 */
class GameEntryTest {

    @Test
    void fallsBackToTheCompiledDefaultWhenRunningUnpackaged() {
        // 测试类路径中没有 META-INF/rwml-bootstrap.properties，这正是未打包运行的情况。运行时不能改变结果：目标版本只来自生成 JAR 的构建流程。
        assertEquals(GameEntry.DEFAULT_TARGET_JAVA_VERSION, GameEntry.targetJavaVersion());
    }

    @Test
    void theTwoEntryPointsAreDistinctAndShareTheGamesPackage() {
        // RWML 提供启动器使用的入口名称。游戏自身入口是另一个类，且必须保留在相同包中，因为游戏通过包级私有成员访问它。
        assertEquals("com.corrodinggames.rts.java.Main", GameEntry.MAIN);
        assertEquals("com.corrodinggames.rts.java.MainLauncher", GameEntry.GAME_MAIN);
        assertNotEquals(GameEntry.MAIN, GameEntry.GAME_MAIN);
        assertEquals(packageOf(GameEntry.MAIN), packageOf(GameEntry.GAME_MAIN));
    }

    private static String packageOf(String className) {
        return className.substring(0, className.lastIndexOf('.'));
    }
}
