/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.desktop;

import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.Platform;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.api.Rwml;
import io.github.rwml.api.RwmlRuntime;
import io.github.rwml.api.game.GameBoot;
import io.github.rwml.api.i18n.Messages;
import io.github.rwml.api.ui.UiFramework;
import io.github.rwml.api.ui.UiHooks;
import io.github.rwml.api.ui.UiServices;
import io.github.rwml.asm.MixinSupport;
import io.github.rwml.bootstrap.Relauncher;
import io.github.rwml.builtin.VanillaInterface;
import io.github.rwml.builtin.LauncherWindow;
import io.github.rwml.core.DefaultExtensionLibrary;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionHandle;
import io.github.rwml.core.ExtensionManager;
import io.github.rwml.core.GameContextImpl;
import io.github.rwml.core.LoadReport;
import io.github.rwml.game.LocaleUiMixin;
import io.github.rwml.game.ResourcePackAssetMixin;
import io.github.rwml.game.ResourcePackResourcePathMixin;
import io.github.rwml.game.ResourcePackTexturePathMixin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 桌面宿主先准备元数据，并在启动界面显示后再加载扩展。
 *
 * <p>它与 {@code com.corrodinggames.rts.java.Main} 分开；后者按 Java 8 编译，以便在任意 JVM 上启动。此处代码使用较新字节码，
 * 只有 {@code Bootstrap} 确认 JVM 满足要求后才会访问。运行实例是当前进程的 {@link RwmlRuntime}。</p>
 *
 * <p>{@link #prepare(String[])} 只准备元数据和界面服务。之后启动扩展时若发生失败，会返回启动器，并要求在准备游戏前重启进程。</p>
 */
public final class DesktopRuntime implements RwmlRuntime {

    /** Mixin 注册表是全局的；重复准备会导致每个 Mixin 被注册两次。 */
    private static boolean prepared;

    /** 当前进程唯一的运行时，在扩展系统启动后设置。 */
    private static DesktopRuntime current;

    private final ExtensionManager manager;
    private volatile LoadReport report;
    private final GameBoot gameBoot;
    private final UiServices ui;
    private volatile boolean starting;
    private final java.util.concurrent.atomic.AtomicBoolean recoveryShown = new java.util.concurrent.atomic.AtomicBoolean();

    private DesktopRuntime(ExtensionManager manager, String[] args) throws IOException {
        this.manager = manager;
        UiHooks hooks = new UiHooks(
            failure -> report("UI listener failed", failure));
        ui = new UiServices(
            new Messages(manager.dataDirectory(), hooks),
            new DefaultExtensionLibrary(manager, manager.extensionDirectory(),
                () -> Collections.singletonList(manager.extensionDirectory()),
                () -> report, archive -> { }, hooks), hooks);
        // 引导流程属于平台，因此由桌面端在此提供画布类、平台标志、HTTP 工厂，以及交给窗口所属启动器的移交操作。
        // 界面扩展只决定是否执行这些步骤。
        this.gameBoot = new GameBoot(new GameContextImpl(
            this, gameArguments(args), manager.rootDirectory(), System.out::println));
        this.gameBoot.stopOnFailure();
        this.gameBoot.beforeStages("extensions", stage -> {
            loadExtensions((percent, message) -> stage.subProgress(message));
        });
        // Lambda 会延迟解析面向游戏的代码；扩展仍可在 onLoad 中替换这些阶段。
        this.gameBoot.add("desktop platform", context -> DesktopInterface.choosePlatform(context));
        this.gameBoot.add(GameBoot.ENGINE, context -> DesktopInterface.handOverToTheGame(context));
    }

    /** @return 正在运行的运行时；{@link #prepare} 成功前返回 {@code null} */
    public static DesktopRuntime current() {
        return current;
    }

    /**
     * 准备扩展目录和启动服务，但不加载扩展代码。
     *
     * @param args 游戏参数，由启动器转发
     * @return 扩展系统是否已就绪；返回 {@code false} 时仍会启动游戏，只是不加载扩展
     */
    public static boolean prepare(String[] args) {
        synchronized (DesktopRuntime.class) {
            if (prepared) {
                return true;
            }
            try {
                DesktopRuntime runtime = start(args);
                if (runtime != null) {
                    current = runtime;
                    Rwml.install(runtime);
                    prepared = true;
                }
                return prepared;
            } catch (Throwable t) {
                Rwml.install(null);
                report("The extension system failed to start: " + describe(t), t);
                return false;
            }
        }
    }

    @Override
    public ExtensionManager manager() {
        return manager;
    }

    @Override
    public LoadReport report() {
        return report;
    }

    /** @return 此运行时所在的平台，即 JVM */
    @Override
    public Platform platform() {
        return Platform.DESKTOP;
    }

    @Override
    public GameBoot gameBoot() {
        return gameBoot;
    }

    @Override
    public UiServices ui() {
        return ui;
    }

    /**
     * 若扩展声明了界面，则启动该界面；声明的界面不可用时继续使用内置启动器。
     *
     * @param args 游戏参数，转发给扩展界面
     * @return 是否已启动扩展界面
     */
    public static boolean startExtensionInterface(String[] args) {
        DesktopRuntime runtime = current;
        if (runtime == null) {
            return false;
        }
        DesktopUiHost host = new DesktopUiHost(runtime, args);
        LauncherWindow.launch(host, progress -> runtime.startSelectedInterface(host, progress));
        return true;
    }

    private void startSelectedInterface(DesktopUiHost host, ProgressListener progress) throws Exception {
        starting = true;
        try {
            loadExtensions(progress);
            UiFramework framework = manager.uiFramework();
            if (framework == null || framework instanceof VanillaInterface) {
                io.github.rwml.api.game.StageReport boot = gameBoot.start(progress);
                if (!boot.isClean()) { throw new ExtensionException("Game preparation failed: " + boot.failed()); }
            } else {
                try { framework.start(host); }
                catch (Throwable failure) {
                    quarantine(manager.uiFrameworkExtensionId(), failure);
                    throw new ExtensionException("The extension interface failed to start", failure);
                }
                if (gameBoot.isStarted() && !gameBoot.start().isClean()) {
                    throw new ExtensionException("Game preparation failed: " + gameBoot.start().failed());
                }
            }
            if (manager.recoveryRequired()) { throw new ExtensionException("An extension failed; restart RWML to continue"); }
        } finally { starting = false; }
    }

    /** 仅在宿主引导界面显示后调用，或由明确指定的无头启动流程调用。 */
    private synchronized void loadExtensions(ProgressListener progress) throws Exception {
        if (report == null) {
            installTransformer();
            MixinSupport.instance().onFailure((archive, failure) -> {
                try {
                    String id = io.github.rwml.core.ExtensionDescriptorReader.read(archive).id();
                    quarantine(id, failure);
                } catch (Exception error) { report("Cannot recover a failed mixin", error); }
            });
            MixinSupport.instance().register(LocaleUiMixin.class);
            // 资源包重定向必须与界面 mixin 同时注册：两者都在游戏类加载之前生效，且资源包目录在加载阶段就已物化。
            registerResourcePackMixin();
            report = manager.loadAll(manager.discover(), progress);
            printReport(report);
            if (manager.resourcePackFailure() != null) {
                System.out.println("[rwml]   ! " + manager.resourcePackFailure());
            }
            if (report.isClean()) { MixinSupport.instance().validateRegistered(); }
        }
        if (!report.isClean() || manager.recoveryRequired()) {
            ui.hooks().publish(UiHooks.EXTENSIONS_CHANGED, null);
            throw manager.recoveryFailure("Extension preparation failed: " + report.failed());
        }
    }

    /**
     * 注册资源包重定向。
     *
     * <p>这些 mixin 由宿主自带，不属于任何扩展归档。游戏只在三条互不重叠的路径上读取资源，因此逐个注册：某一类资源
     * 的注入点消失时，其余类别仍然可以替换，资源包整体不会被停用。</p>
     */
    private static void registerResourcePackMixin() {
        Class<?>[] mixins = {ResourcePackAssetMixin.class, ResourcePackResourcePathMixin.class, ResourcePackTexturePathMixin.class};
        for (Class<?> mixin : mixins) {
            try {
                MixinSupport.instance().register(mixin);
            } catch (ExtensionException failure) {
                System.err.println("[rwml] Resource pack cannot redirect " + mixin.getSimpleName() + ": " + failure.getMessage());
            }
        }
    }

    private void quarantine(String id, Throwable failure) throws ExtensionException {
        manager.quarantine(id, failure);
        ui.hooks().publish(UiHooks.EXTENSIONS_CHANGED, null);
        if (!starting && recoveryShown.compareAndSet(false, true)) {
            LauncherWindow.recover(new DesktopUiHost(this, gameBoot.context().arguments().toArray(new String[0])), failure);
        }
    }

    static String[] gameArguments(String[] args) {
        return Arrays.stream(args == null ? new String[0] : args)
            .filter(arg -> !"--rwml-no-launcher".equals(arg) && !"--rwml-manager".equals(arg))
            .toArray(String[]::new);
    }

    /** @return 扩展系统已启动的运行时；无法加载任何内容时返回 {@code null} */
    private static DesktopRuntime start(String[] args) throws Exception {
        Path root = resolveRootDirectory();
        System.out.println("[rwml] Game directory: " + root);
        System.out.println("[rwml] Extensions directory: " + root.resolve("extensions"));

        ExtensionManager manager = new ExtensionManager(root, new DesktopHost(root));
        manager.platform().stageArchives(manager.extensionDirectory());
        DesktopRuntime runtime = new DesktopRuntime(manager, args);
        runtime.ui.extensions().entries();
        Rwml.install(runtime);
        Runtime.getRuntime().addShutdownHook(new Thread(manager::shutdown, "rwml-shutdown"));
        return runtime;
    }

    /**
     * 确保 ASM 转换器已启用。使用打包 JAR 时，启动器已经通过 {@code -javaagent} 重启游戏；其余情况会尝试自附加 agent。
     */
    private static void installTransformer() {
        MixinSupport mixin = MixinSupport.instance();
        mixin.installTransformer();
        if (mixin.isInstalled()) {
            System.out.println("[rwml] Bytecode transformer is active; mixins apply as classes load.");
        } else {
            System.out.println("[rwml] Live bytecode rewriting is unavailable on this JVM. Extensions "
                + "that require mixins will be disabled. Start through the packaged jar or add -javaagent:<rwml>.jar.");
        }
    }

    /**
     * 查找 RWML 存放 {@code extensions/} 和 {@code data/} 的目录。
     *
     * <p>使用游戏 JAR 所在目录，使扩展存放在游戏旁边并能自动发现，无需额外配置。</p>
     */
    private static Path resolveRootDirectory() {
        File jar = Relauncher.ownJar();
        if (jar != null && jar.getParentFile() != null) {
            return jar.getParentFile().toPath().toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    /** 输出每轮加载结果，每个扩展占一行，以便查看失败情况。 */
    private static void printReport(LoadReport report) {
        for (String line : report.staged()) {
            System.out.println("[rwml]   " + line);
        }
        if (report.hasResourcePack()) {
            System.out.println("[rwml]   = resource pack " + report.resourcePack());
        }
        for (ExtensionHandle handle : report.loaded()) {
            ExtensionDescriptor d = handle.descriptor();
            System.out.println("[rwml]   + " + d.name() + " " + d.version() + "  [" + d.id() + "]");
        }
        for (Map.Entry<String, String> failure : report.failed().entrySet()) {
            System.out.println("[rwml]   ! " + failure.getKey() + ": " + failure.getValue());
        }
        for (String id : report.disabled()) {
            System.out.println("[rwml]   - " + id + " (disabled)");
        }
        System.out.println("[rwml] " + report.loaded().size() + " loaded, " + report.disabled().size()
            + " disabled, " + report.failed().size() + " skipped");
    }

    /**
     * 无法显示窗口时使用无界面模式回退：加载过程较短，因此每个步骤都会输出到控制台。无论是否显示窗口，都会打印扩展摘要。
     */
    private static ProgressListener consoleListener() {
        return (percent, message) ->
            System.out.println("[rwml]   " + percent + "%  " + message);
    }

    private static void report(String message, Throwable cause) {
        System.err.println("[rwml] " + message);
        cause.printStackTrace(System.err);
    }

    private static String describe(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
