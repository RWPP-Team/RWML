package com.corrodinggames.rts.java;

import io.github.rwml.bootstrap.Bootstrap;

/**
 * The game's entry point, taken over by RWML.
 *
 * <p>The game launcher starts this jar through its {@code Main-Class} manifest attribute, which is
 * fixed to this exact class name, so RWML has to provide it and move the game's own entry point
 * aside (see {@code io.github.rwml.bootstrap.GameEntry}).</p>
 *
 * <p>This class is intentionally tiny and deliberately compiled for <b>Java 8</b>, because it is the
 * one class that must load on whatever JVM the game's launcher happens to use. It contains no logic
 * of its own:</p>
 *
 * <ol>
 *   <li>{@link Bootstrap#ensureRuntime} makes sure the JVM is one RWML can run on - restarting the
 *       game on a downloaded Java 25 with the RWML agent installed when it is not - and returns the
 *       child's exit code when it did. RWML needs Java 25 for its own bytecode and the agent flag for
 *       its bytecode transformer, and neither can be arranged from inside a JVM that has already
 *       started.</li>
 *   <li>{@link Bootstrap#startGame} then loads the extensions and invokes the game's relocated
 *       entry point.</li>
 * </ol>
 *
 * <p>Everything here degrades gracefully: if no suitable JVM can be obtained, or the extension
 * system fails, the game is still started, just without extensions.</p>
 */
public class Main {

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = Bootstrap.ensureRuntime(args);
        } catch (Throwable t) {
            // The bootstrap must never be the reason the game refuses to start.
            BootstrapLogHolder.error(t);
            exitCode = Bootstrap.CONTINUE_IN_PROCESS;
        }

        if (exitCode != Bootstrap.CONTINUE_IN_PROCESS) {
            // The game already ran in the relaunched JVM; relay how it ended.
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
     * Keeps the reference to the bootstrap logger out of {@link #main}.
     *
     * <p>Referenced through a tiny holder so that a failure inside the logger itself cannot mask the
     * original problem.</p>
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
