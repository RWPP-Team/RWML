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
 * The naming contract shared by RWML's build and its runtime entry point.
 *
 * <p>RWML ships as a drop-in replacement for the game's {@code game-lib.jar}: the game's classes are
 * merged into RWML's jar and the original file is overwritten. The game launcher starts the JVM on
 * the jar's {@code Main-Class}, and that value is fixed to {@code com.corrodinggames.rts.java.Main},
 * so the replacement jar has to provide a class of exactly that name. RWML writes it in
 * {@code src/bootstrap/java}.</p>
 *
 * <p>The game's own entry point is renamed to {@code MainLauncher} by the Enigma mappings, a change
 * {@code remapFromEnigma} applies to the game jar before it is merged. Nothing rewrites bytecode at
 * package time, and this class is where the runtime learns which name to hand over to.</p>
 *
 * <p>This class is part of the <em>bootstrap</em> layer, which is compiled for Java 8 and must stay
 * free of any dependency on the RWML core. The target JVM version is therefore read from
 * {@value #BOOTSTRAP_RESOURCE}, a properties file that {@code packageGameJar} writes into the jar
 * from the single source of truth in {@code gradle.properties}; the constant below is the fallback
 * used when RWML runs unpackaged (tests, IDE, the demo harness).</p>
 *
 * <p>{@link #GAME_MAIN} deliberately stays in {@code com.corrodinggames.rts.java}: the game's entry
 * point is referenced by {@code e}, {@code i}, {@code j}, {@code u} and {@code b.a} in that same
 * package, including through package-private members. Moving it anywhere else would break those
 * accesses at link time.</p>
 */
public final class GameEntry {

    /** The class the game launcher starts from, and therefore the class RWML has to own. */
    public static final String MAIN = "com.corrodinggames.rts.java.Main";

    /**
     * The game's own entry point, as named by the Enigma mappings.
     *
     * <p>Kept as a constant rather than a setting: the name comes from the mappings, which are part
     * of the build, so there is nothing to configure at runtime.</p>
     */
    public static final String GAME_MAIN = "com.corrodinggames.rts.java.MainLauncher";

    /** The JVM feature version RWML wants to run on. Mirrors {@code rwmlTargetJavaVersion}. */
    public static final int DEFAULT_TARGET_JAVA_VERSION = 25;

    /** The file {@code packageGameJar} writes into the packaged jar. */
    public static final String BOOTSTRAP_RESOURCE = "META-INF/rwml-bootstrap.properties";

    private static final String KEY_TARGET_JAVA_VERSION = "targetJavaVersion";

    private static final Properties PACKAGE_PROPERTIES = loadPackageProperties();

    private GameEntry() {
    }

    /**
     * @return the JVM feature version RWML should run on: the value the packaged jar was built with,
     *         or {@link #DEFAULT_TARGET_JAVA_VERSION} when running unpackaged.
     */
    public static int targetJavaVersion() {
        String packaged = PACKAGE_PROPERTIES.getProperty(KEY_TARGET_JAVA_VERSION);
        if (packaged != null && !packaged.trim().isEmpty()) {
            try {
                return Integer.parseInt(packaged.trim());
            } catch (NumberFormatException ignored) {
                // Fall through to the compiled default.
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
            // Running unpackaged (or the resource is unreadable): the compiled defaults apply.
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                    // Nothing useful to do.
                }
            }
        }
        return properties;
    }
}
