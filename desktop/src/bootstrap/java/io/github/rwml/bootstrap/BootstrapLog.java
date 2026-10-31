/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

/**
 * 启动层的日志记录。
 *
 * <p>这是 RWML 最先运行的类，特意只依赖 Java 8 平台：它按 Java 8 编译，可在 Java 8 及以上的 JVM 中加载，
 * 远早于需要较新 JVM 的 RWML 核心被解析之前。此处不依赖日志框架或配置，也不会加载 {@code java.base} 之外的类。</p>
 *
 * <p>日志输出到进程控制台，也就是游戏启动器原本使用的位置。每行前面都会加上 {@code [rwml]}，方便在游戏日志中识别和筛选。</p>
 */
public final class BootstrapLog {

    private static final String PREFIX = "[rwml] ";
    private static volatile java.util.function.Consumer<String> sink;

    /** 可选的运行环境配置窗口会同步显示诊断信息，但不改变其语言。 */
    static void sink(java.util.function.Consumer<String> listener) { sink = listener; }

    private BootstrapLog() {
    }

    /** 写入普通状态信息。 */
    public static void info(String message) {
        System.out.println(PREFIX + message);
        java.util.function.Consumer<String> listener = sink;
        if (listener != null) { listener.accept(message); }
    }

    /** 写入说明降级模式或可恢复问题的信息。 */
    public static void warn(String message) {
        System.out.println(PREFIX + "warning: " + message);
    }

    /** 将致命错误信息写入错误流。 */
    public static void error(String message) {
        System.err.println(PREFIX + "error: " + message);
    }

    /** 将致命错误信息及其堆栈跟踪写入错误流。 */
    public static void error(String message, Throwable cause) {
        System.err.println(PREFIX + "error: " + message);
        cause.printStackTrace(System.err);
    }

    /**
     * 无条件写入一条诊断信息。
     *
     * <p>启动器不接收参数，因此没有控制详细程度的开关。这些日志每次启动只会产生少量，但正是问题报告所需的信息。</p>
     */
    public static void debug(String message) {
        System.out.println(PREFIX + message);
    }

    /** 将诊断堆栈跟踪写入错误流。 */
    public static void debug(Throwable cause) {
        cause.printStackTrace(System.err);
    }

    /** @return {@code throwable} 最深层的原因，即实际解释故障的异常 */
    public static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /** @return throwable 的单行描述，格式为 {@code Type: message} */
    public static String describe(Throwable throwable) {
        Throwable cause = rootCause(throwable);
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
