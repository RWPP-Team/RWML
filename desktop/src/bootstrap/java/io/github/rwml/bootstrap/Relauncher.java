/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 在指定 JVM 中重新启动游戏。
 *
 * <p>这是受平台限制所需的处理：从 Java 9 起，如果 JVM 启动时没有设置 {@code -Djdk.attach.allowAttachSelf=true}，就会拒绝向自身附加
 * agent（错误为 {@code Can not attach to current VM}）。该属性会在应用代码运行前由启动器读取，因此在 {@code main} 中设置为时已晚
 *（已在 JDK 25 上验证）。RWML 无法控制游戏启动器，且启动器未传入 {@code -javaagent} 标志，因此要在游戏类加载前安装字节码转换器，
 * 唯一方法是接管启动流程：在指定 JVM 上以相同命令行重新启动，并添加所需标志。</p>
 *
 * <p>使用额外进程可以带来两项效果：</p>
 * <ul>
 *   <li>{@code -javaagent:<this jar>} 会让 {@code RwmlAgent.premain} 在 {@code main} <em>之前</em>运行，使
 *       {@code Instrumentation} 可用，并在游戏类加载时应用 mixin；无需向自身附加 agent、修改启动类路径，也无需游戏启动器提供任何标志；</li>
 *   <li>启动器已传入的 JVM 参数（{@code -Xmx}、{@code -D...} 和第三方 {@code -javaagent}）会继续传递，游戏仍使用预期配置。</li>
 * </ul>
 */
public final class Relauncher {

    /** 在子 JVM 中设置，防止重启失败后陷入无限循环。 */
    public static final String RELAUNCHED_PROPERTY = "rwml.relaunched";

    /** 转换器启动后由 {@code RwmlAgent.premain} 设置。 */
    public static final String AGENT_INSTALLED_PROPERTY = "rwml.agent.installed";

    private Relauncher() {
    }

    /**
     * @return 启动器当前运行所在的 JAR；未通过 JAR 运行时（例如 IDE 或测试类路径）返回 {@code null}，此时无法通过重启安装 agent
     */
    public static File ownJar() {
        try {
            CodeSource source = Relauncher.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            File location = new File(source.getLocation().toURI());
            return location.isFile() && location.getName().toLowerCase(Locale.ROOT).endsWith(".jar")
                ? location
                : null;
        } catch (Throwable t) {
            BootstrapLog.debug("Could not determine the bootstrap jar: " + BootstrapLog.describe(t));
            return null;
        }
    }

    /**
     * 为子 JVM 构建命令行。
     *
     * @param javaHome 要启动的 JVM
     * @param ownJar   RWML 的 JAR；已知时会添加为 {@code -javaagent}
     * @param addAgent 是否添加 {@code -javaagent} 标志
     * @param args     游戏自身参数，按原样转发
     */
    public static List<String> buildCommand(File javaHome, File ownJar, boolean addAgent, String[] args) {
        List<String> command = new ArrayList<>();
        File java = Archives.javaExecutable(javaHome);
        command.add(java != null
            ? java.getAbsolutePath()
            : new File(javaHome, "bin" + File.separator + "java").getAbsolutePath());

        for (String argument : jvmInputArguments()) {
            if (isForwardable(argument, ownJar)) {
                command.add(argument);
            } else {
                BootstrapLog.debug("Not forwarding JVM argument: " + argument);
            }
        }

        if (addAgent && ownJar != null) {
            command.add("-javaagent:" + ownJar.getAbsolutePath());
        }
        command.add("-D" + RELAUNCHED_PROPERTY + "=true");
        command.add("-cp");
        command.add(System.getProperty("java.class.path", ""));
        command.add(GameEntry.MAIN);
        for (String argument : args) {
            command.add(argument);
        }
        return command;
    }

    /**
     * 启动子 JVM，将控制台输出直接转发给它，并等待其退出。
     *
     * @return 子进程的退出码，由调用方转发
     */
    public static int run(List<String> command) throws Exception {
        BootstrapLog.info("Launching: " + quote(command));
        ProcessBuilder builder = new ProcessBuilder(command);
        // 控制台由游戏使用：游戏输出必须直接写入真实标准输出，不能经过启动器转发。
        builder.inheritIO();
        String workingDirectory = System.getProperty("user.dir");
        if (workingDirectory != null) {
            builder.directory(new File(workingDirectory));
        }
        final Process process = builder.start();
        // 当前 JVM 被结束时，也要结束子进程，避免遗留孤儿进程。
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (process.isAlive()) {
                    process.destroy();
                }
            }, "rwml-relay-shutdown"));
        } catch (IllegalStateException ignored) {
            // 进程已经在关闭，无需转发退出码。
        }
        return process.waitFor();
    }

    private static List<String> jvmInputArguments() {
        try {
            return ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (Throwable t) {
            // 所有受支持的 JVM 都包含 JMX，但不能让启动器因日志记录失败而退出。
            BootstrapLog.debug("Could not read the JVM input arguments: " + BootstrapLog.describe(t));
            return new ArrayList<>();
        }
    }

    /**
     * 判断当前进程的 JVM 参数是否应转发到子进程。
     *
     * <p>类路径由 RWML 指定；{@code -Xbootclasspath/a} 参数会悄然将游戏移入启动类加载器，使其加载到的类与扩展系统提供的副本不同。
     * 因此两者都会被移除。</p>
     *
     * <p>只移除 RWML <em>自身</em>的 {@code -javaagent} 参数（之后会显式重新添加）。启动器请求的第三方 agent 会继续转发，
     * 因为静默移除会改变游戏运行方式。</p>
     *
     * @param ownJar RWML 的 JAR；无法确定时为 {@code null}
     */
    static boolean isForwardable(String argument, File ownJar) {
        if (argument == null || argument.isEmpty()) {
            return false;
        }
        String lower = argument.toLowerCase(Locale.ROOT);
        if (lower.equals("-cp") || lower.equals("-classpath") || lower.equals("--class-path")) {
            return false;
        }
        if (lower.startsWith("-classpath=") || lower.startsWith("--class-path=")
            || lower.startsWith("-cp=")) {
            return false;
        }
        if (lower.startsWith("-djava.class.path=") || lower.startsWith("-djava.home=")
            || lower.startsWith("-djava.command=") || lower.startsWith("-dsun.java.command=")) {
            return false;
        }
        if (lower.startsWith("-xbootclasspath")) {
            return false;
        }
        if (lower.startsWith("-javaagent:")) {
            // buildCommand 会重新添加该参数；再次转发会导致重复安装转换器。
            return !isOwnAgent(argument, ownJar);
        }
        return !lower.startsWith("-d" + RELAUNCHED_PROPERTY + "=")
            && !lower.startsWith("-d" + AGENT_INSTALLED_PROPERTY + "=");
    }

    /** @return {@code -javaagent:} 参数是否指向 RWML 自身的 JAR */
    private static boolean isOwnAgent(String argument, File ownJar) {
        if (ownJar == null) {
            return false;
        }
        String path = argument.substring("-javaagent:".length());
        // 参数格式：-javaagent:<jar>=<options>
        int options = path.indexOf('=');
        if (options >= 0) {
            path = path.substring(0, options);
        }
        try {
            return new File(path).getCanonicalFile().equals(ownJar.getCanonicalFile());
        } catch (Exception e) {
            return path.equalsIgnoreCase(ownJar.getPath());
        }
    }

    /** 生成命令行，并为包含空格的部分添加引号。 */
    private static String quote(List<String> command) {
        StringBuilder builder = new StringBuilder();
        for (String part : command) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            if (part.indexOf(' ') >= 0) {
                builder.append('"').append(part).append('"');
            } else {
                builder.append(part);
            }
        }
        return builder.toString();
    }
}
