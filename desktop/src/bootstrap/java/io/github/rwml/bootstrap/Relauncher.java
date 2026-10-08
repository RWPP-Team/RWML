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
 * Restarts the game on a chosen JVM.
 *
 * <p>This exists because of a hard constraint: from Java 9 onwards, a JVM refuses to attach an agent
 * to itself ({@code Can not attach to current VM}) unless it was started with
 * {@code -Djdk.attach.allowAttachSelf=true}, and that property is read by the launcher before any
 * application code runs - setting it from {@code main} is too late (verified on JDK 25). The game's
 * launcher is not under RWML's control and passes no {@code -javaagent} flag, so the only way to get
 * the bytecode transformer installed before the game's classes load is to become the launcher:
 * re-exec the same command line on the chosen JVM and add the flags it needs.</p>
 *
 * <p>Two consequences make this worth the extra process:</p>
 * <ul>
 *   <li>{@code -javaagent:<this jar>} makes {@code RwmlAgent.premain} run <em>before</em>
 *       {@code main}, so {@code Instrumentation} is available and mixins are applied to game classes
 *       as they load - no self-attach, no bootstrap-classpath tricks, no flags required from the
 *       game's own launcher;</li>
 *   <li>the JVM arguments the launcher did pass ({@code -Xmx}, {@code -D...}, and any third-party
 *       {@code -javaagent}) are forwarded, so the game keeps the configuration it expects.</li>
 * </ul>
 */
public final class Relauncher {

    /** Set on the child JVM so a failed relaunch can never turn into an endless loop. */
    public static final String RELAUNCHED_PROPERTY = "rwml.relaunched";

    /** Set by {@code RwmlAgent.premain} once the transformer is live. */
    public static final String AGENT_INSTALLED_PROPERTY = "rwml.agent.installed";

    private Relauncher() {
    }

    /**
     * @return the jar the bootstrap is running from, or {@code null} when it is not running from a
     *         jar (an IDE or test class path), in which case the agent cannot be installed by
     *         relaunching.
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
     * Builds the command line for the child JVM.
     *
     * @param javaHome the JVM to start
     * @param ownJar   RWML's jar, added as a {@code -javaagent} when it is known
     * @param addAgent whether to add the {@code -javaagent} flag
     * @param args     the game's own arguments, forwarded verbatim
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
     * Starts the child JVM, wires the console straight through to it and waits for it to finish.
     *
     * @return the child's exit code, which the caller relays
     */
    public static int run(List<String> command) throws Exception {
        BootstrapLog.info("Launching: " + quote(command));
        ProcessBuilder builder = new ProcessBuilder(command);
        // The game owns the console: its output has to appear on the real stdout, not be piped
        // through the bootstrap.
        builder.inheritIO();
        String workingDirectory = System.getProperty("user.dir");
        if (workingDirectory != null) {
            builder.directory(new File(workingDirectory));
        }
        final Process process = builder.start();
        // If this JVM is killed, take the child down with it instead of leaving it orphaned.
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (process.isAlive()) {
                    process.destroy();
                }
            }, "rwml-relay-shutdown"));
        } catch (IllegalStateException ignored) {
            // Already shutting down; nothing to relay.
        }
        return process.waitFor();
    }

    private static List<String> jvmInputArguments() {
        try {
            return ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (Throwable t) {
            // JMX is present on every supported JVM, but the bootstrap must never die over logging.
            BootstrapLog.debug("Could not read the JVM input arguments: " + BootstrapLog.describe(t));
            return new ArrayList<>();
        }
    }

    /**
     * Decides whether a JVM argument from the current process should be replayed on the child.
     *
     * <p>The class path is supplied by RWML, and an {@code -Xbootclasspath/a} entry would silently
     * move the game onto the boot loader, where it would be a different copy of the classes than the
     * one the extension system hands out. Both are therefore dropped.</p>
     *
     * <p>Only RWML's <em>own</em> {@code -javaagent} entry is dropped (it is re-added explicitly):
     * a third-party agent the launcher asked for is forwarded, since silently losing it would change
     * how the game runs.</p>
     *
     * @param ownJar RWML's jar, or {@code null} when it could not be determined
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
            // Re-added by buildCommand; replaying it would install the transformer twice.
            return !isOwnAgent(argument, ownJar);
        }
        return !lower.startsWith("-d" + RELAUNCHED_PROPERTY + "=")
            && !lower.startsWith("-d" + AGENT_INSTALLED_PROPERTY + "=");
    }

    /** @return whether a {@code -javaagent:} argument points at RWML's own jar. */
    private static boolean isOwnAgent(String argument, File ownJar) {
        if (ownJar == null) {
            return false;
        }
        String path = argument.substring("-javaagent:".length());
        // -javaagent:<jar>=<options>
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

    /** Renders a command line, quoting the parts that contain spaces. */
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
