/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

import java.io.InputStream;
import java.util.Properties;

/**
 * RWML 构建流程与运行时入口共用的命名约定。
 *
 * <p>RWML 会作为游戏 {@code game-lib.jar} 的直接替代品发布：游戏类会合并到 RWML 的 JAR 中，并覆盖原文件。游戏启动器根据 JAR 的
 * {@code Main-Class} 启动 JVM，而该值固定为 {@code com.corrodinggames.rts.java.Main}，因此替代 JAR 必须提供同名类。RWML 在
 * {@code src/bootstrap/java} 中实现此类。</p>
 *
 * <p>Enigma 映射会将游戏自身的入口重命名为 {@code MainLauncher}；在合并游戏 JAR 前，{@code remapFromEnigma} 会应用此更改。
 * 打包时不会再改写字节码，而运行时会在此处获取需要移交控制权的类名。</p>
 *
 * <p>此类属于 <em>启动层</em>，按 Java 8 编译，且不能依赖 RWML 核心。因此，目标 JVM 版本从 {@value #BOOTSTRAP_RESOURCE} 中读取：
 * {@code packageGameJar} 会将属性文件写入 JAR，其唯一数据源是 {@code gradle.properties}。当 RWML 未打包运行时（例如测试、IDE 或演示程序），
 * 则使用下方常量作为回退值。</p>
 *
 * <p>{@link #GAME_MAIN} 有意保留在 {@code com.corrodinggames.rts.java} 包中：同一包中的 {@code e}、{@code i}、{@code j}、{@code u} 和
 * {@code b.a} 都会引用游戏入口，包括通过包级私有成员进行访问。将其移动到其他位置会导致链接阶段访问失败。</p>
 */
public final class GameEntry {

    /** 游戏启动器的启动类，因此该类必须由 RWML 接管。 */
    public static final String MAIN = "com.corrodinggames.rts.java.Main";

    /**
     * Enigma 映射中的游戏自身入口名称。
     *
     * <p>此值定义为常量，而非设置项：名称来自构建使用的映射，运行时无需再配置。</p>
     */
    public static final String GAME_MAIN = "com.corrodinggames.rts.java.MainLauncher";

    /** RWML 目标 JVM 功能版本，与 {@code rwmlTargetJavaVersion} 一致。 */
    public static final int DEFAULT_TARGET_JAVA_VERSION = 25;

    /** {@code packageGameJar} 写入打包后 JAR 的文件。 */
    public static final String BOOTSTRAP_RESOURCE = "META-INF/rwml-bootstrap.properties";

    private static final String KEY_TARGET_JAVA_VERSION = "targetJavaVersion";

    private static final Properties PACKAGE_PROPERTIES = loadPackageProperties();

    private GameEntry() {
    }

    /**
     * @return RWML 应使用的 JVM 主版本：已打包时返回 JAR 构建时使用的值，未打包运行时返回 {@link #DEFAULT_TARGET_JAVA_VERSION}
     */
    public static int targetJavaVersion() {
        String packaged = PACKAGE_PROPERTIES.getProperty(KEY_TARGET_JAVA_VERSION);
        if (packaged != null && !packaged.trim().isEmpty()) {
            try {
                return Integer.parseInt(packaged.trim());
            } catch (NumberFormatException ignored) {
                // 回退到编译时默认值。
            }
        }
        return DEFAULT_TARGET_JAVA_VERSION;
    }

    private static Properties loadPackageProperties() {
        Properties properties = new Properties();
        InputStream stream = null;
        try {
            ClassLoader loader = GameEntry.class.getClassLoader();
            stream = loader == null
                ? ClassLoader.getSystemResourceAsStream(BOOTSTRAP_RESOURCE)
                : loader.getResourceAsStream(BOOTSTRAP_RESOURCE);
            if (stream != null) {
                properties.load(stream);
            }
        } catch (Throwable ignored) {
            // 当前未打包运行，或资源无法读取：使用编译时默认值。
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                    // 没有可执行的处理方式。
                }
            }
        }
        return properties;
    }
}
