/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动器仅根据 JVM 报告的版本决定是否重启游戏，因此解析错误会导致不必要的下载，或使用无法运行 RWML 的旧版 JVM。需要固定测试平台使用过的两种属性格式。
 */
class JvmVersionTest {

    @Test
    void parsesEveryVersionFormatTheJvmHasUsed() {
        assertEquals(8, JvmVersion.parseFeature("1.8"));
        assertEquals(8, JvmVersion.parseFeature("1.8.0_422"));
        assertEquals(9, JvmVersion.parseFeature("9"));
        assertEquals(11, JvmVersion.parseFeature("11.0.2"));
        assertEquals(17, JvmVersion.parseFeature("17"));
        assertEquals(25, JvmVersion.parseFeature("25.0.4.1"));
    }

    @Test
    void ignoresSuffixesAppendedToTheVersion() {
        assertEquals(17, JvmVersion.parseFeature("17-ea"));
        assertEquals(25, JvmVersion.parseFeature("25.0.4.1+1"));
        assertEquals(25, JvmVersion.parseFeature("  25.0.4.1  "));
    }

    @Test
    void reportsNothingRatherThanGuessing() {
        assertEquals(-1, JvmVersion.parseFeature(null));
        assertEquals(-1, JvmVersion.parseFeature(""));
        assertEquals(-1, JvmVersion.parseFeature("   "));
        assertEquals(-1, JvmVersion.parseFeature("not-a-version"));
    }

    @Test
    void readsTheRunningJvm() {
        assertTrue(JvmVersion.current() >= 8,
            "the test JVM should report a usable feature version, got " + JvmVersion.current());
    }
}
