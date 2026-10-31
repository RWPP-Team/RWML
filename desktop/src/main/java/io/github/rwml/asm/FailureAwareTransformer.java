/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */
package io.github.rwml.asm;

import kim.der.asm.transformer.AsmProcessor;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.function.BiConsumer;

/** JVM 会忽略转换器抛出的异常；无效输出会阻止未打补丁的类加载。 */
final class FailureAwareTransformer implements ClassFileTransformer {
    private final AsmProcessor processor = new AsmProcessor();
    private final BiConsumer<String, Throwable> failures;

    FailureAwareTransformer(BiConsumer<String, Throwable> failures) { this.failures = failures; }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                            ProtectionDomain domain, byte[] bytes) {
        if (name == null) { return null; }
        try {
            MixinSupport mixins = MixinSupport.instance();
            boolean hasMixins = processor.shouldTransform(name);
            boolean hasPatches = mixins.hasClassPatch(name);
            if (!hasMixins && !hasPatches) { return null; }
            byte[] transformed = hasMixins ? processor.transform(name, bytes, loader) : bytes;
            return hasPatches ? mixins.applyClassPatches(name, transformed, loader) : transformed;
        } catch (Throwable failure) {
            try { failures.accept(name, failure); }
            catch (Throwable recoveryFailure) { failure.addSuppressed(recoveryFailure); }
            failure.printStackTrace(System.err);
            return new byte[]{0};
        }
    }
}
