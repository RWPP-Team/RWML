/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import io.github.rwml.android.mixin.MasterServerListLifecycleMixin;
import kim.der.asm.AsmRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.List;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class MasterServerLifecycleMixinTest {
    @TempDir Path temporary;
    @AfterEach void clear() { AsmRegistry.clear(); }
    @Test void cancellableHeadWorksWithoutGameOrMixinClassFileAndProducesDex() throws Exception {
        AsmRegistry.clear();
        String target = "com/corrodinggames/rts/gameFramework/network/MasterServerConnector";
        String mixin = MasterServerListLifecycleMixin.class.getName();
        ClassLoader app = getClass().getClassLoader();
        ClassLoader beforeMount = new ClassLoader(app) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(mixin)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        try (InputStream in = app.getResourceAsStream(name.replace('.', '/') + ".class")) {
                            byte[] bytes = JarResources.readFully(in); type = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException error) { throw new ClassNotFoundException(name, error); }
                    }
                    return type;
                }
                if (name.startsWith("com.corrodinggames.")) { throw new ClassNotFoundException(name); }
                return super.loadClass(name, resolve);
            }
            @Override public InputStream getResourceAsStream(String name) {
                return name.equals(mixin.replace('.', '/') + ".class") ? null : super.getResourceAsStream(name);
            }
        };
        OfflineMixinBridge bridge = new OfflineMixinBridge(beforeMount);
        bridge.register(Class.forName(mixin, false, beforeMount));
        Path game = Paths.get(System.getProperty("rwml.testGameArchive"));
        byte[] rewritten;
        try (JarFile archive = new JarFile(game.toFile());
             InputStream in = archive.getInputStream(archive.getJarEntry(target + ".class"));
             JarResources resources = new JarResources(List.of(game), beforeMount)) {
            rewritten = bridge.transform(target, JarResources.readFully(in), resources);
        }
        ClassNode node = new ClassNode(); new ClassReader(rewritten).accept(node, 0);
        MethodNode load = node.methods.stream().filter(method -> method.name.equals("startLoadRequest")).findFirst().orElseThrow();
        boolean routed = false, cancelChecked = false;
        for (AbstractInsnNode instruction : load.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals(mixin.replace('.', '/'))) { routed = true; }
                if (call.name.equals("isCancelled")) { cancelChecked = true; }
                if (call.owner.equals("java/lang/Thread")) { assertTrue(routed && cancelChecked); }
            }
        }
        assertTrue(routed); assertTrue(cancelChecked);
        Path patched = temporary.resolve("patched.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(patched))) {
            out.putNextEntry(new JarEntry(target + ".class")); out.write(rewritten); out.closeEntry();
        }
        List<Path> dex = Dexer.toDex(patched, temporary.resolve("dex"));
        try { assertFalse(dex.isEmpty()); }
        finally { for (Path file : dex) { Dexer.makeWritable(file); } }
    }
}
