/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */
package io.github.rwml.asm;

import kim.der.asm.AsmRegistry;
import kim.der.asm.api.annotation.AsmInject;
import kim.der.asm.api.annotation.AsmMixin;
import kim.der.asm.api.annotation.At;
import kim.der.asm.transformer.AsmTransformException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FailureAwareTransformerTest {
    @AfterEach void clear() { AsmRegistry.clear(); }

    @Test void brokenMixinNotifiesRecoveryAndCannotDefineAnUnpatchedClass() {
        AsmRegistry.clear();
        AsmRegistry.register(BrokenMixin.class);
        AtomicReference<Throwable> notified = new AtomicReference<>();
        FailureAwareTransformer transformer = new FailureAwareTransformer((name, failure) -> {
            assertEquals("fixture/Target", name);
            notified.set(failure);
        });
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/Target", null, "java/lang/Object", null);
        writer.visitEnd();
        byte[] original = writer.toByteArray();
        byte[] result = transformer.transform(getClass().getClassLoader(), "fixture/Target", null, null, original);
        assertTrue(notified.get() instanceof AsmTransformException);
        assertNotEquals(original.length, result.length);
        assertThrows(ClassFormatError.class, () -> new ClassLoader() {
            Class<?> define() { return defineClass("fixture.Target", result, 0, result.length); }
        }.define());
    }

    @Test void validMixinStillPatchesAClassAndRunsOnce() throws Exception {
        AsmRegistry.clear();
        GoodMixin.calls = 0;
        AsmRegistry.register(GoodMixin.class);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/GoodTarget", null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "run", "()V", null, null);
        method.visitCode(); method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd();
        writer.visitEnd();
        FailureAwareTransformer transformer = new FailureAwareTransformer((name, failure) -> fail(failure));
        byte[] bytes = transformer.transform(getClass().getClassLoader(), "fixture/GoodTarget", null, null,
            writer.toByteArray());
        Class<?> target = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass("fixture.GoodTarget", bytes, 0, bytes.length); }
        }.define();
        target.getMethod("run").invoke(null);
        assertEquals(1, GoodMixin.calls);
    }

    @AsmMixin("fixture/GoodTarget")
    public static final class GoodMixin {
        static int calls;
        @AsmInject(method = "run")
        public static void hook() { calls++; }
    }

    @Test void unrelatedClassesAreNotChangedOrReportedAsFailures() {
        AsmRegistry.clear();
        FailureAwareTransformer transformer = new FailureAwareTransformer((name, failure) -> fail(failure));
        assertNull(transformer.transform(getClass().getClassLoader(), "unrelated/Type", null, null, new byte[]{1}));
    }

    @AsmMixin("fixture/Target")
    public static final class BrokenMixin {
        @AsmInject(method = "run", at = @At(by = 1))
        public static void brokenHook() { }
    }
}
