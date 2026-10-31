/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重启时会将启动器的 JVM 参数传递给子进程，因此需要确保被移除的参数正确：转发 {@code -cp} 会破坏类路径；移除第三方 agent 则会悄然改变游戏运行方式。
 */
class RelauncherTest {

    @Test
    void dropsTheArgumentsTheBootstrapHasToOwn() {
        assertFalse(Relauncher.isForwardable("-cp", null));
        assertFalse(Relauncher.isForwardable("-classpath", null));
        assertFalse(Relauncher.isForwardable("-cp=foo.jar", null));
        assertFalse(Relauncher.isForwardable("--class-path", null));
        assertFalse(Relauncher.isForwardable("--class-path=foo.jar", null));
        assertFalse(Relauncher.isForwardable("-Djava.class.path=foo.jar", null));
        assertFalse(Relauncher.isForwardable("-Djava.home=C:\\jdk", null));
        assertFalse(Relauncher.isForwardable("-Dsun.java.command=com.example.Main", null));
    }

    @Test
    void dropsTheBootClassPathSoTheHostStaysOnOneLoader() {
        assertFalse(Relauncher.isForwardable("-Xbootclasspath/a:game.jar", null));
        assertFalse(Relauncher.isForwardable("-Xbootclasspath/p:game.jar", null));
    }

    @Test
    void refusesToReplayItsOwnMarkers() {
        assertFalse(Relauncher.isForwardable("-Drwml.relaunched=true", null));
        assertFalse(Relauncher.isForwardable("-Drwml.agent.installed=true", null));
        assertFalse(Relauncher.isForwardable("", null));
        assertFalse(Relauncher.isForwardable(null, null));
    }

    @Test
    void keepsTheArgumentsTheLauncherChose() {
        assertTrue(Relauncher.isForwardable("-Xmx2g", null));
        assertTrue(Relauncher.isForwardable("-Xms512m", null));
        assertTrue(Relauncher.isForwardable("-Dfile.encoding=UTF-8", null));
        assertTrue(Relauncher.isForwardable("-XX:+UseG1GC", null));
        assertTrue(Relauncher.isForwardable("-Duser.timezone=UTC", null));
    }

    @Test
    void dropsOnlyItsOwnAgentAndKeepsEveryoneElses() {
        File own = new File("build/libs/rwml-1.0.0.jar");
        assertFalse(Relauncher.isForwardable("-javaagent:" + own.getPath(), own));
        assertFalse(Relauncher.isForwardable("-javaagent:" + own.getPath() + "=args", own));
        assertTrue(Relauncher.isForwardable("-javaagent:profiler.jar", own));
        assertTrue(Relauncher.isForwardable("-javaagent:profiler.jar", null));
    }
}
