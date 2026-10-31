/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */
package io.github.rwml.android;

import kim.der.asm.AsmRegistry;
import kim.der.asm.api.annotation.AsmInject;
import kim.der.asm.api.annotation.AsmMixin;
import kim.der.asm.api.annotation.At;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

import fixture.extension.FactoryAccess;
import fixture.extension.FactoryAccessorFixture;
import fixture.game.FactoryBuildQueue;

class OfflineMixinRecoveryTest {
    @TempDir Path root;
    @AfterEach void clear() { AsmRegistry.clear(); }

    @Test void offlineTransformFailureIdentifiesOnlyItsOwningArchive() throws Exception {
        AsmRegistry.clear();
        Path archive = root.resolve("broken.jar");
        writeArchive(archive, BrokenMixin.class);
        OfflineMixinBridge bridge = new OfflineMixinBridge(getClass().getClassLoader());
        bridge.registerArchive(archive);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/Target", null, "java/lang/Object", null);
        writer.visitEnd();
        Throwable failure = assertThrows(RuntimeException.class, () -> bridge.transform("fixture/Target",
            writer.toByteArray(), getClass().getClassLoader()));
        assertEquals(archive.toAbsolutePath().normalize(), bridge.failedArchive(failure));
        assertNull(bridge.failedArchive(new IllegalStateException("A game failure unrelated to mixins")));
    }

    @Test void offlineRegistrationResetDropsParentLoadedMixinAndArchiveCache() throws Exception {
        AsmRegistry.clear();
        Path archive = root.resolve("broken.jar");
        writeArchive(archive, BrokenMixin.class);
        OfflineMixinBridge bridge = new OfflineMixinBridge(getClass().getClassLoader());
        bridge.registerArchive(archive);

        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/Target", null, "java/lang/Object", null);
        writer.visitEnd();
        byte[] target = writer.toByteArray();
        assertThrows(RuntimeException.class, () -> bridge.transform("fixture/Target", target,
            getClass().getClassLoader()));

        ClassLoader rejectingLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(BrokenMixin.class.getName())) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        bridge.resetForOfflineRegistration(rejectingLoader);

        assertArrayEquals(target, bridge.transform("fixture/Target", target,
            getClass().getClassLoader()));
        assertThrows(io.github.rwml.api.ExtensionException.class, () -> bridge.registerArchive(archive));
    }

    @Test void localFirstClosureIncludesExtensionInterfacesButLeavesGameTypesForStandIns()
        throws Exception {
        Path archive = root.resolve("accessor.jar");
        writeArchive(archive, FactoryAccessorFixture.class, FactoryAccess.class);

        Set<String> localFirst = ExtensionClassClosure.localFirstClassNames(
            java.util.Collections.singletonList(archive),
            OfflineMixinBridge.mixinClassNames(archive));

        assertTrue(localFirst.contains(FactoryAccessorFixture.class.getName()));
        assertTrue(localFirst.contains(FactoryAccess.class.getName()));
        assertFalse(localFirst.contains(FactoryBuildQueue.class.getName()));
        assertFalse(localFirst.contains(AsmMixin.class.getName()));
    }

    private void writeArchive(Path archive, Class<?>... types) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            for (Class<?> type : types) {
                String name = type.getName().replace('.', '/') + ".class";
                try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
                    assertNotNull(input, name);
                    output.putNextEntry(new JarEntry(name));
                    byte[] bytes = new byte[8192];
                    int count;
                    while ((count = input.read(bytes)) != -1) { output.write(bytes, 0, count); }
                    output.closeEntry();
                }
            }
        }
    }

    @AsmMixin("fixture/Target")
    public static final class BrokenMixin {
        @AsmInject(method = "run", at = @At(by = 1))
        public static void brokenHook() { }
    }

}
