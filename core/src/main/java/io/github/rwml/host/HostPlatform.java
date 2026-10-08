/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.host;

import io.github.rwml.api.MixinBridge;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.api.ExtensionException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The parts of RWML that differ between hosts.
 *
 * <p>{@link io.github.rwml.core.ExtensionManager} owns everything that is the same on every host:
 * discovery, descriptor reading, dependency ordering, instantiation and the enable/disable flag.
 * Platform decisions depend on where RWML runs, and each one is a method here, so the desktop and the
 * Android host implement one interface instead of keeping two copies of the loader.</p>
 */
public interface HostPlatform {

    /** @return the bytecode patching service this host offers. */
    MixinBridge mixins();

    /**
     * Describes the extensions the host carries inside itself.
     *
     * <p>These are loaded like any other extension - they are ordered, instantiated, may be disabled
     * by id and appear in the load report - but they have no archive, so their classes come from the
     * host's own class path. RWML ships one: the interface that shows the game, which is what makes
     * a fresh install able to start a game without downloading anything.</p>
     *
     * <p>A host that wants nothing of the kind returns an empty list, and with no extension
     * declaring a {@code ui-framework} the runtime stays headless.</p>
     *
     * @return the built-in descriptors, in the order they should load
     */
    List<ExtensionDescriptor> builtInExtensions();

    /**
     * Copies the archives the host carries inside itself into the extension directory, so that
     * discovery sees them like any other extension.
     *
     * <p>A desktop install has nothing to hand over; an Android app ships its extensions in the APK
     * and has to unpack them. Implementations compare content digests and only copy what changed, so
     * a repeated call is cheap.</p>
     *
     * @return one line per archive describing what was done, for the load report
     */
    List<String> stageArchives(Path extensionDirectory) throws IOException;

    /**
     * @return the class loader that owns one extension.
     *
     * <p>The desktop host gives every extension a loader of its own. The Android host cannot: a mixin
     * is compiled into a static call inside the class it patches, and that call resolves through the
     * loader that defines the patched class, so the mixin has to be reachable from there.</p>
     */
    ClassLoader createExtensionLoader(ExtensionDescriptor descriptor) throws IOException;

    /**
     * Reconciles the Java version an extension asks for with what this host can run.
     *
     * <p>Only a JVM host can act on the answer. Android runs whatever the platform ships, so its
     * implementation returns the descriptor unchanged.</p>
     */
    ExtensionDescriptor reconcileJavaRequirement(ExtensionDescriptor descriptor, ProgressListener progress)
        throws ExtensionException;
}
