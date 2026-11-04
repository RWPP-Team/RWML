package io.github.rwml.android;

import io.github.rwml.android.mixin.DirectStorageMixins;
import kim.der.asm.AsmRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectStorageMixinTest {
    @TempDir Path temporary;
    @AfterEach void clear() { AsmRegistry.clear(); }

    @Test void allFilesystemHooksMatchTheActualGameAndProduceDex() throws Exception {
        AsmRegistry.clear();
        Path game = Paths.get(System.getProperty("rwml.testGameArchive"));
        List<String> targets = Arrays.asList(
            "com/corrodinggames/rts/gameFramework/files/FileAccessConfig",
            "com/corrodinggames/rts/gameFramework/files/FileLoader",
            "com/corrodinggames/rts/gameFramework/SettingsEngine",
            "com/corrodinggames/rts/gameFramework/utility/FileReaderManager",
            "com/corrodinggames/rts/appFramework/AppContext");
        Path patched = temporary.resolve("storage.jar");
        try (JarResources resources = new JarResources(Collections.singletonList(game), getClass().getClassLoader());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(patched))) {
            OfflineMixinBridge bridge = new OfflineMixinBridge(getClass().getClassLoader());
            bridge.resetForOfflineRegistration(resources);
            for (Class<?> mixin : Arrays.asList(DirectStorageMixins.Access.class, DirectStorageMixins.Loader.class,
                    DirectStorageMixins.Settings.class, DirectStorageMixins.Readers.class, DirectStorageMixins.Permissions.class)) {
                bridge.register(mixin);
            }
            for (String target : targets) {
                byte[] original;
                try (JarFile jar = new JarFile(game.toFile())) {
                    original = JarResources.readFully(jar.getInputStream(jar.getJarEntry(target + ".class")));
                }
                byte[] rewritten = bridge.transform(target, original, resources);
                assertFalse(Arrays.equals(original, rewritten), target);
                ClassNode node = new ClassNode(); new ClassReader(rewritten).accept(node, 0);
                if (target.endsWith("FileAccessConfig")) {
                    assertTrue(node.interfaces.contains("io/github/rwml/android/mixin/DirectStorageMixins$AccessConfig"));
                    assertEquals(3, node.methods.stream().filter(method -> method.name.startsWith("rwml")).count());
                    Class<?> configType = new ClassLoader(getClass().getClassLoader()) {
                        Class<?> define() { return defineClass(null, rewritten, 0, rewritten.length); }
                    }.define();
                    Object config = configType.getConstructor().newInstance();
                    configType.getField("useSafExternalAccess").setBoolean(config, true);
                    configType.getField("useOverriddenExternalPath").setBoolean(config, true);
                    assertSame(config, DirectStorageMixins.Loader.direct(config));
                    assertTrue(configType.getField("useDirectExternalAccess").getBoolean(config));
                    assertFalse(configType.getField("useSafExternalAccess").getBoolean(config));
                    assertFalse(configType.getField("useOverriddenExternalPath").getBoolean(config));
                }
                out.putNextEntry(new JarEntry(target + ".class")); out.write(rewritten); out.closeEntry();
            }
        }
        List<Path> dex = Dexer.toDex(patched, temporary.resolve("dex"));
        try { assertFalse(dex.isEmpty()); }
        finally { for (Path path : dex) Dexer.makeWritable(path); }
    }
}
