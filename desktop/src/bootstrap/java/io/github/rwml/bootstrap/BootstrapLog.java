/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

/**
 * Logging for the bootstrap layer.
 *
 * <p>This class is the very first thing RWML runs, and it deliberately depends on nothing but the
 * Java 8 platform: it is compiled for Java 8 and is loaded on JVMs as old as that, long before the
 * RWML core (which needs a newer JVM) can even be resolved. No logging framework, no configuration,
 * no class loading beyond {@code java.base}.</p>
 *
 * <p>Output goes to the process console, which is the same place the game launcher already writes
 * to. Lines are prefixed with {@code [rwml]} so they are easy to spot - and easy to filter - in a
 * game log.</p>
 */
public final class BootstrapLog {

    private static final String PREFIX = "[rwml] ";

    private BootstrapLog() {
    }

    /** Writes a normal status line. */
    public static void info(String message) {
        System.out.println(PREFIX + message);
    }

    /** Writes a line that explains a degraded mode or a recoverable problem. */
    public static void warn(String message) {
        System.out.println(PREFIX + "warning: " + message);
    }

    /** Writes a fatal message to the error stream. */
    public static void error(String message) {
        System.err.println(PREFIX + "error: " + message);
    }

    /** Writes a fatal message plus its stack trace to the error stream. */
    public static void error(String message, Throwable cause) {
        System.err.println(PREFIX + "error: " + message);
        cause.printStackTrace(System.err);
    }

    /**
     * Writes a diagnostic line, unconditionally.
     *
     * <p>The bootstrap takes no parameters, so there is no verbosity switch to turn these lines on
     * with. They are a handful per launch, and they are what a bug report needs.</p>
     */
    public static void debug(String message) {
        System.out.println(PREFIX + message);
    }

    /** Writes a diagnostic stack trace to the error stream. */
    public static void debug(Throwable cause) {
        cause.printStackTrace(System.err);
    }

    /** @return the deepest cause of {@code throwable}, which is what actually explains a failure. */
    public static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /** @return a one line {@code Type: message} description of a throwable. */
    public static String describe(Throwable throwable) {
        Throwable cause = rootCause(throwable);
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
