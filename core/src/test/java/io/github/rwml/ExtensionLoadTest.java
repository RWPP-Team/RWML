/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml;

import io.github.rwml.api.MixinBridge;import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionManager;
import io.github.rwml.core.LoadReport;
import io.github.rwml.host.HostPlatform;
import io.github.rwml.api.Extension;
import io.github.rwml.api.ui.UiFramework;
import io.github.rwml.api.ui.UiHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loading an extension runs {@code onLoad} on the calling thread, with no time limit imposed on it.
 *
 * <p>The host is the one process that owns the game, so a slow extension is the author's own pace
 * rather than a fault: an installation step, a first-run asset extraction or a deliberate pause all
 * legitimately outlive any fixed budget, and a loader that abandons the callback leaves the
 * extension half-initialised with its class loader already scheduled for closing. The second case
 * matters just as much: a callback that throws has to be reported as a failure of that one
 * extension, and the others still load.</p>
 */class ExtensionLoadTest {

    @Test
    void aCustomFrameworkOwnsTheLauncherAndDisablingItRestoresTheBuiltin() throws Exception {
        ExtensionDescriptor builtin = ExtensionDescriptor.builtIn("builtin", "Builtin", "1.0", "",
            UiEntry.class.getName(), DefaultUi.class.getName());
        Path archive = writeExtension("custom-ui", UiEntry.class,
            "id=custom-ui\nmainClass=" + UiEntry.class.getName()
                + "\nui-framework=" + CustomUi.class.getName() + "\n");
        ExtensionManager manager = new ExtensionManager(root, new TestHost(List.of(builtin)));
        try {
            manager.loadAll(List.of(archive), null);
            assertEquals("custom-ui", manager.uiFrameworkExtensionId());
            assertTrue(manager.uiFramework() instanceof CustomUi);
            assertTrue(manager.uiFramework() == manager.uiFramework(), "UI entry must be cached");
            java.util.ArrayList<String> screens = new java.util.ArrayList<>();
            manager.uiFramework().start(new UiHost() {
                public Object platform() { return this; }
                public List<String> arguments() { return List.of(); }
                public void openScreen(String screen) { screens.add(screen); }
            });
            assertEquals(List.of("custom-launcher"), screens);
            manager.disable("custom-ui");
            assertEquals("custom-ui", manager.uiFrameworkExtensionId(), "Disabling takes effect next launch");
            ExtensionManager nextLaunch = new ExtensionManager(root, new TestHost(List.of(builtin)));
            try {
                nextLaunch.loadAll(List.of(archive), null);
                assertEquals("builtin", nextLaunch.uiFrameworkExtensionId());
                assertTrue(nextLaunch.uiFramework() instanceof DefaultUi);
            } finally {
                nextLaunch.shutdown();
            }
        } finally {
            manager.shutdown();
        }
    }

    @Test void onlyTheSelectedUiRunsCallbacksOrRegistersMixins() throws Exception {
        CountingEntry.ids.clear();
        Path first = writeExtension("first", CountingEntry.class, "id=first\nmainClass="
            + CountingEntry.class.getName() + "\nui-framework=" + CustomUi.class.getName());
        Path second = writeExtension("second", CountingEntry.class, "id=second\nmainClass="
            + CountingEntry.class.getName() + "\nui-framework=" + CustomUi.class.getName());
        ExtensionDescriptor builtin = ExtensionDescriptor.builtIn("builtin", "Builtin", "1", "",
            CountingEntry.class.getName(), DefaultUi.class.getName());
        TestHost host = new TestHost(List.of(builtin));
        ExtensionManager manager = new ExtensionManager(root, host);
        try {
            LoadReport report = manager.loadAll(List.of(first, second), null);
            assertEquals(List.of("first"), CountingEntry.ids);
            assertEquals(List.of(first), host.registered);
            assertEquals(java.util.Set.of("second", "builtin"), java.util.Set.copyOf(report.disabled()));
            assertEquals(List.of("first"), sorted(manager.extensionIds()));
            assertThrows(io.github.rwml.api.ExtensionException.class, () -> manager.load(second, null));
            assertEquals(List.of("first"), CountingEntry.ids);
        } finally { manager.shutdown(); }
    }
    @Test void remainingLoadReusesEarlyUiExactlyOnceAndRejectsChangedSha() throws Exception {
        CountingEntry.ids.clear();
        Path ui = writeExtension("ui", CountingEntry.class, "id=ui\nmainClass="
            + CountingEntry.class.getName() + "\nui-framework=" + CustomUi.class.getName());
        Path ordinary = writeExtension("ordinary", CountingEntry.class,
            "id=ordinary\nmainClass=" + CountingEntry.class.getName());
        TestHost host = new TestHost();
        ExtensionManager manager = new ExtensionManager(root, host);
        try {
            manager.loadAll(List.of(ui), null);
            Object early = manager.uiFramework();
            LoadReport report = manager.loadRemaining(List.of(ui, ordinary), null);
            assertTrue(report.failed().isEmpty());
            assertSame(early, manager.uiFramework());
            assertEquals(List.of("ui", "ordinary"), CountingEntry.ids);
            assertEquals(List.of(ui, ordinary), host.registered);
            writeExtension("ui", CountingEntry.class, "id=ui\nversion=2\nmainClass="
                + CountingEntry.class.getName() + "\nui-framework=" + CustomUi.class.getName());
            assertTrue(manager.loadRemaining(List.of(ui, ordinary), null).failed().get("ui").contains("changed"));
            assertEquals(List.of("ui", "ordinary"), CountingEntry.ids);
        } finally { manager.shutdown(); }
    }
    @Test void reentrantOnLoadCannotActivateAnotherUiOrLoadItselfTwice() throws Exception {
        Path first = writeExtension("first", ReentrantUiEntry.class, "id=first\nmainClass="
            + ReentrantUiEntry.class.getName() + "\nui-framework=" + CustomUi.class.getName());
        Path second = writeExtension("second", CountingEntry.class, "id=second\nmainClass="
            + CountingEntry.class.getName() + "\nui-framework=" + CustomUi.class.getName());
        ExtensionManager manager = new ExtensionManager(root, new TestHost());
        ReentrantUiEntry.manager = manager; ReentrantUiEntry.first = first; ReentrantUiEntry.second = second;
        ReentrantUiEntry.calls = 0;
        try {
            assertTrue(manager.loadAll(List.of(first, second), null).failed().isEmpty());
            assertEquals(1, ReentrantUiEntry.calls);
            assertEquals(List.of("first"), sorted(manager.extensionIds()));
        } finally { manager.shutdown(); ReentrantUiEntry.manager = null; }
    }
    public static final class ReentrantUiEntry implements Extension {
        static ExtensionManager manager;
        static Path first, second;
        static int calls;
        public void onLoad(io.github.rwml.api.ExtensionContext context) {
            calls++;
            assertThrows(io.github.rwml.api.ExtensionException.class, () -> manager.load(second, null));
            assertThrows(io.github.rwml.api.ExtensionException.class, () -> manager.load(first, null));
        }
    }
    @Test void competingThreadsCannotEnableTwoFrameworks() throws Exception {
        CountingEntry.ids.clear();
        Path first = writeExtension("first", CountingEntry.class, "id=first\nmainClass="
            + CountingEntry.class.getName() + "\nui-framework=" + CustomUi.class.getName());
        Path second = writeExtension("second", CountingEntry.class, "id=second\nmainClass="
            + CountingEntry.class.getName() + "\nui-framework=" + CustomUi.class.getName());
        ExtensionManager manager = new ExtensionManager(root, new TestHost());
        java.util.concurrent.CountDownLatch begin = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger loaded = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Consumer<Path> attempt = archive -> {
            try { begin.await(); manager.load(archive, null); loaded.incrementAndGet(); }
            catch (io.github.rwml.api.ExtensionException expected) { rejected.incrementAndGet(); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        };
        Thread a = new Thread(() -> attempt.accept(first));
        Thread b = new Thread(() -> attempt.accept(second));
        try {
            a.start(); b.start(); begin.countDown(); a.join(3000); b.join(3000);
            assertEquals(1, loaded.get()); assertEquals(1, rejected.get());
            assertEquals(1, manager.extensionIds().size()); assertEquals(1, CountingEntry.ids.size());
        } finally { manager.shutdown(); }
    }

    public static final class CountingEntry implements Extension {
        static final java.util.List<String> ids = new java.util.ArrayList<>();
        public void onLoad(io.github.rwml.api.ExtensionContext context) { ids.add(context.extensionId()); }
    }

    public static final class UiEntry implements Extension { }

    public static final class DefaultUi implements UiFramework {
        public void start(UiHost host) {
            throw new AssertionError("A builtin launcher must not start when a custom UI is selected");
        }
    }

    public static final class CustomUi implements UiFramework {
        public void start(UiHost host) { host.openScreen("custom-launcher"); }
    }

    private static final long SLOW_CALLBACK_MILLIS = 1_500L;

    @TempDir
    Path root;

    @Test
    void anOnLoadThatOutlivesAnyFixedBudgetStillCompletes() throws Exception {
        Path archive = writeExtension("slow", SlowEntryPoint.class);
        ExtensionManager manager = new ExtensionManager(root, new TestHost());
        try {
            LoadReport report = manager.loadAll(List.of(archive), null);

            assertTrue(report.failed().isEmpty(), () -> "unexpected failures: " + report.failed());
            assertEquals(List.of("com.acme.slow"), sorted(manager.extensionIds()));
        } finally {
            manager.shutdown();
        }

        // The callback records the thread it ran on and how long it took, which is what makes the
        // claim checkable: a loader that wrapped the call in a worker would show another thread.
        assertEquals(Thread.currentThread().getName(), SlowEntryPoint.threadName);
        assertTrue(SlowEntryPoint.elapsed >= SLOW_CALLBACK_MILLIS,
            "the callback returned before it had finished its work");
    }

    @Test
    void aFailingOnLoadIsReportedWithoutAffectingTheOtherExtensions() throws Exception {
        Path failing = writeExtension("failing", FailingEntryPoint.class);
        Path healthy = writeExtension("healthy", SlowEntryPoint.class);
        ExtensionManager manager = new ExtensionManager(root, new TestHost());
        try {
            LoadReport report = manager.loadAll(List.of(failing, healthy), null);

            assertEquals(Collections.singleton("com.acme.failing"), report.failed().keySet());
            assertTrue(report.failed().get("com.acme.failing").contains("onLoad"),
                () -> "the report does not say which step failed: " + report.failed());
            assertEquals(List.of("com.acme.healthy"), sorted(manager.extensionIds()));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void aDescriptorWithNoTimeoutFieldIsStillRead() throws Exception {
        // A previously published extension may still carry the key. It is now an ordinary unknown
        // property: reading the descriptor must not fail, and no field may be derived from it.
        Path archive = writeExtension("legacy", SlowEntryPoint.class,
            "id=com.acme.legacy\nname=Legacy\nmainClass=" + SlowEntryPoint.class.getName()
                + "\nloadTimeoutMillis=1\n");
        ExtensionManager manager = new ExtensionManager(root, new TestHost());
        try {
            LoadReport report = manager.loadAll(List.of(archive), null);

            assertTrue(report.failed().isEmpty(), () -> "unexpected failures: " + report.failed());
            assertTrue(manager.extensionIds().contains("com.acme.legacy"));
        } finally {
            manager.shutdown();
        }
    }

    private static List<String> sorted(java.util.Collection<String> ids) {
        return ids.stream().sorted().collect(java.util.stream.Collectors.toList());
    }

    private Path writeExtension(String id, Class<?> entryPoint, String... extraProperties) throws IOException {
        Path archive = root.resolve("extensions").resolve(id + ".jar");
        Files.createDirectories(archive.getParent());
        StringBuilder properties = new StringBuilder();
        for (String line : extraProperties) {
            properties.append(line).append('\n');
        }
        if (extraProperties.length == 0) {
            properties.append("id=com.acme.").append(id).append('\n')
                .append("name=").append(id).append('\n')
                .append("mainClass=").append(entryPoint.getName()).append('\n');
        }
        try (OutputStream out = Files.newOutputStream(archive);
             JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry("META-INF/extension.properties"));
            jar.write(properties.toString().getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();

            String resource = entryPoint.getName().replace('.', '/') + ".class";
            jar.putNextEntry(new JarEntry(resource));
            try (InputStream bytes = ExtensionLoadTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = bytes.read(buffer)) != -1) {
                    jar.write(buffer, 0, read);
                }
            }
            jar.closeEntry();
        }
        return archive;
    }

    /** Sleeps for longer than any timeout the descriptor format used to be able to express. */
    public static final class SlowEntryPoint implements io.github.rwml.api.Extension {

        static String threadName;
        static long elapsed;

        @Override
        public void onLoad(io.github.rwml.api.ExtensionContext context) {
            threadName = Thread.currentThread().getName();
            long start = System.nanoTime();
            try {
                Thread.sleep(SLOW_CALLBACK_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the loader interrupted the callback", e);
            }
            elapsed = (System.nanoTime() - start) / 1_000_000L;
        }
    }

    public static final class FailingEntryPoint implements io.github.rwml.api.Extension {

        @Override
        public void onLoad(io.github.rwml.api.ExtensionContext context) {
            throw new IllegalStateException("deliberate failure");
        }
    }

    /** A host that loads extensions from their archives and patches nothing. */
    private static final class TestHost implements HostPlatform {
        private final List<ExtensionDescriptor> builtins;
        private final java.util.List<Path> registered = new java.util.ArrayList<>();

        TestHost() { this(Collections.emptyList()); }
        TestHost(List<ExtensionDescriptor> builtins) { this.builtins = builtins; }

        private final MixinBridge mixins = new MixinBridge() {
            @Override
            public void install() {
            }

            @Override
            public void registerArchive(Path archive) { registered.add(archive); }

            @Override
            public void register(Class<?> mixin) {
            }

            @Override
            public byte[] transform(String internalName, byte[] classfileBuffer, ClassLoader loader) {
                return classfileBuffer;
            }
        };

        @Override
        public MixinBridge mixins() {
            return mixins;
        }

        @Override
        public List<ExtensionDescriptor> builtInExtensions() {
            return builtins;
        }

        @Override
        public List<String> stageArchives(Path extensionDirectory) {
            return Collections.emptyList();
        }

        @Override
        public ClassLoader createExtensionLoader(ExtensionDescriptor descriptor) throws IOException {
            return new URLClassLoader(new URL[] {descriptor.source().toUri().toURL()},
                ExtensionLoadTest.class.getClassLoader());
        }

        @Override
        public ExtensionDescriptor reconcileJavaRequirement(ExtensionDescriptor descriptor,
            io.github.rwml.api.ProgressListener progress) {
            return descriptor;
        }
    }
}
