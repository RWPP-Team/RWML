/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * RWML 的第 0 阶段：在游戏启动前运行的代码。
 *
 * <p>它按顺序完成三项工作：</p>
 * <ol>
 *   <li><b>准备运行环境。</b>如果当前 JVM 版本低于 RWML 要求，或字节码转换器尚未安装，就会在合适的 JVM 中重新启动游戏；
 *       必要时会先下载该 JVM，并转发其退出码。为何必须重启才能安装 agent，参见 {@link Relauncher}。</li>
 *   <li><b>启动扩展系统。</b>委托给 {@code io.github.rwml.desktop.DesktopRuntime}，由其发现和加载扩展并注册 mixin。
 *       此处有意使用反射调用：该类使用较新的字节码，而当前类必须能在 Java 8 JVM 上加载。</li>
 *   <li><b>移交给游戏。</b>调用迁移后的游戏入口。无论扩展系统是否启动成功，都会执行此步骤，因此有问题的扩展不会阻止游戏启动。</li>
 * </ol>
 */
public final class Bootstrap {

    /** 当当前进程应继续启动时，由 {@link #ensureRuntime} 返回。 */
    public static final int CONTINUE_IN_PROCESS = -1;

    /**
     * 通过反射加载的 RWML 核心入口。
     *
     * <p>此处不能产生编译期依赖：当前类按 Java 8 编译，不能链接 Java 17 字节码，否则旧 JVM 甚至在解析此类时都会抛出
     * {@code UnsupportedClassVersionError}。</p>
     */
    private static final String CORE_ENTRY_POINT = "io.github.rwml.desktop.DesktopRuntime";

    /** 引导程序调用的 {@link #CORE_ENTRY_POINT} 唯一方法。 */
    private static final String CORE_METHOD = "prepare";

    /**
     * {@link #CORE_ENTRY_POINT} 中的第二个方法：启动扩展声明的界面并返回是否成功，从而决定由谁启动游戏。
     */
    private static final String CORE_INTERFACE_METHOD = "startExtensionInterface";

    private Bootstrap() {
    }

    /**
     * 确保 JVM 满足 RWML 的运行要求；必要时在另一个 JVM 中重新启动游戏。
     *
     * @return 返回 {@link #CONTINUE_IN_PROCESS} 表示在当前 JVM 中继续；如果游戏已在子 JVM 中运行，则返回要转发的退出码
     */
    public static int ensureRuntime(String[] args) {
        int required = GameEntry.targetJavaVersion();
        int current = JvmVersion.current();
        boolean relaunched = Boolean.parseBoolean(
            System.getProperty(Relauncher.RELAUNCHED_PROPERTY, "false"));
        boolean agentInstalled = Boolean.parseBoolean(
            System.getProperty(Relauncher.AGENT_INSTALLED_PROPERTY, "false"));
        File ownJar = Relauncher.ownJar();

        BootstrapLog.info("RWML " + version() + " bootstrap on Java " + current
            + " (Java " + required + " required).");

        if (relaunched) {
            // 当前进程已经是重启产生的子进程：无论状态如何都不能再次重启，否则交接失败时会无限创建新进程。
            if (current < required) {
                BootstrapLog.warn("This JVM is Java " + current + ", below the required Java "
                    + required + " (the relaunch target). Not restarting again; the extension "
                    + "system will report what it can and cannot load.");
            }
            return CONTINUE_IN_PROCESS;
        }

        boolean needNewerJvm = current < required;
        boolean needAgent = ownJar != null && !agentInstalled;
        if (!needNewerJvm && !needAgent) {
            BootstrapLog.info("Running on a suitable JVM with the RWML agent already installed.");
            return CONTINUE_IN_PROCESS;
        }

        File javaHome;
        try {
            if (needNewerJvm) {
                try (BootstrapWindow window = BootstrapWindow.showIfPossible()) {
                    javaHome = JavaRuntime.provision(required);
                }
            } else {
                // 仅缺少 agent，当前 JVM 仍可使用；只需添加 -javaagent 参数并在此 JVM 中重启。
                javaHome = new File(System.getProperty("java.home", "."));
                BootstrapLog.info("Restarting on this JVM so the RWML agent is installed before "
                    + "the game's classes load.");
            }
        } catch (Throwable t) {
            BootstrapLog.warn("Could not prepare Java " + required + ": " + BootstrapLog.describe(t));
            BootstrapLog.debug(t);
            if (current < required) {
                BootstrapLog.warn("Continuing on Java " + current + "; extensions needing a newer "
                    + "JVM will be skipped.");
            }
            return CONTINUE_IN_PROCESS;
        }

        try {
            List<String> command = Relauncher.buildCommand(javaHome, ownJar, ownJar != null, args);
            int exitCode = Relauncher.run(command);
            if (exitCode != 0) {
                BootstrapLog.warn("The game exited with code " + exitCode + ".");
            }
            return exitCode;
        } catch (Throwable t) {
            BootstrapLog.warn("Could not restart the game on " + javaHome + ": "
                + BootstrapLog.describe(t));
            BootstrapLog.debug(t);
            BootstrapLog.warn("Continuing on the current JVM instead.");
            return CONTINUE_IN_PROCESS;
        }
    }

    /**
     * 启动扩展系统，然后启动游戏。
     *
     * @return 要报告的退出码；游戏正常退出时为 {@code 0}
     */
    public static int startGame(String[] args) {
        startExtensionSystem(args);
        if (startExtensionInterface(args)) {
            // 界面由扩展提供，因此由扩展决定要显示什么，包括是否启动游戏。此时不显示内置启动器。
            return 0;
        }
        return handOverToGame(args);
    }

    /**
     * 若扩展声明了界面，则尝试启动该界面。
     *
     * <p>此方法按尽力而为方式设计：核心未提供界面，或声明的界面无法使用时，都会返回 {@code false}；两种情况下都会继续运行下方的内置启动器。</p>
     */
    private static boolean startExtensionInterface(String[] args) {
        try {
            Class<?> launcher = Class.forName(CORE_ENTRY_POINT);
            Method start = launcher.getMethod(CORE_INTERFACE_METHOD, String[].class);
            return Boolean.TRUE.equals(start.invoke(null, (Object) args));
        } catch (Throwable t) {
            BootstrapLog.debug(t);
            return false;
        }
    }

    /** 加载并启动 RWML 核心；报告错误，但不会抛出异常。 */
    private static void startExtensionSystem(String[] args) {
        try {
            Class<?> launcher = Class.forName(CORE_ENTRY_POINT);
            Method prepare = launcher.getMethod(CORE_METHOD, String[].class);
            Object started = prepare.invoke(null, (Object) args);
            if (Boolean.TRUE.equals(started)) {
                BootstrapLog.info("Extension system ready.");
            } else {
                BootstrapLog.warn("The extension system did not start; the game will run without "
                    + "extensions.");
            }
        } catch (Throwable t) {
            BootstrapLog.warn("RWML core could not be loaded (" + BootstrapLog.describe(t)
                + "); the game will run without extensions.");
            BootstrapLog.debug(t);
        }
    }

    /** 调用游戏自身的入口点，并转发其结束状态。 */
    private static int handOverToGame(String[] args) {
        String gameMain = GameEntry.GAME_MAIN;
        BootstrapLog.info("Starting the game: " + gameMain);
        try {
            Class<?> type = Class.forName(gameMain);
            Method main = type.getMethod("main", String[].class);
            main.invoke(null, (Object) args);
            return 0;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            BootstrapLog.error("The game entry point " + gameMain + " is not present in this jar. "
                + "A runnable game jar is produced by the :packageGameJar Gradle task, which merges "
                + "the game into RWML's jar.");
            return 1;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            BootstrapLog.error("The game stopped with an error.", cause);
            return 1;
        } catch (Throwable t) {
            BootstrapLog.error("Could not start " + gameMain + ".", t);
            return 1;
        }
    }

    /** @return JAR 清单中的 RWML 版本；未提供时返回 {@code "development build"} */
    private static String version() {
        try {
            Package pkg = Bootstrap.class.getPackage();
            String version = pkg == null ? null : pkg.getImplementationVersion();
            return version == null || version.trim().isEmpty() ? "development build" : version;
        } catch (Throwable ignored) {
            return "development build";
        }
    }
}
