/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */
package io.github.rwml.asm;

import kim.der.asm.transformer.AsmProcessor;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.function.BiConsumer;

/** The JVM ignores transformer exceptions; invalid output prevents an unpatched class from loading. */
final class FailureAwareTransformer implements ClassFileTransformer {
    private final AsmProcessor processor = new AsmProcessor();
    private final BiConsumer<String, Throwable> failures;

    FailureAwareTransformer(BiConsumer<String, Throwable> failures) { this.failures = failures; }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                            ProtectionDomain domain, byte[] bytes) {
        if (name == null) { return null; }
        try {
            if (!processor.shouldTransform(name)) { return null; }
            return processor.transform(name, bytes, loader);
        } catch (Throwable failure) {
            try { failures.accept(name, failure); }
            catch (Throwable recoveryFailure) { failure.addSuppressed(recoveryFailure); }
            failure.printStackTrace(System.err);
            return new byte[]{0};
        }
    }
}
