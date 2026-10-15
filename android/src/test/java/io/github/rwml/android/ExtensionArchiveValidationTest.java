/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionArchiveValidationTest {
    @TempDir Path temporary;

    @Test
    void generatedDexIsAcceptedButCorruptionAndMissingPrimaryDexAreRejected() throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "example/Entry", null, "java/lang/Object", null);
        writer.visitEnd();
        Path classes = archive("classes.jar", "example/Entry.class", writer.toByteArray());
        List<Path> files = Dexer.toDex(classes, temporary.resolve("dex"));
        try {
            byte[] dex = Files.readAllBytes(files.get(0));
            Path valid = archive("valid.jar", "classes.dex", dex);
            assertDoesNotThrow(() -> Dexer.validateCarriedDex(valid));
            Path secondaryOnly = archive("secondary.jar", "classes2.dex", dex);
            assertThrows(IOException.class, () -> Dexer.validateCarriedDex(secondaryOnly));
            dex[dex.length - 1] ^= 1;
            Path corrupt = archive("corrupt.jar", "classes.dex", dex);
            assertThrows(IOException.class, () -> Dexer.validateCarriedDex(corrupt));
            Path empty = archive("empty.jar", "classes.dex", new byte[]{1});
            assertThrows(IOException.class, () -> Dexer.validateCarriedDex(empty));
        } finally { for (Path file : files) { Dexer.makeWritable(file); } }
    }

    private Path archive(String file, String entry, byte[] bytes) throws IOException {
        Path target = temporary.resolve(file);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target))) {
            output.putNextEntry(new JarEntry(entry));
            output.write(bytes);
            output.closeEntry();
        }
        return target;
    }
}
