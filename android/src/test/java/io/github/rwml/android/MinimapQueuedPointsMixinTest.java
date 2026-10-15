/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import kim.der.asm.AsmRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MinimapQueuedPointsMixinTest {
    private static final String TARGET =
        "com/corrodinggames/rts/gameFramework/gui/MinimapHandlerQueuedPointsDraw";
    private static final String MIXIN = TARGET + "Mixin";
    private final Path game = Paths.get(System.getProperty("rwml.testGameArchive"));

    @TempDir
    Path temporary;

    @AfterEach
    void clearRegistry() {
        AsmRegistry.clear();
    }

    @Test
    void rewritesAndDexesWithoutLoadingGameClassesOrReadingMixinBytecode() throws Exception {
        OfflineMixinBridge bridge = bridgeWithoutGame();
        byte[] rewritten;
        try (JarResources resources = new JarResources(Collections.singletonList(game),
                getClass().getClassLoader())) {
            rewritten = bridge.transform(TARGET, targetBytes(), resources);
        }

        MethodNode execute = execute(read(rewritten));
        int redirects = 0;
        int recyclingCalls = 0;
        for (AbstractInsnNode instruction : execute.instructions) {
            if (instruction instanceof FieldInsnNode) {
                assertNotEquals("drawAsPoints", ((FieldInsnNode) instruction).name);
            }
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                assertNotEquals("drawLines", call.name);
                if (MIXIN.equals(call.owner)) {
                    redirects++;
                    assertTrue(call.name.equals("useBatchRenderer") || call.name.equals("drawSquares"));
                    assertFalse(call.desc.contains("Lcom/corrodinggames/"));
                }
                if (call.name.equals("recycleQueuedPointsDraw")) {
                    recyclingCalls++;
                }
            }
        }
        assertEquals(2, redirects);
        assertEquals(1, recyclingCalls);

        Path patched = temporary.resolve("patched.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(patched))) {
            out.putNextEntry(new JarEntry(TARGET + ".class"));
            out.write(rewritten);
            out.closeEntry();
        }
        List<Path> dex = Dexer.toDex(patched, temporary.resolve("dex"));
        try {
            assertFalse(dex.isEmpty());
            for (Path file : dex) {
                assertTrue(Files.size(file) > 0);
            }
        } finally {
            for (Path file : dex) {
                Dexer.makeWritable(file);
            }
        }
    }

    @Test
    void failsIfTheExpectedBatchCallNoLongerMatches() throws Exception {
        OfflineMixinBridge bridge = bridgeWithoutGame();
        ClassNode target = read(targetBytes());
        for (AbstractInsnNode instruction : execute(target).instructions) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.name.equals("drawLines")) {
                    call.name = "changedDrawLines";
                }
            }
        }
        ClassWriter writer = new ClassWriter(0);
        target.accept(writer);
        try (JarResources resources = new JarResources(Collections.singletonList(game),
                getClass().getClassLoader())) {
            assertThrows(RuntimeException.class,
                () -> bridge.transform(TARGET, writer.toByteArray(), resources));
        }
    }

    private OfflineMixinBridge bridgeWithoutGame() throws Exception {
        AsmRegistry.clear();
        ClassLoader app = getClass().getClassLoader();
        ClassLoader beforeMount = new ClassLoader(app) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(MIXIN.replace('/', '.'))) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        try (InputStream in = app.getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (in == null) {
                                throw new ClassNotFoundException(name);
                            }
                            byte[] bytes = JarResources.readFully(in);
                            type = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException e) {
                            throw new ClassNotFoundException(name, e);
                        }
                    }
                    if (resolve) {
                        resolveClass(type);
                    }
                    return type;
                }
                if (name.startsWith("com.corrodinggames.")) {
                    throw new ClassNotFoundException("Game dex is not mounted: " + name);
                }
                return super.loadClass(name, resolve);
            }

            @Override
            public InputStream getResourceAsStream(String name) {
                // APKs carry dex, not the JVM bytecode an overwrite would need.
                return name.startsWith(MIXIN) ? null : super.getResourceAsStream(name);
            }
        };
        assertThrows(ClassNotFoundException.class, () -> beforeMount.loadClass(
            "com.corrodinggames.rts.gameFramework.render.GraphicsEngine"));
        Class<?> mixin = Class.forName(MIXIN.replace('/', '.'), false, beforeMount);
        for (Method method : mixin.getDeclaredMethods()) {
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(parameter.getName().startsWith("com.corrodinggames."));
            }
        }
        assertNull(beforeMount.getResourceAsStream(MIXIN + ".class"));
        OfflineMixinBridge bridge = new OfflineMixinBridge(beforeMount);
        bridge.register(mixin);
        return bridge;
    }

    private byte[] targetBytes() throws Exception {
        try (JarFile jar = new JarFile(game.toFile());
             InputStream in = jar.getInputStream(jar.getJarEntry(TARGET + ".class"))) {
            return JarResources.readFully(in);
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode execute(ClassNode target) {
        return target.methods.stream().filter(method -> method.name.equals("execute"))
            .findFirst().orElseThrow(AssertionError::new);
    }
}
