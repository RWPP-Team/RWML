/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml;

import io.github.rwml.api.ExtensionException;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionDescriptorReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionDescriptorReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsEveryFieldFromThePropertiesDescriptor() throws Exception {
        Path jar = tempDir.resolve("described.jar");
        String properties = """
            mainClass=com.acme.Sample
            id=com.acme.sample
            name=Sample
            version=3.4.5
            description=A sample extension
            authors=Ada Lovelace, Alan Turing
            license=Apache-2.0
            licenseUrl=https://example.com/license
            icon=/icon.png
            loadBefore=com.acme.next
            homepage=https://example.com
            tags=tools, demo
            apiVersion=1.2
            javaVersion=17
            dependsOn=com.acme.core
            loadAfter=com.acme.base
            ui-framework=com.acme.SampleUi
            """;
        writeJar(jar, "META-INF/extension.properties", properties.getBytes(StandardCharsets.UTF_8));

        ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(jar);

        assertEquals("com.acme.sample", descriptor.id());
        assertEquals("Sample", descriptor.name());
        assertEquals("3.4.5", descriptor.version());
        assertEquals("A sample extension", descriptor.description());
        assertEquals(List.of("Ada Lovelace", "Alan Turing"), descriptor.authors());
        assertEquals("Apache-2.0", descriptor.license());
        assertEquals("https://example.com", descriptor.homepage());
        assertEquals("https://example.com/license", descriptor.licenseUrl());
        assertEquals("/icon.png", descriptor.icon());
        assertEquals(List.of("com.acme.next"), descriptor.loadBefore());
        assertEquals(List.of("tools", "demo"), descriptor.tags());
        assertEquals("1.2", descriptor.apiVersion());
        assertEquals(17, descriptor.requiredJavaVersion());
        assertEquals("com.acme.Sample", descriptor.mainClass());
        assertEquals("com.acme.SampleUi", descriptor.uiFramework());
        assertEquals(List.of("com.acme.core"), descriptor.dependsOn());
        assertEquals(List.of("com.acme.base"), descriptor.loadAfter());
        assertTrue(descriptor.sourceSize() > 0);
        assertEquals(64, descriptor.sourceSha256().length());
    }

    @Test
    void defaultsToNoInterface() throws Exception {
        Path jar = tempDir.resolve("no-ui.jar");
        writeJar(jar, "META-INF/extension.properties",
            "id=com.acme.quiet\nname=Quiet\nmainClass=com.acme.Quiet\n"
                .getBytes(StandardCharsets.UTF_8));

        // An extension that says nothing about an interface declares none, which is the headless
        // default rather than an empty class name to be looked up.
        assertEquals("", ExtensionDescriptorReader.read(jar).uiFramework());
    }

    @Test
    void rejectsAnArchiveWithoutAnyDescriptor() throws Exception {
        Path jar = tempDir.resolve("empty.jar");
        writeJar(jar, "README.txt", "nothing to see".getBytes(StandardCharsets.UTF_8));

        ExtensionException error = assertThrows(ExtensionException.class,
            () -> ExtensionDescriptorReader.read(jar));
        assertNotNull(error.getMessage());
    }

    @Test
    void readsUtf8WithoutInspectingEntryPointBytecode() throws Exception {
        Path jar = tempDir.resolve("future.jar");
        writeJar(jar, "META-INF/extension.properties", ("id=isolated\nname=独立扩展\n"
            + "mainClass=isolated.Entry\nauthors=Ada Lovelace, 林\njavaVersion=29\n")
            .getBytes(StandardCharsets.UTF_8), "isolated/Entry.class", new byte[]{1, 2, 3});
        ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(jar);
        assertEquals("独立扩展", descriptor.name());
        assertEquals(List.of("Ada Lovelace", "林"), descriptor.authors());
        assertEquals(29, descriptor.requiredJavaVersion());
    }

    @Test
    void rejectsLegacyAnnotationOnlyArchivesAndDoesNotMergeAnnotationFields() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve("legacy"));
        Path java = directory.resolve("Entry.java");
        Files.writeString(java, """
            package legacy;
            import io.github.rwml.api.ExtensionInfo;
            @ExtensionInfo(id="legacy", name="Legacy name", loadBefore={"other"})
            public class Entry { }
            """);
        Path annotation = directory.resolve("ExtensionInfo.java");
        Files.writeString(annotation, """
            package io.github.rwml.api;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            public @interface ExtensionInfo {
                String id(); String name(); String[] loadBefore() default {};
            }
            """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "8", "-d", directory.toString(), annotation.toString(), java.toString()));
        byte[] entry = Files.readAllBytes(directory.resolve("legacy/Entry.class"));
        Path jar = tempDir.resolve("annotation.jar");
        writeJar(jar, "legacy/Entry.class", entry);
        assertThrows(ExtensionException.class, () -> ExtensionDescriptorReader.read(jar));
        writeJar(jar, "META-INF/extension.properties", "id=properties\nmainClass=legacy.Entry\n"
            .getBytes(StandardCharsets.UTF_8), "legacy/Entry.class", entry);
        ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(jar);
        assertEquals("properties", descriptor.name());
        assertTrue(descriptor.loadBefore().isEmpty());
    }

    @Test
    void requiresPropertiesToNameBothIdAndEntryPoint() throws Exception {
        Path jar = tempDir.resolve("incomplete.jar");
        for (String properties : List.of("id=missing-main\n", "mainClass=missing.Id\n")) {
            writeJar(jar, "META-INF/extension.properties", properties.getBytes(StandardCharsets.UTF_8));
            assertThrows(ExtensionException.class, () -> ExtensionDescriptorReader.read(jar));
        }
    }

    @Test
    void defaultsArePropertiesDefaultsAndSingleAuthorPreservesSpaces() throws Exception {
        Path jar = tempDir.resolve("defaults.jar");
        writeJar(jar, "META-INF/extension.properties", ("id=defaults\nmainClass=sample.Entry\n"
            + "author=Ada Lovelace\njavaVersion=invalid\n").getBytes(StandardCharsets.UTF_8));
        ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(jar);
        assertEquals("defaults", descriptor.name());
        assertEquals("1.0.0", descriptor.version());
        assertEquals(List.of("Ada Lovelace"), descriptor.authors());
        assertEquals(8, descriptor.requiredJavaVersion());
    }

    // ------------------------------------------------------------------

    private static void writeJar(Path target, Object... nameAndBytes) throws IOException {
        try (OutputStream out = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(out)) {
            for (int i = 0; i < nameAndBytes.length; i += 2) {
                jar.putNextEntry(new JarEntry((String) nameAndBytes[i]));
                jar.write((byte[]) nameAndBytes[i + 1]);
                jar.closeEntry();
            }
        }
    }
}
