package io.github.rwml.bootstrap;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Stage 0 of RWML: the code that runs before the game does.
 *
 * <p>It has three jobs, in order:</p>
 * <ol>
 *   <li><b>Make the runtime right.</b> If the current JVM is older than the version RWML needs, or if
 *       the bytecode transformer is not installed, re-exec the game on a suitable JVM - downloading
 *       one if necessary - and relay its exit code. See {@link Relauncher} for why restarting is the
 *       only way to install the agent.</li>
 *   <li><b>Start the extension system.</b> Delegate to {@code io.github.rwml.launcher.RwmlLauncher},
 *       which discovers and loads the extensions and registers their mixins. The call is reflective
 *       on purpose: that class is modern bytecode, and this class has to be loadable on a Java 8
 *       JVM.</li>
 *   <li><b>Hand over to the game.</b> Invoke the game's relocated entry point. This happens whether
 *       or not the extension system started, so a broken extension can never stop the game from
 *       launching.</li>
 * </ol>
 */
public final class Bootstrap {

    /** Returned by {@link #ensureRuntime} when this process should continue starting up. */
    public static final int CONTINUE_IN_PROCESS = -1;

    /**
     * The RWML core entry point, loaded reflectively.
     *
     * <p>Not a compile-time dependency: this class is compiled for Java 8 and must not link against
     * Java 17 bytecode, or even resolving it would fail with {@code UnsupportedClassVersionError} on
     * an old JVM.</p>
     */
    private static final String CORE_ENTRY_POINT = "io.github.rwml.RwmlLauncher";

    /** The single method of {@link #CORE_ENTRY_POINT} the bootstrap calls. */
    private static final String CORE_METHOD = "prepare";

    private Bootstrap() {
    }

    /**
     * Ensures the JVM satisfies RWML's requirements, restarting the game on another one if needed.
     *
     * @return {@link #CONTINUE_IN_PROCESS} to carry on in this JVM, or the exit code to relay when
     *         the game has already run in a child JVM
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
            // Already the child of a relaunch: never restart again, whatever the state is, or a
            // failed hand-over would fork forever.
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
                javaHome = JavaRuntime.provision(required);
            } else {
                // Only the agent is missing, so the current JVM is fine - just restart on it with
                // -javaagent added.
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
     * Starts the extension system and then the game.
     *
     * @return the exit code to report: {@code 0} on a normal game exit
     */
    public static int startGame(String[] args) {
        startExtensionSystem(args);
        return handOverToGame(args);
    }

    /** Loads and starts the RWML core, reporting - but never raising - any failure. */
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

    /** Invokes the game's own entry point and relays how it ended. */
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

    /** @return the RWML version from the jar manifest, or {@code "development build"}. */
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
