/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml;

import io.github.rwml.api.Extension;
import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.MixinBridge;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionManager;
import io.github.rwml.host.HostPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionDependencyClassLoaderTest {

    @TempDir Path temporary;

    @Test
    void dependentExtensionsResolveOneSharedApiClassAndStaticState() throws Exception {
        Path provider = compileProvider();
        Path consumerA = compileConsumer("consumer-a", "sample.ConsumerA", "sample.ConsumerA", provider,
            "api-library");
        Path consumerB = compileConsumer("consumer-b", "sample.ConsumerB", "sample.ConsumerB", provider,
            "api-library");
        ExtensionManager manager = new ExtensionManager(temporary.resolve("runtime"), new TestHost());
        try {
            assertTrue(manager.loadAll(List.of(consumerB, consumerA, provider), null).failed().isEmpty());
            ClassLoader providerLoader = manager.handle("api-library").classLoader();
            Class<?> api = providerLoader.loadClass("sample.SharedApi");
            assertSame(api, manager.handle("consumer-a").classLoader().loadClass("sample.SharedApi"));
            assertSame(api, manager.handle("consumer-b").classLoader().loadClass("sample.SharedApi"));
            assertEquals(2, api.getField("calls").getInt(null),
                "both consumers should update the provider's single static state");
            ExtensionException blocked = assertThrows(ExtensionException.class,
                () -> manager.unload("api-library"));
            assertTrue(blocked.getMessage().contains("consumer-a")
                || blocked.getMessage().contains("consumer-b"));
            assertSame(api, manager.handle("consumer-a").classLoader().loadClass("sample.SharedApi"));
            manager.unload("consumer-b");
            manager.unload("consumer-a");
            manager.unload("api-library");
            assertTrue(manager.handles().isEmpty());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void missingDeclaredDependencyFailsBeforeTheConsumerLoads() throws Exception {
        Path compileOnlyProvider = compileProvider();
        Path absent = compileConsumer("missing-consumer", "sample.ConsumerMissing", "sample.ConsumerMissing",
            compileOnlyProvider, "not-installed");
        ExtensionManager manager = new ExtensionManager(temporary.resolve("missing-runtime"), new TestHost());
        try {
            var report = manager.loadAll(List.of(absent), null);
            assertFalse(report.failed().isEmpty());
            assertTrue(report.failed().get("missing-consumer").contains("not-installed"));
            assertEquals(null, manager.handle("missing-consumer"));
        } finally {
            manager.shutdown();
        }
    }

    private Path compileProvider() throws Exception {
        Path source = writeSource("provider-src/sample/SharedApi.java", """
            package sample;
            public final class SharedApi {
                public static int calls;
                public static void called() { calls++; }
            }
            """);
        Path entry = writeSource("provider-src/sample/ApiLibrary.java", """
            package sample;
            public final class ApiLibrary implements io.github.rwml.api.Extension { }
            """);
        Path classes = temporary.resolve("provider-classes");
        compile(classes, null, source, entry);
        return archive("api-library.jar", classes, "id=api-library\nmainClass=sample.ApiLibrary\n");
    }

    private Path compileConsumer(String fileName, String className, String sourceName,
                                 Path provider, String dependency) throws Exception {
        String simpleName = sourceName.substring(sourceName.lastIndexOf('.') + 1);
        Path source = writeSource(fileName + "-src/sample/" + simpleName + ".java", """
            package sample;
            public final class %s implements io.github.rwml.api.Extension {
                @Override public void onLoad(io.github.rwml.api.ExtensionContext context) {
                    SharedApi.called();
                }
            }
            """.formatted(simpleName));
        Path classes = temporary.resolve(fileName + "-classes");
        compile(classes, provider, source);
        return archive(fileName + ".jar", classes,
            "id=" + fileName + "\nmainClass=" + className + "\ndependsOn=" + dependency + "\n");
    }

    private void compile(Path output, Path dependency, Path... sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "tests require a JDK with the Java compiler");
        Files.createDirectories(output);
        String coreClasses = Path.of(Extension.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toString();
        String classPath = coreClasses + (dependency == null ? "" : java.io.File.pathSeparator + dependency);
        List<String> args = new ArrayList<>(List.of("--release", "17", "-classpath", classPath,
            "-d", output.toString()));
        for (Path source : sources) { args.add(source.toString()); }
        int result = compiler.run(null, null, null, args.toArray(new String[0]));
        assertEquals(0, result, "dynamic fixture compilation failed");
    }

    private Path writeSource(String relative, String text) throws IOException {
        Path source = temporary.resolve(relative);
        Files.createDirectories(source.getParent());
        Files.write(source, text.getBytes(StandardCharsets.UTF_8));
        return source;
    }

    private Path archive(String name, Path classes, String descriptor) throws IOException {
        Path archive = temporary.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new JarEntry("META-INF/extension.properties"));
            output.write(descriptor.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            try (Stream<Path> files = Files.walk(classes)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, output);
                    output.closeEntry();
                }
            }
        }
        return archive;
    }

    private static final class TestHost implements HostPlatform {
        private final MixinBridge mixins = new MixinBridge() {
            @Override public void install() { }
            @Override public void registerArchive(Path archive) { }
            @Override public void register(Class<?> mixin) { }
            @Override public byte[] transform(String internalName, byte[] classfileBuffer, ClassLoader loader) {
                return classfileBuffer;
            }
        };

        @Override public MixinBridge mixins() { return mixins; }
        @Override public List<ExtensionDescriptor> builtInExtensions() { return Collections.emptyList(); }
        @Override public List<String> stageArchives(Path extensionDirectory) { return Collections.emptyList(); }
        @Override public ClassLoader createExtensionLoader(ExtensionDescriptor descriptor) throws IOException {
            return createExtensionLoader(descriptor, ExtensionDependencyClassLoaderTest.class.getClassLoader());
        }
        @Override public ClassLoader createExtensionLoader(ExtensionDescriptor descriptor, ClassLoader parent)
            throws IOException {
            return new URLClassLoader(new URL[]{descriptor.source().toUri().toURL()}, parent);
        }
        @Override public ExtensionDescriptor reconcileJavaRequirement(ExtensionDescriptor descriptor,
                                                                       ProgressListener progress) {
            return descriptor;
        }
    }
}
