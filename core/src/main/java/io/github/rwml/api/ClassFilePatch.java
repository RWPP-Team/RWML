/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api;

/**
 * A host-side class-file patch declared by an extension archive.
 *
 * <p>The host invokes patches after applying registered ASM-Framework mixins. Implementations
 * must only use the supplied class bytes and loader; they must not load the class currently being
 * transformed. Return the bytes to pass to the next patch. Returning {@code null} is invalid.
 * This interface deliberately has no ASM dependency so extensions can choose their own bytecode
 * tools and the core remains platform-neutral.</p>
 */
public interface ClassFilePatch {

    /**
     * Transforms one class file.
     *
     * @param internalName the class name in JVM internal form, for example {@code example/Foo}
     * @param classfileBuffer bytes produced by the preceding mixin/patch stage
     * @param loader loader associated with the class being transformed; may be {@code null} for
     *               bootstrap-defined classes
     * @return bytes to pass to the next patch; must not be {@code null}
     */
    byte[] transform(String internalName, byte[] classfileBuffer, ClassLoader loader);
}
