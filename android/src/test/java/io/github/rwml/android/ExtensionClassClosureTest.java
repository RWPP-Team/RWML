package io.github.rwml.android;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtensionClassClosureTest {
    @TempDir Path root;
    @Test void followsCyclicSignaturesAcrossArchivesAndPreservesClasspathPrecedence() throws Exception {
        Path first = jar("first.jar", Map.of("plugin/Mixin", "Lplugin/Helper;", "plugin/Helper", "Lplugin/Mixin;"));
        Path second = jar("second.jar", Map.of("plugin/Helper", "Lplugin/Unreachable;", "plugin/Unreachable", "Ljava/lang/String;"));
        assertEquals(Set.of("plugin.Mixin", "plugin.Helper"),
            ExtensionClassClosure.localFirstClassNames(List.of(first, second), List.of("plugin.Mixin")));
        Files.move(first, root.resolve("released.jar"));
    }
    @Test void platformTypesNeverEnterTheRegistrationLoaderClosure() throws Exception {
        Path file = jar("shared.jar", Map.of("plugin/Mixin", "Landroidx/lifecycle/ViewModel;", "androidx/lifecycle/ViewModel", "Lplugin/Unreachable;"));
        assertEquals(Set.of("plugin.Mixin"),
            ExtensionClassClosure.localFirstClassNames(List.of(file), List.of("plugin.Mixin")));
    }
    private Path jar(String filename, Map<String, String> types) throws Exception {
        Path file = root.resolve(filename);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (var type : types.entrySet()) {
                ClassWriter writer = new ClassWriter(0);
                writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, type.getKey(), null, "java/lang/Object", null);
                writer.visitField(Opcodes.ACC_PUBLIC, "reference", type.getValue(), null, null).visitEnd();
                writer.visitEnd();
                out.putNextEntry(new JarEntry(type.getKey() + ".class")); out.write(writer.toByteArray()); out.closeEntry();
            }
        }
        return file;
    }
}
