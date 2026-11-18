/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml;

import io.github.rwml.api.MixinBridge;import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionManager;
import io.github.rwml.core.ExtensionDescriptorReader;
import io.github.rwml.core.ExtensionSelection;
import io.github.rwml.core.ExtensionVersions;
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
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加载扩展时会在调用线程中执行 {@code onLoad}，且不设定超时时间。
 *
 * <p>宿主进程独占游戏，因此扩展加载较慢只是作者选择的运行节奏，而非错误：安装操作、首次提取资源或有意等待都可能超过任意固定时限；
 * 如果加载器放弃等待回调，扩展就会处于初始化一半的状态，其类加载器却已计划关闭。另一个情况同样重要：回调抛出异常时，必须将其报告为
 * 单个扩展加载失败，同时继续加载其他扩展。</p>
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

    @Test void skipsAFrameworkAndItsDependentsBeforeLoadingWhenItNeedsNewerMappings() throws Exception {
        CountingEntry.ids.clear();
        String mainClass = "\nmainClass=" + CountingEntry.class.getName() + "\n";
        Path future = writeExtension("future-ui", CountingEntry.class, "id=future-ui" + mainClass
            + "minRwmlVersion=999.0.0\nui-framework=" + CustomUi.class.getName());
        Path dependent = writeExtension("dependent", CountingEntry.class,
            "id=dependent" + mainClass + "dependsOn=future-ui\n");
        Path ordinary = writeExtension("ordinary", CountingEntry.class, "id=ordinary" + mainClass);
        ExtensionDescriptor builtin = ExtensionDescriptor.builtIn("builtin", "Builtin", "1", "",
            UiEntry.class.getName(), DefaultUi.class.getName());
        TestHost host = new TestHost(List.of(builtin));
        ExtensionManager manager = new ExtensionManager(root, host);
        try {
            LoadReport report = manager.loadAll(List.of(dependent, future, ordinary), null);
            assertTrue(report.isClean());
            assertEquals("builtin", manager.uiFrameworkExtensionId());
            assertEquals(List.of("ordinary"), CountingEntry.ids);
            assertEquals(List.of(ordinary), host.registered);
            assertEquals(Set.of("future-ui", "dependent"), report.incompatible().keySet());
            assertTrue(report.incompatible().get("future-ui").contains("Upgrade to RWML 999.0.0"));
            assertTrue(report.incompatible().get("dependent").contains("future-ui"));
            assertTrue(manager.recoveredFailures().isEmpty());
            assertTrue(assertThrows(io.github.rwml.api.ExtensionException.class,
                () -> manager.load(future, null)).getMessage().contains("Upgrade to RWML 999.0.0"));
        } finally { manager.shutdown(); }
    }

    @Test void choosesACompatibleVersionUnlessTheUserPinnedAnIncompatibleArchive() throws Exception {
        String mainClass = "\nmainClass=" + CountingEntry.class.getName() + "\n";
        ExtensionDescriptor old = ExtensionDescriptorReader.read(writeExtension("old", CountingEntry.class,
            "id=example\nversion=1.0.0" + mainClass));
        ExtensionDescriptor latest = ExtensionDescriptorReader.read(writeExtension("latest", CountingEntry.class,
            "id=example\nversion=2.0.0" + mainClass + "minRwmlVersion=999.0.0\n"));
        List<ExtensionDescriptor> installed = List.of(old, latest);
        assertEquals(List.of(old), ExtensionSelection.resolve(installed, Set.of()).enabled());
        ExtensionSelection pinned = ExtensionSelection.resolve(installed, Set.of(),
            Map.of("example", ExtensionVersions.identity(latest)));
        assertTrue(pinned.enabled().isEmpty());
        assertTrue(pinned.incompatible().get("example").contains("Upgrade to RWML 999.0.0"));
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

    @Test void versionsResolveToTheNewestInstalledArchiveAndAnExplicitChoiceStillWins() throws Exception {
        CountingEntry.ids.clear();
        Path one = writeExtension("one", CountingEntry.class, "id=versioned\nversion=1\nmainClass=" + CountingEntry.class.getName());
        Path two = writeExtension("two", CountingEntry.class, "id=versioned\nversion=2\nmainClass=" + CountingEntry.class.getName());
        Path dependent = writeExtension("dependent", CountingEntry.class, "id=dependent\ndependsOn=versioned\nmainClass=" + CountingEntry.class.getName());
        TestHost firstHost = new TestHost();
        ExtensionManager first = new ExtensionManager(root, firstHost);
        try {
            // 没有显式选择时按版本自动解析，因此两个版本共存不再阻止加载。
            LoadReport automatic = first.loadAll(List.of(one, two, dependent), null);
            assertTrue(automatic.failed().isEmpty());
            assertEquals("2", first.handle("versioned").descriptor().version());
            assertEquals(List.of("versioned", "dependent"), CountingEntry.ids);
            assertEquals(List.of(two, dependent), firstHost.registered);
        } finally { first.shutdown(); }
        // 显式选择较低版本后，下一次启动只加载该版本，另一个归档不能单独加载。
        io.github.rwml.core.ExtensionStateStore.open(root.resolve("data")).setSelectedVersion("versioned",
            io.github.rwml.core.ExtensionDescriptorReader.read(one).sourceSha256());
        TestHost nextHost = new TestHost();
        ExtensionManager next = new ExtensionManager(root, nextHost);
        try {
            assertThrows(io.github.rwml.api.ExtensionException.class, () -> next.load(two, null));
            assertTrue(nextHost.registered.isEmpty());
            LoadReport loaded = next.loadAll(List.of(one, two, dependent), null);
            assertTrue(loaded.failed().isEmpty());
            assertEquals("1", next.handle("versioned").descriptor().version());
            assertEquals(List.of(one, dependent), nextHost.registered);
            assertThrows(io.github.rwml.api.ExtensionException.class, () -> next.load(two, null));
        } finally { next.shutdown(); }
    }

    @Test void selectedUiVersionAndDependencyAreIdenticalForEarlyAndRemainingLoads() throws Exception {
        CountingEntry.ids.clear();
        Path one = writeExtension("one", CountingEntry.class, "id=ui\nversion=1\nmainClass="
            + CountingEntry.class.getName() + "\nui-framework=" + CustomUi.class.getName());
        Path two = writeExtension("two", CountingEntry.class, "id=ui\nversion=2\ndependsOn=dep\nmainClass="
            + CountingEntry.class.getName() + "\nui-framework=" + CustomUi.class.getName());
        Path dep = writeExtension("dep", CountingEntry.class, "id=dep\nmainClass=" + CountingEntry.class.getName());
        Path later = writeExtension("later", CountingEntry.class, "id=later\nmainClass=" + CountingEntry.class.getName());
        List<Path> archives = List.of(one, two, dep, later);
        io.github.rwml.core.ExtensionStateStore state = io.github.rwml.core.ExtensionStateStore.open(root.resolve("data"));
        state.setSelectedVersion("ui", io.github.rwml.core.ExtensionDescriptorReader.read(two).sourceSha256());
        java.util.ArrayList<ExtensionDescriptor> descriptors = new java.util.ArrayList<>();
        for (Path archive : archives) { descriptors.add(io.github.rwml.core.ExtensionDescriptorReader.read(archive)); }
        io.github.rwml.core.ExtensionSelection plan = io.github.rwml.core.ExtensionSelection.resolve(descriptors,
            state.disabledIds(), state.selectedVersions());
        List<Path> early = plan.interfaceArchives().stream().map(ExtensionDescriptor::source).toList();
        assertEquals(List.of(dep, two), early);
        TestHost host = new TestHost();
        ExtensionManager manager = new ExtensionManager(root, host);
        try {
            assertTrue(manager.loadAll(early, null).failed().isEmpty());
            assertEquals("2", manager.handle("ui").descriptor().version());
            assertTrue(manager.loadRemaining(archives, null).failed().isEmpty());
            assertEquals(List.of("dep", "ui", "later"), CountingEntry.ids);
            assertEquals(List.of(dep, two, later), host.registered);
        } finally { manager.shutdown(); }
    }

    @Test void mixinRegistrationFailureDisablesOnlyItsOwnerAndClosesTheLoader() throws Exception {
        CountingEntry.ids.clear();
        Path broken = writeExtension("broken", CountingEntry.class, "id=broken\nmainClass=" + CountingEntry.class.getName());
        Path dependent = writeExtension("dependent", CountingEntry.class, "id=dependent\ndependsOn=broken\nmainClass=" + CountingEntry.class.getName());
        TestHost host = new TestHost();
        host.failRegistration = broken;
        ExtensionManager manager = new ExtensionManager(root, host);
        try {
            LoadReport report = manager.loadAll(List.of(broken, dependent), null);
            assertEquals(java.util.Set.of("broken", "dependent"), report.failed().keySet());
            assertTrue(manager.recoveryRequired());
            assertTrue(manager.isDisabled("broken"));
            assertTrue(!manager.isDisabled("dependent"), "Unavailable dependencies must not disable their dependents");
            assertTrue(CountingEntry.ids.isEmpty(), "onLoad must not run after mixin registration fails");
            assertEquals(List.of(broken), host.closed);
            assertEquals(java.util.Set.of("broken"), io.github.rwml.core.ExtensionStateStore.open(root.resolve("data")).disabledIds());
        } finally { manager.shutdown(); }
        TestHost nextHost = new TestHost();
        ExtensionManager next = new ExtensionManager(root, nextHost);
        try {
            assertTrue(next.loadAll(List.of(broken), null).failed().isEmpty());
            assertTrue(nextHost.registered.isEmpty(), "A failed extension is skipped in the next process");
        } finally { next.shutdown(); }
    }

    @Test void malformedArchivesAreQuarantinedByContentWithoutDeletingThem() throws Exception {
        Path archive = root.resolve("broken.jar");
        Files.write(archive, new byte[]{1, 2, 3});
        ExtensionManager first = new ExtensionManager(root, new TestHost());
        try {
            assertTrue(first.loadAll(List.of(archive), null).failed().containsKey("broken.jar"));
            assertTrue(first.recoveryRequired());
            assertTrue(io.github.rwml.core.ExtensionStateStore.open(root.resolve("data")).isArchiveDisabled(archive));
            assertTrue(Files.exists(archive));
        } finally { first.shutdown(); }
        ExtensionManager next = new ExtensionManager(root, new TestHost());
        try {
            LoadReport report = next.loadAll(List.of(archive), null);
            assertTrue(report.isClean());
            assertEquals(List.of("broken.jar"), report.disabled());
            assertTrue(!next.recoveryRequired());
        } finally { next.shutdown(); }
    }

    @Test void linkageErrorsInOnLoadAreRecoveredAndPersisted() throws Exception {
        Path archive = writeExtension("linkage", LinkageFailure.class, "id=linkage\nmainClass=" + LinkageFailure.class.getName());
        ExtensionManager manager = new ExtensionManager(root, new TestHost());
        try {
            assertTrue(manager.loadAll(List.of(archive), null).failed().containsKey("linkage"));
            assertTrue(manager.isDisabled("linkage"));
            assertTrue(manager.recoveryRequired());
            assertEquals(java.util.Set.of("linkage"), io.github.rwml.core.ExtensionStateStore.open(root.resolve("data")).disabledIds());
        } finally { manager.shutdown(); }
    }

    @Test void invalidUiDeclarationIsDisabledBeforeReturningToTheHost() throws Exception {
        Path archive = writeExtension("bad-ui", UiEntry.class, "id=bad-ui\nmainClass=" + UiEntry.class.getName()
            + "\nui-framework=java.lang.String");
        ExtensionManager manager = new ExtensionManager(root, new TestHost());
        try {
            assertTrue(manager.loadAll(List.of(archive), null).failed().isEmpty());
            assertThrows(io.github.rwml.api.ExtensionException.class, manager::uiFramework);
            assertTrue(manager.isDisabled("bad-ui"));
            assertTrue(manager.recoveryRequired());
        } finally { manager.shutdown(); }
    }

    public static final class LinkageFailure implements Extension {
        public void onLoad(io.github.rwml.api.ExtensionContext context) { throw new NoClassDefFoundError("missing optional library"); }
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

        // 回调会记录执行线程和耗时，使这一行为可验证：如果加载器把调用包装在工作线程中，记录的就会是另一个线程。
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
        // 之前发布的扩展可能仍携带该键。现在它属于普通的未知属性：读取描述符不能失败，也不能根据此属性生成任何字段。
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

    @Test
    void aResourcePackIsMaterialisedBeforeAnyExtensionRuns() throws Exception {
        Path pack = writeResourcePack("materials", "gui/panel.png", "materialised panel");
        Path consumer = writeExtension("consumer", PackAwareEntry.class);
        PackAwareEntry.activatedBeforeCallback = null;
        TestResourcePacks environment = new TestResourcePacks(root.resolve("generated"));
        TestHost host = new TestHost();
        host.resourcePacks = environment;
        ExtensionManager manager = new ExtensionManager(root, host);
        try {
            LoadReport report = manager.loadAll(List.of(pack, consumer), null);

            assertTrue(report.failed().isEmpty(), () -> "unexpected failures: " + report.failed());
            assertEquals("com.acme.materials", report.resourcePack());
            assertEquals("materialised panel",
                new String(Files.readAllBytes(environment.activated.resolve("gui/panel.png")), StandardCharsets.UTF_8));
            // 扩展在 onLoad 中读取资源，因此物化必须早于任何回调。
            assertEquals(Boolean.TRUE, PackAwareEntry.activatedBeforeCallback);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void selectingAResourcePackAppliesOnTheNextLaunch() throws Exception {
        Path first = writeResourcePack("first", "gui/panel.png", "first");
        Path second = writeResourcePack("second", "gui/panel.png", "second");
        TestHost host = new TestHost();
        host.resourcePacks = new TestResourcePacks(root.resolve("generated"));
        ExtensionManager manager = new ExtensionManager(root, host);
        try {
            LoadReport report = manager.loadAll(List.of(first, second), null);

            assertEquals("com.acme.first", report.resourcePack());
            assertTrue(report.disabled().contains("com.acme.second"), "the other pack stays installed but idle");
            manager.selectResourcePack("com.acme.second");
        } finally {
            manager.shutdown();
        }

        TestResourcePacks nextLaunch = new TestResourcePacks(root.resolve("generated"));
        TestHost nextHost = new TestHost();
        nextHost.resourcePacks = nextLaunch;
        ExtensionManager relaunch = new ExtensionManager(root, nextHost);
        try {
            assertEquals("com.acme.second", relaunch.loadAll(List.of(first, second), null).resourcePack());
            assertEquals("second",
                new String(Files.readAllBytes(nextLaunch.activated.resolve("gui/panel.png")), StandardCharsets.UTF_8));
        } finally {
            relaunch.shutdown();
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

    /** @return 只携带资源的归档；条目按“路径, 内容”成对给出 */
    private Path writeResourcePack(String id, String... entries) throws IOException {
        Path archive = root.resolve("extensions").resolve(id + ".jar");
        Files.createDirectories(archive.getParent());
        try (OutputStream out = Files.newOutputStream(archive);
             JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry("META-INF/extension.properties"));
            jar.write(("id=com.acme." + id + "\nname=" + id + "\nresourcePack=true\n")
                .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            for (int index = 0; index + 1 < entries.length; index += 2) {
                jar.putNextEntry(new JarEntry(entries[index]));
                jar.write(entries[index + 1].getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return archive;
    }

    /** 休眠时间超过描述符格式原先能够表示的任何超时时长。 */
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

    /** 记录回调开始时资源包是否已经激活。 */
    public static final class PackAwareEntry implements io.github.rwml.api.Extension {

        static Boolean activatedBeforeCallback;

        @Override
        public void onLoad(io.github.rwml.api.ExtensionContext context) {
            activatedBeforeCallback = Boolean.valueOf(TestResourcePacks.active != null);
        }
    }

    /** 物化目录初始为空，激活时记录核心交给宿主的目录。 */
    private static final class TestResourcePacks implements io.github.rwml.host.ResourcePackEnvironment {

        /** 最近一次激活的目录，供扩展回调检查物化是否已经完成。 */
        static Path active;

        private final Path root;
        private Path activated;

        private TestResourcePacks(Path root) {
            this.root = root;
        }

        @Override
        public boolean supported() {
            return true;
        }

        @Override
        public Path directory() {
            return root;
        }

        @Override
        public void activated(Path directory) {
            activated = directory;
            active = directory;
        }
    }

    /** 从归档加载扩展且不应用任何补丁的宿主。 */
    private static final class TestHost implements HostPlatform {
        private final List<ExtensionDescriptor> builtins;
        private final java.util.List<Path> registered = new java.util.ArrayList<>();
        private final java.util.List<Path> closed = new java.util.ArrayList<>();
        private Path failRegistration;
        private io.github.rwml.host.ResourcePackEnvironment resourcePacks =
            io.github.rwml.host.ResourcePackEnvironment.unsupported();

        TestHost() { this(Collections.emptyList()); }
        TestHost(List<ExtensionDescriptor> builtins) { this.builtins = builtins; }

        private final MixinBridge mixins = new MixinBridge() {
            @Override
            public void install() {
            }

            @Override
            public void registerArchive(Path archive) throws io.github.rwml.api.ExtensionException {
                registered.add(archive);
                if (archive.equals(failRegistration)) { throw new io.github.rwml.api.ExtensionException("deliberate mixin failure"); }
            }

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
        public io.github.rwml.host.ResourcePackEnvironment resourcePacks() {
            return resourcePacks;
        }

        @Override
        public ClassLoader createExtensionLoader(ExtensionDescriptor descriptor) throws IOException {
            return new URLClassLoader(new URL[] {descriptor.source().toUri().toURL()},
                ExtensionLoadTest.class.getClassLoader()) {
                @Override public void close() throws IOException { closed.add(descriptor.source()); super.close(); }
            };
        }

        @Override
        public ExtensionDescriptor reconcileJavaRequirement(ExtensionDescriptor descriptor,
            io.github.rwml.api.ProgressListener progress) {
            return descriptor;
        }
    }
}
