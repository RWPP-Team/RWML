/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package com.corrodinggames.rts.java;

import io.github.rwml.bootstrap.Bootstrap;

/**
 * 由 RWML 接管的游戏入口。
 *
 * <p>游戏启动器通过清单中的 {@code Main-Class} 属性启动此 JAR，该属性固定为此类名，因此 RWML 必须提供此类，
 * 并将游戏自身的入口移到其他位置（参见 {@code io.github.rwml.bootstrap.GameEntry}）。</p>
 *
 * <p>此类有意保持精简，并专门按 <b>Java 8</b> 编译，因为无论游戏启动器使用哪个 JVM，此类都必须能够加载。它本身不包含业务逻辑：</p>
 *
 * <ol>
 *   <li>{@link Bootstrap#ensureRuntime} 确保当前 JVM 满足 RWML 的运行要求；不满足时，会下载 Java 25、安装 RWML agent，
 *       然后重启游戏；如果已在子进程中运行，则返回子进程的退出码。RWML 自身字节码需要 Java 25，字节码转换器也需要 agent 标志，
 *       这些都无法在已启动的 JVM 内配置。</li>
 *   <li>随后 {@link Bootstrap#startGame} 加载扩展，并调用迁移后的游戏入口。</li>
 * </ol>
 *
 * <p>此处所有步骤都允许降级：如果无法获取合适的 JVM，或扩展系统启动失败，仍会启动游戏，只是不加载扩展。</p>
 */
public class Main {

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = Bootstrap.ensureRuntime(args);
        } catch (Throwable t) {
            // 不能让启动器本身成为游戏无法启动的原因。
            BootstrapLogHolder.error(t);
            exitCode = Bootstrap.CONTINUE_IN_PROCESS;
        }

        if (exitCode != Bootstrap.CONTINUE_IN_PROCESS) {
            // 游戏已在重启后的 JVM 中运行；转发其退出码。
            if (exitCode != 0) {
                System.exit(exitCode);
            }
            return;
        }

        int gameExitCode = Bootstrap.startGame(args);
        if (gameExitCode != 0) {
            System.exit(gameExitCode);
        }
    }

    /**
     * 将启动器日志记录器的引用移出 {@link #main}。
     *
     * <p>通过简单的持有类获取日志记录器，避免记录器自身失败时遮蔽原始问题。</p>
     */
    private static final class BootstrapLogHolder {
        private static void error(Throwable t) {
            try {
                io.github.rwml.bootstrap.BootstrapLog.error("RWML bootstrap failed.", t);
            } catch (Throwable ignored) {
                t.printStackTrace();
            }
        }
    }
}
