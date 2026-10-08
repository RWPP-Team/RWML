/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api;

/**
 * The running {@link RwmlRuntime}, and the way extension code reaches it.
 *
 * <p>A host installs its runtime here when the extension system comes up. An extension reads it
 * through {@link #get()}, typically from a mixin: the call is platform neutral, and it is the only
 * sanctioned route from injected game code back into the host.</p>
 *
 * <p>The accessor is a static method of a plain class on purpose. An extension travels to Android as
 * class files and is dexed there by the dx the APK carries, and that dx predates Java 8's static
 * interface methods: a static method declared on an interface would make the extension undexable.</p>
 */
public final class Rwml {

    private static volatile RwmlRuntime runtime;

    private Rwml() {
    }

    /** @return the runtime the host installed, or {@code null} when no host has installed one */
    public static RwmlRuntime get() {
        return runtime;
    }

    /**
     * Installs {@code runtime} as the one returned by {@link #get()}; {@code null} removes it.
     * A host calls this; an extension only reads.
     */
    public static void install(RwmlRuntime runtime) {
        Rwml.runtime = runtime;
    }
}
