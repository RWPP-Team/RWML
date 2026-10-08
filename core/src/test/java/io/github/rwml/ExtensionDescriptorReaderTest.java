/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml;

import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.ExtensionInfo;
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
    void fallsBackToTheAnnotatedEntryPointClass() throws Exception {
        Path jar = tempDir.resolve("annotated.jar");
        Path compiled = Path.of(ExtensionDescriptorReaderTest.class.getResource(
            "ExtensionDescriptorReaderTest$Fixture.class").toURI());
        writeJar(jar, "io/github/rwml/ExtensionDescriptorReaderTest$Fixture.class",
            Files.readAllBytes(compiled));

        ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(jar);

        // There is no META-INF/extension.properties at all: everything comes from @ExtensionInfo.
        assertEquals("fixture-extension", descriptor.id());
        assertEquals("Fixture Extension", descriptor.name());
        assertEquals("2.1.0", descriptor.version());
        assertEquals("io.github.rwml.ExtensionDescriptorReaderTest$Fixture", descriptor.mainClass());
        assertEquals("fixture.Ui", descriptor.uiFramework());
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
    void readsAnnotationsWithoutResolvingDependenciesOrLoadingFutureBytecode() throws Exception {
        Path classes = compile("""
            package isolated;
            import io.github.rwml.api.ExtensionInfo;
            class MissingDependency { }
            class Decoy {
                static final String TEXT = "Lio/github/rwml/api/ExtensionInfo;";
            }
            @ExtensionInfo(id="isolated", name="独立扩展", javaVersion=29,
                authors={"Ada Lovelace", "林"}, tags={"ui", "tools"}, dependsOn={"base"})
            public class Entry extends MissingDependency {
                static final long LONG_VALUE = 42L;
                static final double DOUBLE_VALUE = 1.25;
            }
            """);
        byte[] entry = Files.readAllBytes(classes.resolve("isolated/Entry.class"));
        entry[6] = 3;
        entry[7] = (byte) 232; // class file version 1000: no current JVM can define this class
        Path jar = tempDir.resolve("future.jar");
        writeJar(jar, "isolated/Decoy.class", Files.readAllBytes(classes.resolve("isolated/Decoy.class")),
            "isolated/Entry.class", entry); // superclass intentionally absent

        ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(jar);

        assertEquals("isolated", descriptor.id());
        assertEquals("独立扩展", descriptor.name());
        assertEquals(29, descriptor.requiredJavaVersion());
        assertEquals(List.of("Ada Lovelace", "林"), descriptor.authors());
        assertEquals(List.of("ui", "tools"), descriptor.tags());
        assertEquals(List.of("base"), descriptor.dependsOn());
        assertEquals("1.0.0", descriptor.version());
        assertEquals("isolated.Entry", descriptor.mainClass());
    }

    @Test
    void propertiesOverrideInheritedAnnotationMetadataWithinTheArchive() throws Exception {
        Path classes = compile("""
            package isolated;
            import io.github.rwml.api.ExtensionInfo;
            @ExtensionInfo(id="parent", name="Parent", loadBefore={"other"})
            class Parent { }
            public class Entry extends Parent { }
            """);
        Path jar = tempDir.resolve("inherited.jar");
        writeJar(jar,
            "META-INF/extension.properties", "id=override\nmainClass=isolated.Entry\n"
                .getBytes(StandardCharsets.UTF_8),
            "isolated/Entry.class", Files.readAllBytes(classes.resolve("isolated/Entry.class")),
            "isolated/Parent.class", Files.readAllBytes(classes.resolve("isolated/Parent.class")));

        ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(jar);

        assertEquals("override", descriptor.id());
        assertEquals("Parent", descriptor.name());
        assertEquals(List.of("other"), descriptor.loadBefore());
    }

    @Test
    void honorsTheEntryPointNamedByTheAnnotation() throws Exception {
        Path classes = compile("""
            package isolated;
            import io.github.rwml.api.ExtensionInfo;
            @ExtensionInfo(id="redirect", name="Redirect", mainClass="isolated.ActualEntry")
            public class Entry { }
            class ActualEntry { }
            """);
        Path jar = tempDir.resolve("redirect.jar");
        writeJar(jar, "isolated/Entry.class", Files.readAllBytes(classes.resolve("isolated/Entry.class")));
        ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(jar);
        assertEquals("redirect", descriptor.id());
        assertEquals("isolated.ActualEntry", descriptor.mainClass());
    }

    private Path compile(String source) throws Exception {
        Path directory = Files.createTempDirectory(tempDir, "compiled-");
        Path java = directory.resolve("Entry.java");
        Files.writeString(java, source);
        Path api = Path.of(ExtensionInfo.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "8", "-encoding", "UTF-8", "-classpath", api.toString(),
            "-d", directory.toString(), java.toString());
        assertEquals(0, status, "fixture compilation failed");
        return directory;
    }

    /** A real class carrying {@link ExtensionInfo}, packaged into the jar by the annotation test. */
    @ExtensionInfo(
        id = "fixture-extension",
        name = "Fixture Extension",
        version = "2.1.0",
        uiFramework = "fixture.Ui")
    public static final class Fixture {
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
