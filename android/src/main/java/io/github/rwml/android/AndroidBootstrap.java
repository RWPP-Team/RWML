/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.content.Context;
import android.content.pm.PackageInfo;

import com.corrodinggames.rts.gameFramework.gui.MinimapHandlerQueuedPointsDrawMixin;

import io.github.rwml.android.builtin.AndroidExtension;
import io.github.rwml.android.mixin.AndroidSoundFactoryMixin;
import io.github.rwml.android.mixin.GraphicsEngineAndroidMixin;
import io.github.rwml.android.mixin.MinimapHandlerMixin;
import io.github.rwml.android.mixin.NetworkEngineNotificationMixin;
import io.github.rwml.android.mixin.ResourcePackImageMixin;
import io.github.rwml.android.mixin.ResourcePackMusicMixin;
import io.github.rwml.android.mixin.ResourcePackSoundMixin;
import io.github.rwml.android.mixin.ServerListNotifyMixin;
import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.MixinBridge;
import io.github.rwml.api.Platform;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.api.Rwml;
import io.github.rwml.api.RwmlRuntime;
import io.github.rwml.api.game.GameBoot;
import io.github.rwml.api.i18n.Messages;
import io.github.rwml.api.ui.UiFramework;
import io.github.rwml.api.ui.UiHooks;
import io.github.rwml.api.ui.UiServices;
import io.github.rwml.core.repository.RepositoryClient;
import io.github.rwml.core.DefaultExtensionLibrary;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionDescriptorReader;
import io.github.rwml.core.ExtensionManager;
import io.github.rwml.core.ExtensionSelection;
import io.github.rwml.core.ExtensionStateStore;
import io.github.rwml.core.GameContextImpl;
import io.github.rwml.core.LoadReport;
import io.github.rwml.core.transform.ArchiveClassPatches;
import io.github.rwml.game.LocaleUiMixin;

import kim.der.asm.AsmRegistry;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * Android 启动器，对应桌面端的 {@code Bootstrap}。
 *
 * <p>Android 没有 agent，因此归档会预先完成改写、编译为 dex，并放到类路径最前面；dex 会按其构建
 * 来源生成缓存键并缓存。扩展必须先挂载，因为注册器应用 mixin 前，必须先能解析对应的类。</p>
 *
 * <p>任何步骤失败都会中止启动：只完成一半改写的游戏会在之后的其他位置才报错。</p>
 */
public final class AndroidBootstrap implements RwmlRuntime {

    /**
     * 标识改写规则和缓存布局；应用更新时递增此版本，避免复用由其他版本 RWML 生成的 dex。
     *
     * <p>{@code rwml-2}：将 dex 设为只读，并在每次运行时重置 dx 状态。
     * {@code rwml-3}：移除随包提供的 {@code android/*} 类，由平台提供这些类。
     * {@code rwml-4}：保留 {@code android/support/*}，因为其他位置不会提供它。
     * {@code rwml-5}：纳入应用自身对游戏类的 mixin，这些类无法通过暂存文件访问。
     * {@code rwml-6}：将构建报告保存在对应 dex 旁边。
     * {@code rwml-7}：强制重建一次，使新报告逻辑也能在已有缓存的设备上运行。
     * {@code rwml-8}：将图像 mixin 的处理方法改为静态方法，并从 {@link GameResources} 获取上下文，
     * 不再读取始终为空的隐藏字段。
     * {@code rwml-9}：将应用自身代码纳入缓存键；应用更新后无需手动递增版本，也能替换编译了其 mixin 的 dex。
     * {@code rwml-11}：将替身 dex 从游戏类加载器移出，以便持有 {@code rwml-10} 缓存的设备识别此次变更。
     * {@code rwml-12}：将扩展的完整 JAR 加入类路径，使扩展资源能通过同一类加载器读取。
     * {@code rwml-13}：加入资源包重定向，改写后的资源类必须重新生成。</p>
     */
    private static final String CACHE_FORMAT = "rwml-13-resource-packs";

    private static final String GAME_GROUP = "game";
    private static final String EXTENSION_GROUP = "extensions";
    private static final String KEY_FILE = "key.txt";

    /**
     * 与缓存键并列的文件，保存生成缓存 dex 的那次构建所报告的信息。第一行为 {@link #PATCHED_PREFIX}，
     * 后面是该构建改动的类数量。
     */
    private static final String BUILD_LOG = "build.log";

    /** {@link #BUILD_LOG} 第一行的前缀，用于标记已修补类的数量。 */
    private static final String PATCHED_PREFIX = "patched ";

    private final Context context;
    private final List<String> log = new ArrayList<>();

    /** 拥有已挂载游戏 dex 的加载器；准备流程挂载 dex 前为 null。 */
    private static volatile ClassLoader mountedLoader;

    private Path rootDirectory;
    private Path gameDirectory;
    private Path extensionsDirectory;
    private Path dataDirectory;
    private Path dexDirectory;

    private OfflineMixinBridge bridge;
    private AndroidHost host;
    private ExtensionSelection selection;
    private List<Path> archives;
    private boolean interfacePrepared;
    private String sourceCatalog;
    private boolean reloadRequested;
    private ProgressListener preparationProgress = ProgressListener.NOOP;
    private final Set<Path> mountedDex = new LinkedHashSet<>();
    private ExtensionManager manager;
    private ClassLoader classLoader;
    private LoadReport lastReport;
    private GameBoot gameBoot;
    private boolean gameStartRequested;
    private UiServices ui;
    private String cacheKey = "";
    private boolean cacheHit;
    private int patchedClasses;
    private boolean prepared;
    private final java.util.concurrent.atomic.AtomicBoolean recoveryShown = new java.util.concurrent.atomic.AtomicBoolean();

    public AndroidBootstrap(Context context) {
        this.context = context;
        GameResources.attach(context);
    }

    /**
     * @return 拥有已挂载游戏 dex 的类加载器；准备流程挂载 dex 前返回 null
     *
     * <p>除非平台将 dex 放进应用自身的路径，否则应用中的类无法按名称访问游戏类。因此，任何通过反射
     * 查找游戏类的代码都必须明确使用此加载器，不能假定游戏类就在应用类加载器中。</p>
     */
    public static ClassLoader gameLoader() {
        return mountedLoader;
    }

    /**
     * 进程持有的唯一运行时，在首次请求时创建。
     *
     * <p>每个游戏 Activity 都会通过 {@code AndroidApp.prepare} 调用 {@link #prepare(Context)}，但 Android
     * 可能从上次打开的 Activity 重启进程，而该 Activity 并未执行启动画面的引导流程。使用单例可确保
     * 从不同入口进入时准备过程都是幂等的，让游戏 dex 始终挂载完成，并能从进程启动位置访问游戏类。</p>
     */
    private static volatile AndroidBootstrap shared;

    /** @return 初始化运行时后，拥有游戏 dex 的加载器。 */
    public static ClassLoader prepare(Context context) {
        AndroidBootstrap bootstrap = get(context);
        if (!bootstrap.prepare()) {
            throw new IllegalStateException("The Android runtime could not be prepared: " + bootstrap.log());
        }
        return gameLoader();
    }

    /** 每个进程只拥有一个运行时；Activity 不能再创建另一个挂载会话。 */
    public static AndroidBootstrap get(Context context) {
        AndroidBootstrap bootstrap = shared;
        if (bootstrap == null) {
            synchronized (AndroidBootstrap.class) {
                bootstrap = shared;
                if (bootstrap == null) {
                    bootstrap = new AndroidBootstrap(context.getApplicationContext());
                    shared = bootstrap;
                }
            }
        }
        return bootstrap;
    }

    /**
     * 只准备一次运行时；首次调用后的所有调用均不执行操作。
     *
     * <p>准备失败会中止游戏启动；界面仍可用于显示诊断信息。</p>
     *
     * @return 运行时是否已就绪
     */
    public synchronized boolean prepare() {
        if (prepared) {
            return true;
        }
        // 首次启动要改写数千个类并将其编译为 dex，耗时较长，用户可能无法判断程序是在缓慢运行还是已经停止。
        Watchdog watchdog = Watchdog.start("prepare");
        try {
            prepared = start();
        } catch (Throwable t) {
            HostLog.error("game dex preparation failed", t);
            record("bootstrap failed: " + describe(t));
            showRecovery(t);
        } finally {
            watchdog.stop();
        }
        return prepared;
    }

    /** 仅在进入启动器时检查更新，游戏运行期间不会检查。 */
    public synchronized boolean prepareLauncher() throws IOException, ExtensionException {
        if (reloadRequested) { return false; }
        if (interfacePrepared && !sourceCatalog.equals(ExtensionCatalog.fingerprint(
                sharedExtensionsDirectory(), extensionsDirectory,
                dataDirectory.resolve("extension-state.properties")))) {
            // 再次进入启动器时可以复用此进程；ART 中已加载的类发生变化后，则必须启动新进程。
            reloadRequested = true;
            record("extensions changed at launcher entry; reloading in a fresh process");
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                context.startActivity(new android.content.Intent(context, RestartActivity.class)
                    .putExtra("rwml.oldPid", android.os.Process.myPid())
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)));
            return false;
        }
        prepareInterface();
        return true;
    }

    /** 只挂载界面插件及其依赖。此处不会暂存或编译游戏 JAR。 */
    public synchronized void prepareInterface() throws IOException, ExtensionException {
        if (interfacePrepared) { return; }
        HostLog.install();
        rootDirectory = new File(context.getFilesDir(), "rwml").toPath();
        gameDirectory = rootDirectory.resolve(GAME_GROUP);
        extensionsDirectory = rootDirectory.resolve(EXTENSION_GROUP);
        dataDirectory = rootDirectory.resolve("data");
        dexDirectory = rootDirectory.resolve("dex");
        Files.createDirectories(extensionsDirectory);
        Files.createDirectories(dataDirectory);
        recordAll(AssetInstaller.install(context, EXTENSION_GROUP, extensionsDirectory));
        sourceCatalog = ExtensionCatalog.fingerprint(sharedExtensionsDirectory(), extensionsDirectory,
            dataDirectory.resolve("extension-state.properties"));
        archives = new ArrayList<>(SharedExtensions.synchronize(
            sharedExtensionsDirectory(), rootDirectory.resolve("shared-extensions")));
        archives.addAll(AssetInstaller.installed(extensionsDirectory));
        List<ExtensionDescriptor> descriptors = enabledExtensions(archives);
        if (descriptors.stream().noneMatch(descriptor -> descriptor.id().equals(AndroidExtension.ID))) {
            descriptors.add(AndroidExtension.descriptor());
        }
        ExtensionStateStore states = ExtensionStateStore.open(dataDirectory);
        selection = ExtensionSelection.resolve(descriptors, states.disabledIds(), states.selectedVersions(),
            states.selectedResourcePack());
        AsmRegistry.clear();
        classLoader = AndroidBootstrap.class.getClassLoader();
        bridge = new OfflineMixinBridge(classLoader);
        host = new AndroidHost(context, bridge, classLoader,
            new AndroidResourcePacks(rootDirectory.resolve("resource-pack")));
        manager = new ExtensionManager(rootDirectory, host);
        UiHooks hooks = new UiHooks(
            failure -> HostLog.error("UI listener failed", failure));
        ui = new UiServices(
            new Messages(dataDirectory, hooks),
            new DefaultExtensionLibrary(manager, sharedExtensionsDirectory(),
                () -> Arrays.asList(sharedExtensionsDirectory(), extensionsDirectory),
                () -> lastReport, Dexer::validateCarriedDex, hooks), hooks);
        // 仓库只读取数据目录中的配置；目录与归档请求由界面在工作线程发起。
        // 链接错误单列处理：设备若缺少某个 JDK 类，宿主仍应完成启动，仓库功能按不可用处理。
        try {
            ui.repository(RepositoryClient.open(ui.extensions(), Platform.ANDROID, dataDirectory));
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            HostLog.error("The extension repository is unavailable", unavailable);
        }
        gameBoot = new GameBoot(new GameContextImpl(context, null, rootDirectory, HostLog::add));
        gameBoot.stopOnFailure();
        gameBoot.add(GameBoot.ENGINE, stage -> {
            requirePrepared();
            // 只有在游戏 dex 加入应用类路径后，才能解析面向游戏的辅助类。
            Class<?> game = Class.forName("io.github.rwml.android.AndroidGame", true, classLoader);
            game.getMethod("create", Context.class, ProgressListener.class).invoke(null, context,
                (ProgressListener) (percent, message) -> stage.subProgress(message));
        });
        ui.extensions().entries();
        Rwml.install(this);
        List<Path> early = new ArrayList<>();
        List<Path> dex = new ArrayList<>();
        for (ExtensionDescriptor descriptor : selection.interfaceArchives()) {
            early.add(descriptor.source());
            dex.add(ensureExtensionArchive(descriptor));
        }
        classLoader = mount(classLoader, dex);
        bridge.updateLoader(classLoader);
        host.updateLoader(classLoader);
        LoadReport earlyReport = manager.loadAll(early, (percent, message) -> record("ui " + message));
        if (!earlyReport.isClean() || manager.recoveryRequired()) {
            lastReport = earlyReport;
            throw new ExtensionException("Selected UI could not load: " + earlyReport.failed());
        }
        interfacePrepared = true;
        record("interface ready; game dex has not been prepared");
    }

    public static Path sharedExtensionsDirectory() {
        return new File(android.os.Environment.getExternalStorageDirectory(),
            "rustedWarfare/extensions").toPath();
    }

    private void requirePrepared() {
        if (!prepared) { throw new IllegalStateException("Game dex preparation failed"); }
    }

    /** @return 用于加载扩展的管理器；调用 {@link #prepare} 前返回 {@code null}。 */
    @Override
    public ExtensionManager manager() {
        return manager;
    }

    /** @return 最近一次 {@link #load} 的结果；尚未执行过时返回 {@code null}。 */
    @Override
    public LoadReport report() {
        return lastReport;
    }

    /** @return 当前运行时所处的平台，即 Android。 */
    @Override
    public Platform platform() {
        return Platform.ANDROID;
    }

    /**
     * @return 平台引导流程，可在早期扩展回调和游戏准备之前获取
     */
    @Override
    public GameBoot gameBoot() {
        return gameBoot;
    }

    @Override
    public UiServices ui() {
        if (ui == null) { throw new IllegalStateException("UI services are not prepared"); }
        return ui;
    }

    /**
     * @return 声明了用户界面的扩展 ID；无界面模式下返回 {@code null}
     */
    public String uiFrameworkExtensionId() {
        ExtensionManager current = manager;
        return current == null ? null : current.uiFrameworkExtensionId();
    }

    /**
     * @return 要启动的界面；没有扩展声明界面时返回 {@code null}
     * @throws ExtensionException 无法加载该界面或其未实现 {@code UiFramework} 时抛出
     */
    public UiFramework uiFramework() throws ExtensionException {
        ExtensionManager current = manager;
        return current == null ? null : current.uiFramework();
    }

    /**
     * 通过共享管理器加载所有已暂存的扩展，并记录加载结果。
     *
     * @throws IllegalStateException 运行时尚未准备完成时抛出
     */
    public synchronized LoadReport load(ProgressListener progress) throws IOException {
        if (manager == null) {
            throw new IllegalStateException("the runtime is not prepared; call prepare() first");
        }
        if (lastReport != null) {
            return lastReport;
        }
        LoadReport loaded = manager.loadRemaining(archives, progress);
        lastReport = loaded;
        if (loaded.hasResourcePack()) {
            record("resource   " + loaded.resourcePack() + " is active");
        }
        if (manager.resourcePackFailure() != null) {
            record("resource   ! " + manager.resourcePackFailure());
        }
        return loaded;
    }

    /** Host bootstrap phase: install mixins and extensions without constructing the engine. */
    public synchronized void prepareGameRuntime(ProgressListener progress) throws Exception {
        preparationProgress = progress == null ? ProgressListener.NOOP : progress;
        try {
            preparationProgress.onProgress(0, "game dex");
            if (!prepare()) { throw new IllegalStateException("Game preparation failed: " + log()); }
            LoadReport loaded = load(preparationProgress);
            if (!loaded.isClean() || manager.recoveryRequired()) {
                ExtensionException failure = manager.recoveryFailure("Extension preparation failed: " + loaded.failed());
                showRecovery(failure);
                throw failure;
            }
        } finally { preparationProgress = ProgressListener.NOOP; }
    }

    public synchronized void requestGameStart() { gameStartRequested = true; }

    /** @return 准备流程按顺序产生的所有日志行。 */
    public List<String> log() {
        return Collections.unmodifiableList(new ArrayList<>(log));
    }

    /** @return dex 缓存使用的摘要；调用 {@link #prepare} 前为空。 */
    public String cacheKey() {
        return cacheKey;
    }

    /** @return 准备流程是否复用了缓存，而未重新编译。 */
    public boolean usedCache() {
        return cacheHit;
    }

    /** Launcher metadata is ready; game classes and mixins are deferred until GameBoot starts. */
    public synchronized boolean launcherReady() { return interfacePrepared; }

    /** @return 最近一次构建中，已注册 mixin 修改的游戏类数量。 */
    public int patchedClasses() {
        return patchedClasses;
    }

    /** 使 dex 缓存对下一个进程失效；已挂载的类无法在当前进程中卸载。 */
    public synchronized void clear() throws IOException {
        deleteRecursively(dexDirectory);
    }

    // ------------------------------------------------------------------
    // 启动流程
    // ------------------------------------------------------------------

    private boolean start() throws IOException, ExtensionException {
        prepareInterface();
        record("preparing game assets");
        recordAll(AssetInstaller.install(context, GAME_GROUP, gameDirectory));
        List<Path> gameArchives = AssetInstaller.installed(gameDirectory);
        List<ExtensionDescriptor> enabled = new ArrayList<>();
        for (ExtensionDescriptor descriptor : selection.enabled()) {
            if (!descriptor.isBuiltIn()) { enabled.add(descriptor); }
        }
        cacheKey = computeKey(gameArchives, enabled);

        cacheHit = isCacheValid(enabled, gameArchives);
        if (cacheHit) {
            record("cache      dex reused  (key " + AssetInstaller.Digest.shortHash(cacheKey) + ")");
            replayBuildLog();
            clearRestartMarker();
            classLoader = mount(classLoader, collectDex(enabled, gameArchives));
        } else {
            int first = log.size();
            classLoader = build(enabled, gameArchives);
            writeBuildLog(log.subList(first, log.size()));
            writeKey();
            // 构建过程与启动时的状态不同。应用 mixin 时，归档仍是 JAR，修补后的 dex 尚未生成；此时
            // 在本进程中留下的类定义会持续到进程结束。若注册过程在补丁生效前访问了某个类，该类就会
            // 保持当时的结构，首次使用时可能出现找不到类或缺少字段的问题。之后的启动都从缓存开始，
            // 这也是重启曾经掩盖该问题的原因。在此处执行重启，可让首次启动与之后的启动保持一致。
            if (!restartIntoFreshProcess()) {
                record("warning    the handover to a fresh process could not be requested; this one "
                    + "continues with the loader the build returned");
            }
        }

        bridge.updateLoader(classLoader);
        host.updateLoader(classLoader);
        mountedLoader = classLoader;
        GameResources.mounted(classLoader);
        return true;
    }

    /**
     * 使用新进程替换当前进程，并挂载刚刚生成的 dex。
     *
     * <p>替换流程运行在 {@code :restart} 进程中，因为如果在当前进程显示替换界面，就会继承刚被构建流程
     * 判定为过期的类定义。它会等当前进程结束后再启动启动器，避免平台将启动请求交给仍在运行的旧进程。</p>
     *
     * @return 无法请求交接时返回 {@code false}，调用方据此继续运行
     */
    private boolean restartIntoFreshProcess() {
        // 若当前进程已用同一个键发起过交接，但仍然需要构建，说明交接未生效。再次尝试会造成重启循环，
        // 因此本次启动继续使用当前构建出的加载器。
        Path marker = dataDirectory.resolve(RESTART_KEY);
        String handedOver = readMarker(marker);
        if (cacheKey.equals(handedOver)) {
            record("warning    the previous handover for this cache key did not take; continuing in "
                + "this process");
            clearRestartMarker();
            return false;
        }
        record("restarting in a fresh process: the dex is built and the cache key is written");
        writeMarker(marker);
        if (gameStartRequested) { requestAutostart(); }
        int pid = android.os.Process.myPid();
        try {
            context.startActivity(new android.content.Intent(context, RestartActivity.class)
                .putExtra("rwml.oldPid", pid)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
        } catch (Throwable t) {
            HostLog.error("the handover to a fresh process could not be requested", t);
            return false;
        }
        android.os.Process.killProcess(pid);
        // 如果设备拒绝结束进程，当前进程仍会持有已被构建结果替换的类路径，而重启正是为了避免这种情况。
        // 在此等待可阻止游戏在该状态下启动；调用此方法的引导阶段不会恢复。主线程继续运行，因为显示一个
        // 永远无法绘制的界面，比按原样继续启动更糟。
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    /** 重建 dex 后写入的标记，使下一个进程继续启动，而不再等待。 */
    private static final String AUTOSTART = "autostart";

    /** 记录请求交接时使用的缓存键，避免交接未生效时重复尝试。 */
    private static final String RESTART_KEY = "restart-key";

    private void writeMarker(Path marker) {
        try {
            Files.createDirectories(marker.getParent());
            Files.write(marker, cacheKey.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            HostLog.error("the handover marker " + marker.getFileName() + " could not be written", e);
        }
    }

    private String readMarker(Path marker) {
        try {
            return Files.isRegularFile(marker)
                ? new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void clearRestartMarker() {
        try {
            Files.deleteIfExists(dataDirectory.resolve(RESTART_KEY));
        } catch (IOException | RuntimeException e) {
            HostLog.error("the handover marker could not be removed", e);
        }
    }

    /** 记录当前进程替换的启动流程应由下一个进程继续执行。 */
    private void requestAutostart() {
        writeMarker(dataDirectory.resolve(AUTOSTART));
    }

    /**
     * @return 被当前进程替换的启动流程是否请求继续运行；此值会在这里读取并消费
     *
     * <p>重建会结束发起它的启动流程，但玩家仍然要进入游戏，因此会记住触发构建的按键，避免玩家再次操作。</p>
     */
    public static boolean consumeAutostart(Context context) {
        File marker = new File(new File(new File(context.getFilesDir(), "rwml"), "data"), AUTOSTART);
        if (!marker.isFile()) {
            return false;
        }
        try {
            return marker.delete();
        } catch (RuntimeException e) {
            HostLog.error("the continuation marker could not be removed", e);
            return false;
        }
    }

    /** @return 磁盘上所有扩展和游戏归档对应的 dex，按加载顺序排列。 */
    private List<Path> collectDex(List<ExtensionDescriptor> enabled, List<Path> gameArchives) throws IOException {
        List<Path> dex = new ArrayList<>();
        for (ExtensionDescriptor descriptor : enabled) {
            dex.add(ensureExtensionArchive(descriptor));
        }
        for (Path archive : gameArchives) {
            dex.addAll(Dexer.dexFiles(gameDexDirectory(archive)));
        }
        return dex;
    }

    /**
     * @param base 要扩展的类加载器：首次使用应用类加载器，之后使用上一次调用返回的加载器
     * @return 拥有该 dex 的类加载器
     */
    private ClassLoader mount(ClassLoader base, List<Path> dex) throws IOException {
        if (dex.isEmpty()) {
            record("mounted    no dex file; the runtime keeps its own classes");
            return base;
        }
        List<Path> fresh = new ArrayList<>();
        for (Path path : dex) {
            if (!mountedDex.contains(path.toAbsolutePath().normalize())) { fresh.add(path); }
        }
        if (fresh.isEmpty()) { return base; }
        ClassLoader loader = DexClassPath.mount(fresh, rootDirectory.resolve("optimized"), base);
        for (Path path : fresh) { mountedDex.add(path.toAbsolutePath().normalize()); }
        record("mounted    " + dex.size() + " dex file(s) on "
            + (loader == base ? base.getClass().getName() : "a new " + loader.getClass().getName()));
        if (loader != AndroidBootstrap.class.getClassLoader()) {
            record("note       the app class loader refused the patched dex, so extensions live in "
                + "their own loader and code running in the app loader cannot see them");
        }
        return loader;
    }

    /**
     * 编译并挂载扩展、注册其 mixin，然后据此改写游戏。
     *
     * <p>任何 mixin 注册失败都会终止构建：若忽略它继续改写游戏，生成的 dex 会缺少相应补丁，而问题要到
     * 之后才会显现。</p>
     *
     * @return 拥有已挂载 dex 的类加载器
     */
    private ClassLoader build(List<ExtensionDescriptor> enabled, List<Path> gameArchives)
        throws IOException, ExtensionException {
        deleteRecursively(dexDirectory);
        patchedClasses = 0;

        List<Path> extensionDex = new ArrayList<>();
        List<ExtensionDescriptor> mounted = new ArrayList<>();
        for (ExtensionDescriptor descriptor : enabled) {
            Path output = extensionArchiveDirectory(descriptor).resolve("extension.jar");
            try {
                // 扩展到达时已完成编译：dexer 由应用持有。如果在每次安装扩展时都编译，就会把这部分开销
                // 转移到启动阶段。保留完整归档也让同一 DexClassLoader 能读取扩展中的 Compose 资源与本地库。
                extensionDex.add(ensureExtensionArchive(descriptor));
                mounted.add(descriptor);
                record("dex        " + descriptor.id() + "  carried  -> " + relative(output));
            } catch (IOException e) {
                throw new ExtensionException("Extension " + descriptor.id() + " could not be mounted",
                    e);
            }
        }
        ClassLoader loader = mount(classLoader, extensionDex);

        // mixin 会在自身签名中引用它要修补的游戏类型，运行时在定义 mixin 类时（即下方注册阶段）解析这些
        // 签名。此时游戏仍是 JAR，只有应用 mixin 后才会变成 dex，因此注册期间没有类加载器能提供游戏类型。
        // 替身 dex 用于填补这一空档：它包含名称、标志和继承层级都正确的类型，这些信息足以满足框架对
        // 非自身定义类型的要求。
        //
        // 替身 dex 使用独立的类加载器，绝不能放在游戏运行所用的加载器上。类只会定义一次，并在进程结束前
        // 一直保持已定义状态；如果注册期间由替身响应了某个游戏类型，那么整个进程都会继续将它视为替身。
        // 后续挂载的修补 dex 就会忽略 mixin 涉及的类，也就是所有关键类。注册完成后会丢弃隔离的加载器，
        // 替身类也随之释放。
        List<Path> stubDex = StubDex.build(gameArchives, rootDirectory.resolve("stub-dex"));
        Map<ExtensionDescriptor, List<String>> mixins = new LinkedHashMap<>();
        List<Path> extensionArchives = new ArrayList<>();
        List<String> mixinNames = new ArrayList<>();
        List<String> patchClassNames = new ArrayList<>();
        for (ExtensionDescriptor descriptor : mounted) {
            List<String> names = OfflineMixinBridge.mixinClassNames(descriptor.source());
            mixins.put(descriptor, names);
            extensionArchives.add(descriptor.source());
            mixinNames.addAll(names);
            try {
                patchClassNames.addAll(ArchiveClassPatches.readDeclarations(descriptor.source()).values());
            } catch (IOException failure) {
                recoverArchive(descriptor.source(), failure);
                throw failure;
            }
        }
        ClassLoader registration = loader;
        if (!stubDex.isEmpty()) {
            Set<String> standInNames = new LinkedHashSet<>(StubDex.classNames(gameArchives));
            List<String> registrationTypes = new ArrayList<>(mixinNames);
            registrationTypes.addAll(patchClassNames);
            standInNames.addAll(ExtensionClassClosure.localFirstClassNames(extensionArchives,
                registrationTypes));
            List<Path> isolated = new ArrayList<>(stubDex);
            isolated.addAll(extensionDex);
            registration = DexClassPath.mountIsolated(isolated,
                rootDirectory.resolve("optimized"), loader, standInNames);
            record("registered against a stand-in dex and extension type closure on a loader of "
                + "its own; the game's loader never carries one");
        }

        List<Path> classpath = new ArrayList<>(gameArchives);
        for (ExtensionDescriptor descriptor : mounted) {
            classpath.add(descriptor.source());
        }
        List<Path> gameDex = new ArrayList<>();
        try (JarResources resources = new JarResources(classpath, registration)) {
            // 通过资源加载器而不是 dex 加载器注册，是同一修复的另一部分。loadClass 会访问作为父加载器的
            // dex 加载器，并在那里找到扩展和替身类；getResourceAsStream 则优先由此处响应，从 JAR 中读取
            // 游戏的真实字节码。框架会通过后一条路径读取父类层级；如果缺少它，@Accessor 指定的继承字段
            // 就无法被发现。
            bridge.resetForOfflineRegistration(resources);
            // 应用自身也包含针对游戏类的 mixin，注册方式与扩展中的 mixin 相同。该类来自此加载器已能访问的
            // 应用 dex，因此无需为它额外挂载内容。
            List<Class<?>> carried = Arrays.<Class<?>>asList(
                AndroidSoundFactoryMixin.class, GraphicsEngineAndroidMixin.class, LocaleUiMixin.class,
                NetworkEngineNotificationMixin.class, ServerListNotifyMixin.class,
                MinimapHandlerMixin.class, MinimapHandlerQueuedPointsDrawMixin.class,
                io.github.rwml.android.mixin.MasterServerListLifecycleMixin.class,
                io.github.rwml.android.mixin.DirectStorageMixins.Access.class,
                io.github.rwml.android.mixin.DirectStorageMixins.Loader.class,
                io.github.rwml.android.mixin.DirectStorageMixins.Settings.class,
                io.github.rwml.android.mixin.DirectStorageMixins.Readers.class,
                io.github.rwml.android.mixin.DirectStorageMixins.Permissions.class);

            // 资源包替换的是游戏自身的资源文件；这些处理方法的注入点固定，因此由宿主随应用一起注册，
            // 不依赖任何扩展归档。它们在任何游戏类被改写之前登记。
            List<Class<?>> resourcePackMixins = Arrays.<Class<?>>asList(
                io.github.rwml.game.ResourcePackAssetMixin.class,
                ResourcePackImageMixin.class, ResourcePackSoundMixin.class, ResourcePackMusicMixin.class);

            for (Class<?> mixin : carried) {
                bridge.register(mixin);
                record("mixin      " + mixin.getName() + "  [app]");
            }

            // 资源包重定向与界面 mixin 同时注册，但它们只影响资源读取：目标类不匹配时关闭资源包，而不是中止启动。
            for (Class<?> mixin : resourcePackMixins) {
                try {
                    bridge.register(mixin);
                    record("mixin      " + mixin.getName() + "  [resource pack]");
                } catch (ExtensionException failure) {
                    record("skipped    " + mixin.getName() + ": resource packs are unavailable: "
                        + failure.getMessage());
                }
            }

            for (ExtensionDescriptor descriptor : mounted) {
                for (String mixin : mixins.get(descriptor)) {
                    record("mixin      " + mixin + "  [" + descriptor.id() + "]");
                }
                try { bridge.registerArchive(descriptor.source()); }
                catch (ExtensionException failure) {
                    recoverArchive(descriptor.source(), failure);
                    throw failure;
                }
            }

            if (gameArchives.isEmpty()) {
                record("no game classes staged; nothing to rewrite");
                return loader;
            }

            for (Path archive : gameArchives) {
                Path patched = dexDirectory.resolve("patched").resolve(baseName(archive) + ".jar");
                record("rewriting " + archive.getFileName());
                int changed = rewrite(archive, patched, bridge, resources);
                patchedClasses += changed;
                Path output = gameDexDirectory(archive);
                record("compiling " + archive.getFileName() + " to dex");
                gameDex.addAll(Dexer.toDex(patched, output));
                record("dexed      " + archive.getFileName() + "  -> " + relative(output)
                    + "  (" + changed + " class(es) patched)");
            }
        }
        if (patchedClasses == 0) {
            // 这里静默失败最容易被误认为成功：dex 已构建，游戏也启动了，但所有 mixin 都没有生效，因为它们
            // 引用的类名或方法名与归档中的名称不匹配。
            record("warning    no game class was changed; the registered mixins name classes this "
                + "archive does not carry, or none of their injections matched");
        }
        return mount(loader, gameDex);
    }

    /**
     * 将 {@code source} 中的所有类改写到 {@code target}，并移除桌面端需要、设备上不需要的 Android API
     * 捆绑副本。保留 {@code android/support/*}，因为这是游戏打包的库，其他类路径不会提供它。
     *
     * @throws ExtensionException 注册器无法应用于某个类时抛出
     */
    private int rewrite(Path source, Path target, MixinBridge bridge, ClassLoader resources)
        throws IOException, ExtensionException {
        Files.createDirectories(target.getParent());
        int changed = 0;
        int dropped = 0;
        try (JarFile in = new JarFile(source.toFile());
             OutputStream file = Files.newOutputStream(target);
             JarOutputStream out = new JarOutputStream(new BufferedOutputStream(file))) {
            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith("android/") && !name.startsWith("android/support/")) {
                    dropped++;
                    continue;
                }
                byte[] bytes;
                try (InputStream stream = in.getInputStream(entry)) {
                    bytes = JarResources.readFully(stream);
                }
                if (name.endsWith(".class")) {
                    String internalName = name.substring(0, name.length() - ".class".length());
                    byte[] rewritten = bytes;
                    try {
                        byte[] result = bridge.transform(internalName, bytes, resources);
                        if (result != null && result.length > 0) {
                            rewritten = result;
                        }
                    } catch (Throwable t) {
                        // 注册器无法处理某个类时应终止构建。若保留原样，生成的 dex 会有一个 mixin 未能生效，
                        // 游戏随后会在其他位置失败，却无法说明原因。
                        Path failed = this.bridge.failedArchive(t);
                        if (failed != null) { recoverArchive(failed, t); }
                        throw new ExtensionException("The mixins could not be applied to "
                            + internalName, t);
                    }
                    if (!Arrays.equals(rewritten, bytes)) {
                        changed++;
                    }
                    bytes = rewritten;
                }
                out.putNextEntry(new JarEntry(name));
                out.write(bytes);
                out.closeEntry();
            }
        }
        if (dropped > 0) {
            record("dropped    " + dropped + " bundled android/* class(es); the platform supplies "
                + "the framework");
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 缓存管理
    // ------------------------------------------------------------------

    /** @return 扩展未被禁用的归档，按文件名排序。 */
    private List<ExtensionDescriptor> enabledExtensions(List<Path> archives) {
        Set<String> disabled;
        try {
            disabled = ExtensionStateStore.open(dataDirectory).disabledIds();
        } catch (IOException e) {
            HostLog.error("the disabled flags could not be read", e);
            record("cannot read the disabled flags (" + e + "); treating every extension as enabled");
            disabled = Collections.emptySet();
        }
        List<ExtensionDescriptor> enabled = new ArrayList<>();
        for (Path archive : archives) {
            try {
                ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(archive);
                if (disabled.contains(descriptor.id())) {
                    record("disabled   " + descriptor.id());
                    continue;
                }
                enabled.add(descriptor);
            } catch (ExtensionException e) {
                HostLog.error("the descriptor of " + archive.getFileName() + " could not be read", e);
                record("unreadable " + archive.getFileName() + "  (" + e.getMessage() + ")");
            }
        }
        return enabled;
    }

    /**
     * 缓存键涵盖游戏类、所有待加载扩展的标识和内容、应用自身的构建版本，以及缓存格式。
     *
     * <p>应用自身的构建版本必须纳入缓存键，因为应用也带有 mixin，且这些 mixin 会被编译进此处生成的 dex。
     * 在旧版本上安装新版本不会修改游戏归档，因此只依据归档生成的缓存键不会变化，旧 dex 就会被复用：
     * 其中仍是旧版 mixin，新版本新增的补丁则会全部悄然缺失。此前使用手写常量记录版本；这种方式只有
     * 记得递增常量的人才能确保缓存更新，现在由该机制取代。</p>
     */
    private String computeKey(List<Path> gameArchives, List<ExtensionDescriptor> enabled) {
        StringBuilder key = new StringBuilder(CACHE_FORMAT).append('\n');
        key.append("app ").append(appIdentity()).append('\n');
        for (Path archive : gameArchives) {
            key.append("game ").append(archive.getFileName()).append(' ')
                .append(AssetInstaller.Digest.sha256(archive)).append('\n');
        }
        List<String> entries = new ArrayList<>();
        for (ExtensionDescriptor descriptor : enabled) {
            entries.add("ext " + descriptor.id() + ' ' + descriptor.version() + ' '
                + descriptor.sourceSha256() + '\n');
        }
        // 即使归档相同，注册或加载顺序变化也会改变最终补丁。
        for (String entry : entries) {
            key.append(entry);
        }
        return AssetInstaller.Digest.sha256(key.toString());
    }

    /**
     * @return 用于标识当前应用构建版本的值，每次安装都会变化
     */
    private String appIdentity() {
        try {
            PackageInfo info = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0);
            return info.versionCode + ":" + info.lastUpdateTime;
        } catch (Throwable t) {
            // 没有包信息意味着从未构建过可缓存内容，此时使用任意常量都可以作为版本标识。
            return "unidentified";
        }
    }

    /**
     * 只有当缓存键仍然准确描述 dex 的构建来源，且键中引用的每个目录都仍包含 dex 时，缓存才可复用。
     * 缓存键的检查成本最低，因此放在最后执行。
     */
    private boolean isCacheValid(List<ExtensionDescriptor> enabled, List<Path> gameArchives) {
        for (ExtensionDescriptor descriptor : enabled) {
            // 构建时会跳过不含 dex 的扩展，因此若在此处仍要求它提供 dex，每次启动都会因一个固定不变的原因
            // 而使缓存失效。
            if (Dexer.carriesDex(descriptor.source())
                && !ExtensionArchiveCache.isValid(extensionArchiveDirectory(descriptor),
                    descriptor.source(), descriptor.sourceSha256())) {
                return false;
            }
        }
        for (Path archive : gameArchives) {
            if (Dexer.dexFiles(gameDexDirectory(archive)).isEmpty()) {
                return false;
            }
        }
        if (enabled.isEmpty() && gameArchives.isEmpty()) {
            return false;
        }
        Path keyFile = dexDirectory.resolve(KEY_FILE);
        if (!Files.isRegularFile(keyFile)) {
            return false;
        }
        try {
            String recorded = new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8).trim();
            return recorded.equals(cacheKey);
        } catch (IOException e) {
            return false;
        }
    }

    private void writeKey() throws IOException {
        Files.createDirectories(dexDirectory);
        Files.write(dexDirectory.resolve(KEY_FILE), cacheKey.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // 报告
    // ------------------------------------------------------------------

    /** 将一行日志写入此对象提供的日志列表和 logcat。 */
    private void record(String line) {
        log.add(line);
        HostLog.add(line);
        preparationProgress.onProgress(0, line);
    }

    /** 按照 {@link #record} 的方式记录列表中的每一行。 */
    private void recordAll(List<String> lines) {
        for (String line : lines) {
            record(line);
        }
    }

    /**
     * 将构建报告保存在生成的 dex 旁边：复用 dex 的启动流程不会执行报告中描述的工作，否则这些信息只会
     * 显示一次，之后就再也不会出现。
     */
    private void writeBuildLog(List<String> build) throws IOException {
        Files.createDirectories(dexDirectory);
        StringBuilder text = new StringBuilder(PATCHED_PREFIX).append(patchedClasses).append('\n');
        for (String line : build) {
            text.append(line).append('\n');
        }
        Files.write(dexDirectory.resolve(BUILD_LOG), text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 将 {@link #writeBuildLog} 写入的内容读回当前运行日志。 */
    private void replayBuildLog() {
        Path file = dexDirectory.resolve(BUILD_LOG);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.startsWith(PATCHED_PREFIX)) {
                    try {
                        patchedClasses =
                            Integer.parseInt(line.substring(PATCHED_PREFIX.length()).trim());
                    } catch (NumberFormatException ignored) {
                        // 数量无法解析时保留为零，这与未编译任何内容的启动流程报告的数值相同。
                    }
                } else if (!line.trim().isEmpty()) {
                    record(line);
                }
            }
        } catch (IOException e) {
            HostLog.error("the report of the build that produced this dex cannot be read", e);
            record("the report of the build that produced this dex cannot be read (" + e + ")");
        }
    }

    // ------------------------------------------------------------------
    // 路径和辅助方法
    // ------------------------------------------------------------------

    private Path extensionArchiveDirectory(ExtensionDescriptor descriptor) {
        return rootDirectory.resolve("extension-dex").resolve(descriptor.sourceSha256());
    }

    private Path ensureExtensionArchive(ExtensionDescriptor descriptor) throws IOException {
        try {
            return ExtensionArchiveCache.install(extensionArchiveDirectory(descriptor),
                descriptor.source(), descriptor.sourceSha256());
        } catch (IOException failure) {
            recoverArchive(descriptor.source(), failure);
            throw failure;
        }
    }

    private void recoverArchive(Path archive, Throwable failure) {
        try {
            manager.quarantine(ExtensionDescriptorReader.read(archive).id(), failure);
            ui.hooks().publish(UiHooks.EXTENSIONS_CHANGED, null);
        } catch (Exception persistenceFailure) {
            failure.addSuppressed(persistenceFailure);
            HostLog.error("Cannot disable a failed extension", persistenceFailure);
        }
        showRecovery(failure);
    }

    private void showRecovery(Throwable failure) {
        if (!interfacePrepared || !recoveryShown.compareAndSet(false, true)) { return; }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
            context.startActivity(new android.content.Intent(context,
                io.github.rwml.android.builtin.LauncherActivity.class)
                .putExtra(io.github.rwml.android.builtin.LauncherActivity.EXTRA_ERROR,
                    android.util.Log.getStackTraceString(failure))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)));
    }

    private Path gameDexDirectory(Path archive) {
        return dexDirectory.resolve("classes").resolve(baseName(archive));
    }

    private String relative(Path path) {
        return rootDirectory.relativize(path).toString().replace(File.separatorChar, '/');
    }

    private static String baseName(Path archive) {
        String name = archive.getFileName().toString();
        return name.endsWith(".jar") ? name.substring(0, name.length() - ".jar".length()) : name;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            List<Path> paths = new ArrayList<>();
            walk.forEach(paths::add);
            Collections.reverse(paths);
            for (Path path : paths) {
                if (Files.isRegularFile(path)) {
                    // 此处的 dex 是只读的，并非所有系统都允许删除只读文件。清理缓存需要删除该文件，
                    // 因此必须能处理这一情况。
                    Dexer.makeWritable(path);
                }
                Files.deleteIfExists(path);
            }
        }
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
