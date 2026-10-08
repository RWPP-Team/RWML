/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api;

import java.nio.file.Path;

/**
 * The bytecode patching service an extension reaches through {@link ExtensionContext#mixin()}.
 *
 * <p>Both hosts implement this interface, and which of its methods do anything depends on the host.
 * The desktop host installs a live transformer, so a mixin registered at any point takes effect for
 * every class that is not yet loaded. The Android host has no class-file transformer, so it applies
 * the mixins before the process starts using the patched classes; there, only {@link #transform} is
 * useful at runtime, and it operates on buffers the caller supplies.</p>
 */
public interface MixinBridge {

    /**
     * Makes the registered mixins take effect. Idempotent, and safe to call before any mixin is
     * registered.
     */
    void install();

    /**
     * Registers every mixin declared by an extension archive.
     *
     * @throws ExtensionException when one of them cannot be registered. A caller that went on would
     *     be running an extension whose patches are not in, which is worse than not loading it.
     */
    void registerArchive(Path archive) throws ExtensionException;

    /**
     * Registers a single mixin class.
     *
     * @throws ExtensionException when it cannot be registered
     */
    void register(Class<?> mixin) throws ExtensionException;

    /**
     * Applies every registered mixin to one class buffer.
     *
     * @param internalName the class name in internal form, for example {@code com/example/Foo}
     * @return the rewritten buffer, or the input unchanged when no mixin matched
     */
    byte[] transform(String internalName, byte[] classfileBuffer, ClassLoader loader);
}
