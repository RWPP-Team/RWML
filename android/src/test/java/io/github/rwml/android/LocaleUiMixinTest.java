/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import io.github.rwml.game.LocaleUiMixin;
import kim.der.asm.AsmRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LocaleUiMixinTest {
    @TempDir Path temporary;
    @AfterEach void clear() { AsmRegistry.clear(); }

    @Test
    void languagePreferenceIsAppliedBeforeNativeReloadAndThePatchCanBeDexed() throws Exception {
        AsmRegistry.clear();
        String target = "com/corrodinggames/rts/gameFramework/localization/LocaleEngine";
        OfflineMixinBridge bridge = new OfflineMixinBridge(getClass().getClassLoader());
        bridge.register(LocaleUiMixin.class);
        Path game = Paths.get(System.getProperty("rwml.testGameArchive"));
        byte[] patched;
        try (JarFile jar = new JarFile(game.toFile());
             InputStream input = jar.getInputStream(jar.getJarEntry(target + ".class"));
             JarResources resources = new JarResources(List.of(game), getClass().getClassLoader())) {
            patched = bridge.transform(target, JarResources.readFully(input), resources);
        }
        ClassNode node = new ClassNode();
        new ClassReader(patched).accept(node, 0);
        MethodNode reload = node.methods.stream().filter(method -> method.name.equals("reloadBundle"))
            .findFirst().orElseThrow();
        MethodInsnNode firstCall = null;
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : reload.instructions) {
            if (instruction instanceof MethodInsnNode call) { firstCall = call; break; }
        }
        assertNotNull(firstCall);
        assertEquals(LocaleUiMixin.class.getName().replace('.', '/'), firstCall.owner);
        assertEquals("beforeReload", firstCall.name);
        assertEquals("()V", firstCall.desc);
        Path archive = temporary.resolve("locale.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new JarEntry(target + ".class"));
            output.write(patched);
            output.closeEntry();
        }
        List<Path> dex = Dexer.toDex(archive, temporary.resolve("dex"));
        try { assertFalse(dex.isEmpty()); }
        finally { for (Path file : dex) { Dexer.makeWritable(file); } }
    }
}
