/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import io.github.rwml.api.Extension;
import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.ExtensionState;
import io.github.rwml.api.GameAssets;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.api.ui.UiFramework;
import io.github.rwml.host.HostPlatform;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 负责发现、加载、启用和禁用扩展的中央注册表。
 *
 * <p>加载分为三个阶段：</p>
 * <ol>
 *   <li>扫描所有归档并读取描述信息；</li>
 *   <li>{@link ExtensionDependencyResolver} 根据已声明的扩展关系生成有效加载顺序，并移除所有无法满足依赖的扩展；</li>
 *   <li>按顺序实例化扩展。每个扩展都会获得宿主平台提供的独立类加载器；它先访问宿主，再访问其声明依赖的类加载器，
 *       因此依赖提供的共享 API 保留单一类身份，而未声明依赖的扩展仍相互隔离。</li>
 * </ol>
 *
 * <p>Java 版本要求需根据描述信息中声明的值和归档实际字节码级别进行协调。此检查属于宿主职责，因此由
 * {@link HostPlatform} 提供。</p>
 */
public final class ExtensionManager {

    private final Path rootDirectory;
    private final Path extensionDirectory;
    private final Path dataDirectory;
    private final ExtensionStateStore stateStore;
    private final HostPlatform platform;

    private final Map<String, ExtensionHandle> handles = new LinkedHashMap<>();

    /** 本次会话发现的所有扩展 ID，无论是否已加载。 */
    private final Set<String> knownIds = new LinkedHashSet<>();

    /** 本次会话发现的资源包 ID，用于在选择时校验。 */
    private final Set<String> knownResourcePacks = new LinkedHashSet<>();

    /** 当前物化的资源包；没有资源包生效时为 null。 */
    private volatile ResourcePackInstaller.Result resourcePack;

    /** 资源包物化失败的原因；用于让宿主在加载报告中显示，而不是悄悄回退到原版资源。 */
    private volatile String resourcePackFailure;

    /** 声明界面框架的扩展 ID 及解析得到的入口点。 */
    private String uiFrameworkId;
    private UiFramework uiFrameworkEntry;
    private String uiFrameworkReservation;
    private final Set<String> loadingIds = new LinkedHashSet<>();
    private final Map<String, String> recoveredFailures = new LinkedHashMap<>();
    private final Map<String, Throwable> recoveredCauses = new LinkedHashMap<>();
    private volatile boolean recoveryRequired;

    /** 加载失败时可能已注册部分 mixin；只有在新进程中启动才安全。 */
    public boolean recoveryRequired() { return recoveryRequired; }

    public Map<String, String> recoveredFailures() {
        synchronized (recoveredFailures) { return Collections.unmodifiableMap(new LinkedHashMap<>(recoveredFailures)); }
    }

    /** 保留回调和 mixin 的原始异常原因，供引导程序写入诊断日志。 */
    public ExtensionException recoveryFailure(String message) {
        ExtensionException result = new ExtensionException(message);
        synchronized (recoveredFailures) {
            for (Throwable failure : recoveredCauses.values()) { result.addSuppressed(failure); }
        }
        return result;
    }

    private static String failureReason(Throwable failure) {
        Throwable cause = failure;
        Set<Throwable> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (cause.getCause() != null && seen.add(cause)) { cause = cause.getCause(); }
        String reason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        return cause == failure ? reason : reason + ": " + cause;
    }

    /** 记录出错扩展并持久化其禁用标记，然后再将结果返回宿主。 */
    public void quarantine(String id, Throwable failure) throws ExtensionException {
        recoveryRequired = true;
        String reason = failureReason(failure);
        synchronized (recoveredFailures) {
            recoveredFailures.put(id, reason);
            recoveredCauses.put(id, failure);
        }
        synchronized (knownIds) { knownIds.add(id); }
        try { stateStore.setDisabled(id, true); }
        catch (IOException persistenceFailure) {
            throw new ExtensionException("Cannot persist the disabled flag for failed extension '" + id + "'", persistenceFailure);
        }
    }

    /**
     * 按照 {@code platform} 提供的方式管理扩展。
     *
     * <p>必须显式提供宿主，而不使用默认实现。JVM 和 Android 应用之间的所有差异都由宿主决定，核心模块不提供任何平台实现。</p>
     */
    public ExtensionManager(Path rootDirectory, HostPlatform platform) throws IOException {
        this.rootDirectory = rootDirectory.toAbsolutePath();
        this.extensionDirectory = this.rootDirectory.resolve("extensions");
        this.dataDirectory = this.rootDirectory.resolve("data");
        this.stateStore = ExtensionStateStore.open(dataDirectory);
        this.platform = platform;
    }

    /** @return 此管理器对应的宿主平台。 */
    public HostPlatform platform() {
        return platform;
    }

    public Path rootDirectory() {
        return rootDirectory;
    }

    public Path extensionDirectory() {
        return extensionDirectory;
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    /**
     * @return 当前生效的资源包物化结果；没有资源包生效时返回 null
     *
     * <p>该值在 {@code loadAll} 期间确定，早于任何扩展回调和游戏阶段，因此界面与游戏看到同一批资源。</p>
     */
    public ResourcePackInstaller.Result resourcePack() {
        return resourcePack;
    }

    /** @return 资源包物化失败的原因；设备仍以原版资源运行。返回 null 表示没有失败 */
    public String resourcePackFailure() {
        return resourcePackFailure;
    }

    /**
     * 选择下次启动使用的资源包。
     *
     * <p>同一时间只有一个资源包生效，因此此操作同时也就让其他资源包退出。资源在启动时物化，选择在下次启动生效，
     * 与禁用标记和版本选择一致。</p>
     *
     * @param id 资源包 ID；null 表示恢复为按加载顺序选择
     * @throws ExtensionException 未知 ID，或该扩展不是资源包时抛出
     */
    public void selectResourcePack(String id) throws ExtensionException {
        if (id != null) {
            synchronized (knownResourcePacks) {
                if (!knownResourcePacks.contains(id) && !knownIds.contains(id)) {
                    throw new ExtensionException("Unknown resource pack '" + id + "'");
                }
                if (!knownResourcePacks.contains(id)) {
                    throw new ExtensionException("Extension '" + id + "' is not a resource pack");
                }
            }
        }
        try {
            stateStore.setSelectedResourcePack(id);
        } catch (IOException e) {
            throw new ExtensionException("Unable to persist the resource pack selection", e);
        }
    }

    /** 启动和管理界面共用的状态，包括尚未加载的归档。 */
    ExtensionStateStore extensionStates() {
        return stateStore;
    }

    /** @return 扩展目录中找到的所有 {@code .jar} 文件，按名称排序。 */
    public List<Path> discover() throws IOException {
        Files.createDirectories(extensionDirectory);
        try (Stream<Path> stream = Files.list(extensionDirectory)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .sorted()
                .collect(Collectors.toList());
        }
    }

    /** @return 当前已加载所有句柄的不可变快照。 */
    public List<ExtensionHandle> handles() {
        synchronized (handles) {
            return Collections.unmodifiableList(new ArrayList<>(handles.values()));
        }
    }

    public ExtensionHandle handle(String id) {
        synchronized (handles) {
            return handles.get(id);
        }
    }

    public Set<String> extensionIds() {
        synchronized (handles) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(handles.keySet()));
        }
    }

    // ------------------------------------------------------------------
    // 用户界面
    // ------------------------------------------------------------------

    /**
     * 查找声明了用户界面的扩展。
     *
     * <p>由加载顺序决定：第一个在描述信息中声明 {@code ui-framework} 且可用的扩展胜出，其余扩展都会被忽略，因为一个进程
     * 只能运行一个界面。已禁用或加载失败的扩展不能胜出。</p>
     *
     * @return 声明界面的扩展 ID；没有扩展声明时返回 null，表示运行时处于无界面模式
     */
    public String uiFrameworkExtensionId() {
        // 无论加载顺序如何，用户扩展都优先于内置扩展：用户安装了自己的界面就应使用它，随包提供的界面仅作回退选项。
        String fallback = null;
        for (ExtensionHandle handle : handles()) {
            if (!handle.isUsable()) {
                continue;
            }
            String entry = handle.descriptor().uiFramework();
            if (entry == null || entry.trim().isEmpty()) {
                continue;
            }
            if (!handle.descriptor().isBuiltIn()) {
                return handle.descriptor().id();
            }
            if (fallback == null) {
                fallback = handle.descriptor().id();
            }
        }
        return fallback;
    }

    /**
     * 实例化已加载扩展声明的用户界面，只执行一次。
     *
     * <p>首先通过声明扩展自己的加载器解析入口，因此扩展可以携带自己的实现；若未找到，再通过宿主加载器解析，
     * 这样小型扩展也可以引用宿主提供的实现。实例会被缓存，因为宿主只启动一次界面，后续调用不得再次创建实例。</p>
     *
     * @return 界面入口点；没有扩展声明时返回 null
     * @throws ExtensionException 无法加载声明的入口、入口未实现 {@link UiFramework} 或无法实例化时抛出
     */
    public UiFramework uiFramework() throws ExtensionException {
        String id = uiFrameworkExtensionId();
        if (id == null) {
            return null;
        }
        synchronized (this) {
            if (id.equals(uiFrameworkId) && uiFrameworkEntry != null) {
                return uiFrameworkEntry;
            }
            ExtensionHandle handle = handle(id);
            String entry = handle.descriptor().uiFramework().trim();
            try {
                Class<?> type = loadUiFrameworkEntry(entry, handle);
                if (!UiFramework.class.isAssignableFrom(type)) {
                    throw new ExtensionException("Extension '" + id + "' declares ui-framework '" + entry
                        + "', which does not implement " + UiFramework.class.getName());
                }
                try {
                    uiFrameworkEntry = (UiFramework) type.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    throw new ExtensionException("Extension '" + id + "' declares ui-framework '" + entry
                        + "', which could not be instantiated: " + e, e);
                }
                uiFrameworkId = id;
                return uiFrameworkEntry;
            } catch (Throwable failure) {
                ExtensionException error = failure instanceof ExtensionException ? (ExtensionException) failure
                    : new ExtensionException("Interface of '" + id + "' failed", failure);
                try { quarantine(id, error); }
                catch (ExtensionException persistenceFailure) { error.addSuppressed(persistenceFailure); }
                throw error;
            }
        }
    }

    private Class<?> loadUiFrameworkEntry(String entry, ExtensionHandle handle)
        throws ExtensionException {
        try {
            return Class.forName(entry, true, handle.classLoader());
        } catch (ClassNotFoundException | LinkageError notInExtension) {
            try {
                return Class.forName(entry, true, ExtensionManager.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError notInHost) {
                throw new ExtensionException("Extension '" + handle.descriptor().id()
                    + "' declares ui-framework '" + entry
                    + "', which is in neither the extension nor the host", notInHost);
            }
        }
    }

    private void forgetUiFramework() {
        synchronized (this) {
            uiFrameworkId = null;
            uiFrameworkEntry = null;
        }
    }

    // ------------------------------------------------------------------
    // 批量加载
    // ------------------------------------------------------------------

    /**
     * 读取、排序并实例化所有提供的归档。
     *
     * <p>故障不会中止整个加载过程：无法读取的归档、依赖未解析的扩展、循环依赖中的扩展，以及实例化时发生运行时错误的扩展，
     * 都会汇总到返回报告中，其余扩展仍会正常加载。</p>
     */
    public synchronized LoadReport loadAll(List<Path> archives, ProgressListener listener) {
        return loadAll(archives, listener, false);
    }

    /** 完成界面提前加载流程，不重复执行回调或注册 mixin。 */
    public synchronized LoadReport loadRemaining(List<Path> archives, ProgressListener listener) {
        return loadAll(archives, listener, true);
    }

    private LoadReport loadAll(List<Path> archives, ProgressListener listener, boolean keepLoaded) {
        ProgressListener progress = listener == null ? ProgressListener.NOOP : listener;
        platform.mixins().install();

        if (!keepLoaded) {
            synchronized (handles) {
                for (ExtensionHandle handle : handles.values()) { closeQuietly(handle.classLoader()); }
                handles.clear();
            }
            forgetUiFramework();
            uiFrameworkReservation = null;
        }

        // ---------- 阶段 0：宿主自身携带的归档 ----------
        progress.onProgress(0, "Staging extensions supplied by the host");
        List<String> staged = stage(progress);

        // ---------- 阶段 1：读取描述信息 ----------
        progress.onProgress(1, "Scanning " + archives.size() + " archive(s)");
        List<ExtensionDescriptor> descriptors = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        List<String> quarantinedArchives = new ArrayList<>();

        int totalArchives = Math.max(1, archives.size());
        for (int i = 0; i < archives.size(); i++) {
            Path archive = archives.get(i);
            int percent = 1 + (int) (27.0 * i / totalArchives);
            progress.onProgress(percent, "Reading " + archive.getFileName());
            try {
                ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(archive);
                descriptors.add(descriptor);
            } catch (ExtensionException e) {
                try {
                    if (stateStore.isArchiveDisabled(archive)) {
                        quarantinedArchives.add(archive.getFileName().toString());
                        continue;
                    }
                    recoveryRequired = true;
                    stateStore.quarantineArchive(archive, e.getMessage());
                    synchronized (recoveredFailures) {
                        recoveredCauses.put(archive.getFileName().toString(), e);
                    }
                } catch (IOException persistenceFailure) { e.addSuppressed(persistenceFailure); }
                failures.put(archive.getFileName().toString(), e.getMessage());
            }
        }

        // ---------- 阶段 1b：宿主自身携带的扩展 ----------
        for (ExtensionDescriptor builtIn : platform.builtInExtensions()) {
            descriptors.add(builtIn);
            progress.onProgress(29, "Staged the built-in " + builtIn.name());
        }

        // ---------- 阶段 2：排列依赖顺序 ----------
        progress.onProgress(30, "Resolving extension load order");
        ExtensionSelection selection = ExtensionSelection.resolve(
            descriptors, stateStore.disabledIds(), stateStore.selectedVersions(),
            stateStore.selectedResourcePack());

        // 记录的资源包可能已被卸载。继续保留该键会让下次启动仍指向不存在的包，因此在确认目录中已无此 ID 时清除。
        String preferredPack = stateStore.selectedResourcePack();
        if (preferredPack != null && !namesResourcePack(descriptors, preferredPack)) {
            try {
                stateStore.setSelectedResourcePack(null);
            } catch (IOException ignored) {
                // 清理失败只影响下次启动的选择，不影响本次加载。
            }
        }

        if (keepLoaded) {
            String active = uiFrameworkExtensionId();
            String selected = selection.framework() == null ? null : selection.framework().id();
            if (!Objects.equals(active, selected)) {
                throw new IllegalStateException("UI selection changed; restart the host before loading remaining extensions");
            }
        }

        uiFrameworkReservation = selection.framework() == null ? null : selection.framework().id();
        for (Map.Entry<String, String> entry : selection.excluded().entrySet()) {
            if (!selection.incompatible().containsKey(entry.getKey())) {
                failures.put(entry.getKey(), entry.getValue());
            }
            progress.onProgress(30, "Skipped " + entry.getKey() + ": " + entry.getValue());
        }

        // ---------- 阶段 2b：物化资源包 ----------
        // 必须在任何扩展回调之前完成：扩展可能在 onLoad 中读取资源，游戏也可能在下一阶段立即加载纹理。
        progress.onProgress(32, "Preparing game resources");
        for (Map.Entry<String, String> skipped : selection.resourcePackExcluded().entrySet()) {
            progress.onProgress(32, "Skipped " + skipped.getKey() + ": " + skipped.getValue());
        }
        prepareResourcePack(selection.resourcePack(), progress);

        // ---------- 阶段 3：按顺序实例化 ----------
        List<ExtensionDescriptor> order = selection.enabled();
        List<ExtensionHandle> loaded = new ArrayList<>(order.size());
        List<String> disabled = new ArrayList<>(selection.disabled());
        disabled.addAll(quarantinedArchives);
        Set<String> disabledIds = stateStore.disabledIds();
        int totalToLoad = Math.max(1, order.size());

        for (ExtensionDescriptor descriptor : descriptors) {
            synchronized (knownIds) {
                knownIds.add(descriptor.id());
            }
            if (descriptor.resourcePack()) {
                synchronized (knownResourcePacks) {
                    knownResourcePacks.add(descriptor.id());
                }
            }
        }

        for (int i = 0; i < order.size(); i++) {
            ExtensionDescriptor descriptor = order.get(i);
            int percent = 34 + (int) (64.0 * i / totalToLoad);
            int span = Math.max(1, 64 / totalToLoad);
            progress.onProgress(percent, "Loading " + descriptor.name());

            String blockingDependency = firstFailedDependency(descriptor, failures);
            if (blockingDependency != null) {
                failures.put(descriptor.id(),
                    "Required extension '" + blockingDependency + "' did not load");
                continue;
            }

            String missingDependency = firstDisabledDependency(descriptor, disabledIds);
            if (missingDependency != null) {
                failures.put(descriptor.id(),
                    "Required extension '" + missingDependency + "' is disabled");
                continue;
            }

            if (disabledIds.contains(descriptor.id())) {
                disabled.add(descriptor.id());
                continue;
            }

            try {
                ExtensionHandle existing = handle(descriptor.id());
                if (existing != null) {
                    if (!existing.descriptor().sourceSha256().equals(descriptor.sourceSha256())) {
                        throw new ExtensionException("Archive changed after loading '" + descriptor.id()
                            + "'; restart the host before mounting its replacement");
                    }
                    loaded.add(existing);
                    continue;
                }
                ExtensionHandle handle = instantiate(descriptor, (pct, message) ->
                    progress.onProgress(percent + Math.min(span - 1, pct * span / 100), message));
                loaded.add(handle);
                synchronized (handles) {
                    handles.put(descriptor.id(), handle);
                }
            } catch (ExtensionException e) {
                failures.put(descriptor.id(), failureReason(e));
            }
        }

        progress.onProgress(100, loaded.size() + " loaded, " + disabled.size()
            + " disabled, " + (failures.size() + selection.incompatible().size()) + " skipped");
        ResourcePackInstaller.Result pack = resourcePack;
        return new LoadReport(loaded, failures, disabled, staged, pack == null ? null : pack.id(),
            selection.incompatible());
    }

    /**
     * 物化选定的资源包，或在前一次物化存在但没有资源包生效时清除它。
     *
     * <p>物化失败不会中止加载：游戏仍以原版资源启动，失败原因交由宿主显示。让整个启动失败会让玩家无法进入游戏，
     * 而资源包本身只是覆盖文件。</p>
     */
    private void prepareResourcePack(ExtensionDescriptor pack, ProgressListener progress) {
        if (pack == null) {
            try {
                ResourcePackInstaller.clear(platform.resourcePacks());
                resourcePackFailure = null;
            } catch (IOException e) {
                resourcePackFailure = "clearing the previous resource pack failed: " + e;
                progress.onProgress(32, resourcePackFailure);
            }
            resourcePack = null;
            return;
        }
        try {
            ResourcePackInstaller.Result result = ResourcePackInstaller.install(platform.resourcePacks(), pack);
            resourcePack = result;
            resourcePackFailure = null;
            progress.onProgress(33, result.reused()
                ? "Reused the resources of " + result.id()
                : "Materialised " + result.entries() + " resource file(s) of " + result.id());
        } catch (IOException e) {
            resourcePack = null;
            resourcePackFailure = "resource pack '" + pack.id() + "' could not be prepared: " + e.getMessage();
            progress.onProgress(33, resourcePackFailure);
        }
    }

    /** @return 目录中是否有该 ID 的资源包 */
    private static boolean namesResourcePack(List<ExtensionDescriptor> descriptors, String id) {
        for (ExtensionDescriptor descriptor : descriptors) {
            if (descriptor.resourcePack() && descriptor.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 允许宿主将自身打包的归档放入扩展目录。
     *
     * <p>此处的故障会被报告，但不会进一步处理：资源不可读不应阻止已安装扩展的加载。</p>
     */
    private List<String> stage(ProgressListener progress) {
        try {
            List<String> staged = platform.stageArchives(extensionDirectory);
            for (String line : staged) {
                progress.onProgress(0, line);
            }
            return staged;
        } catch (IOException e) {
            progress.onProgress(0, "Staging failed: " + e);
            return Collections.singletonList("staging failed: " + e);
        }
    }

    // ------------------------------------------------------------------
    // 单个扩展
    // ------------------------------------------------------------------

    /** 加载磁盘上已发现的一个归档。 */
    public synchronized ExtensionHandle load(Path archive, ProgressListener listener) throws ExtensionException {
        ProgressListener progress = listener == null ? ProgressListener.NOOP : listener;
        platform.mixins().install();
        progress.onProgress(3, "Reading descriptor of " + archive.getFileName());
        ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(archive);

        synchronized (knownIds) {
            knownIds.add(descriptor.id());
        }

        if (stateStore.isDisabled(descriptor.id())) {
            throw new ExtensionException("Extension '" + descriptor.id()
                + "' is disabled and will not be loaded");
        }
        String incompatibility = RwmlVersion.incompatibility(descriptor);
        if (incompatibility != null) {
            throw new ExtensionException("Extension '" + descriptor.id() + "': " + incompatibility);
        }

        String selectedVersion = stateStore.selectedVersions().get(descriptor.id());
        if (selectedVersion != null && !selectedVersion.equals(ExtensionVersions.identity(descriptor))) {
            throw new ExtensionException("A different version is selected for '" + descriptor.id()
                + "'; select this archive and restart before loading it");
        }
        synchronized (handles) {
            ExtensionHandle existing = handles.get(descriptor.id());
            if (existing != null) {
                throw new ExtensionException("Duplicate extension id '" + descriptor.id()
                    + "' (already provided by " + existing.descriptor().source()
                    + ", new source " + archive + ")");
            }
        }
        if (loadingIds.contains(descriptor.id())) {
            throw new ExtensionException("Extension is already being loaded: " + descriptor.id());
        }
        if (ExtensionSelection.isFramework(descriptor)) {
            String active = uiFrameworkReservation == null ? uiFrameworkExtensionId() : uiFrameworkReservation;
            if (active != null && !active.equals(descriptor.id())) {
                throw new ExtensionException("UI framework already owned by '" + active
                    + "'; restart to select a different framework");
            }
        }
        progress.onProgress(12, "Ordering " + descriptor.id());

        // 加载单个扩展时仍会根据当前已加载扩展验证硬性依赖与版本要求，避免产生不完整的依赖关系图。
        synchronized (handles) {
            for (ExtensionRequirement requirement : descriptor.requirements()) {
                ExtensionHandle dependency = handles.get(requirement.id());
                if (dependency == null) {
                    throw new ExtensionException("Extension '" + descriptor.id()
                        + "' requires '" + requirement.id() + "', which is not loaded");
                }
                String loadedVersion = dependency.descriptor().version();
                if (!requirement.satisfiedBy(loadedVersion)) {
                    throw new ExtensionException("Extension '" + descriptor.id() + "' requires "
                        + requirement.text() + ", but " + requirement.id() + " " + loadedVersion + " is loaded");
                }
            }
        }

        if (ExtensionSelection.isFramework(descriptor)) {
            String active = uiFrameworkReservation == null ? uiFrameworkExtensionId() : uiFrameworkReservation;
            if (active != null && !active.equals(descriptor.id())) {
                throw new ExtensionException("UI framework already owned by '" + active + "'");
            }
            uiFrameworkReservation = descriptor.id();
        }
        if (descriptor.resourcePack()) {
            synchronized (knownResourcePacks) {
                knownResourcePacks.add(descriptor.id());
            }
            if (resourcePack != null && !resourcePack.id().equals(descriptor.id())) {
                throw new ExtensionException("Resource pack '" + resourcePack.id()
                    + "' is already active; only one resource pack can be active at a time");
            }
            prepareResourcePack(descriptor, progress);
            if (resourcePack == null) {
                throw new ExtensionException(resourcePackFailure == null
                    ? "Resource pack '" + descriptor.id() + "' could not be prepared"
                    : resourcePackFailure);
            }
        }
        ExtensionHandle handle = instantiate(descriptor, progress);
        synchronized (handles) {
            handles.put(descriptor.id(), handle);
        }
        progress.onProgress(100, "Loaded " + descriptor.name() + " " + descriptor.version());
        return handle;
    }

    /** 批量加载和单个扩展加载共用的实例化流程。 */
    private ExtensionHandle instantiate(ExtensionDescriptor descriptor, ProgressListener progress)
        throws ExtensionException {
        if (!loadingIds.add(descriptor.id())) {
            throw new ExtensionException("Extension is already being loaded: " + descriptor.id());
        }
        try { return instantiateNew(descriptor, progress); }
        catch (Throwable failure) {
            ExtensionException error = failure instanceof ExtensionException ? (ExtensionException) failure
                : new ExtensionException("Loading extension '" + descriptor.id() + "' failed", failure);
            try { quarantine(descriptor.id(), error); }
            catch (ExtensionException persistenceFailure) { error.addSuppressed(persistenceFailure); }
            throw error;
        } finally { loadingIds.remove(descriptor.id()); }
    }

    private ExtensionHandle instantiateNew(ExtensionDescriptor descriptor, ProgressListener progress)
        throws ExtensionException {
        // 内置扩展没有待检查的归档：其字节码已在宿主自身类路径中，因此无需协调 Java 版本要求或打开类加载器。
        descriptor = descriptor.isBuiltIn()
            ? descriptor
            : platform.reconcileJavaRequirement(descriptor, progress);

        progress.onProgress(60, "Creating the class loader of " + descriptor.id());
        ClassLoader classLoader;
        if (descriptor.isBuiltIn()) {
            classLoader = ExtensionManager.class.getClassLoader();
        } else {
            try {
                classLoader = platform.createExtensionLoader(descriptor, dependencyParent(descriptor));
            } catch (IOException e) {
                throw new ExtensionException("Unable to open " + descriptor.source().getFileName(), e);
            }
        }

        try {
            // 在初始化入口类之前注册 mixin：静态初始化和 onLoad 都可能使用扩展要修补的类。
            // 内置扩展没有待扫描的归档；其 mixin 已在宿主类路径中，并由宿主负责注册。
            if (!descriptor.isBuiltIn()) {
                platform.mixins().registerArchive(descriptor.source());
            }

            progress.onProgress(72, "Instantiating " + descriptor.name());
            Extension instance;
            if (descriptor.mainClass() == null || descriptor.mainClass().trim().isEmpty()) {
                // 资源包只提供资源，通常没有入口点。空实现让它与其他扩展一样拥有句柄、状态与卸载路径。
                if (!descriptor.resourcePack()) {
                    throw new ExtensionException("Extension '" + descriptor.id() + "' declares no entry point");
                }
                instance = new Extension() { };
            } else {
                Class<?> type;
                try {
                    type = Class.forName(descriptor.mainClass(), true, classLoader);
                } catch (ClassNotFoundException | LinkageError e) {
                    throw new ExtensionException("Entry point " + descriptor.mainClass()
                        + " could not be loaded: " + e, e);
                }
                if (!Extension.class.isAssignableFrom(type)) {
                    throw new ExtensionException("Entry point " + descriptor.mainClass()
                        + " does not implement " + Extension.class.getName());
                }

                try {
                    instance = (Extension) type.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    throw new ExtensionException("Constructor of " + descriptor.mainClass() + " failed", e);
                }
            }

            ExtensionHandle handle = new ExtensionHandle(descriptor, instance, classLoader);
            ExtensionContextImpl context;
            try {
                context = new ExtensionContextImpl(
                    descriptor.id(), dataDirectory.resolve(descriptor.id()), descriptor.source(), classLoader,
                    platform.mixins(), gameAssets());
            } catch (IOException e) {
                throw new ExtensionException("Unable to prepare the data directory for " + descriptor.id(), e);
            }
            handle.attachContext(context);

            progress.onProgress(85, "Running onLoad for " + descriptor.id());
            try {
                instance.onLoad(context);
            } catch (Throwable e) {
                context.closeResources();
                throw new ExtensionException("onLoad of '" + descriptor.id() + "' failed", e);
            }

            handle.setState(ExtensionState.ENABLED);
            return handle;
        } catch (Throwable failure) {
            if (!descriptor.isBuiltIn()) { closeQuietly(classLoader); }
            if (failure instanceof ExtensionException) { throw (ExtensionException) failure; }
            throw new ExtensionException("Loading extension '" + descriptor.id() + "' failed", failure);
        }
    }

    /**
     * @return 扩展读取游戏资源时使用的视图：资源包生效时先查包内文件，其余路径回退到宿主
     */
    private GameAssets gameAssets() {
        GameAssets base = platform.gameAssets();
        ResourcePackInstaller.Result pack = resourcePack;
        return pack == null ? base : new LayeredGameAssets(pack.directory(), base);
    }

    /** Builds a host-first parent that delegates classes and resources to declared dependency owners. */    private ClassLoader dependencyParent(ExtensionDescriptor descriptor) throws ExtensionException {
        List<ClassLoader> dependencies = new ArrayList<>();
        synchronized (handles) {
            for (ExtensionRequirement requirement : descriptor.requirements()) {
                ExtensionHandle dependency = handles.get(requirement.id());
                if (dependency == null) {
                    throw new ExtensionException("Extension '" + descriptor.id()
                        + "' requires '" + requirement.id() + "', which is not loaded");
                }
                // 版本已在选择阶段协调过；此处再核对一次，防止在依赖被替换后继续挂载消费者。
                if (!requirement.satisfiedBy(dependency.descriptor().version())) {
                    throw new ExtensionException("Extension '" + descriptor.id() + "' requires "
                        + requirement.text() + ", but " + requirement.id() + " "
                        + dependency.descriptor().version() + " is loaded");
                }
                dependencies.add(dependency.classLoader());
            }
        }
        return new ExtensionDependencyClassLoader(ExtensionManager.class.getClassLoader(), dependencies);
    }

    /** @return {@code descriptor} 中第一个失败的硬性依赖；若没有则返回 {@code null}。 */
    private static String firstFailedDependency(ExtensionDescriptor descriptor, Map<String, String> failures) {
        for (String dependency : descriptor.dependencyIds()) {
            if (failures.containsKey(dependency)) {
                return dependency;
            }
        }
        return null;
    }

    private static String firstDisabledDependency(ExtensionDescriptor descriptor, Set<String> disabled) {
        for (String dependency : descriptor.dependencyIds()) {
            if (disabled.contains(dependency)) {
                return dependency;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 启用或禁用（状态会持久化，并在下次启动时生效）
    // ------------------------------------------------------------------

    /**
     * 将扩展标记为禁用。禁用操作不会影响当前运行中的实例：扩展会继续运行，直到宿主重启；重启后则不会加载该扩展。
     */
    public void disable(String id) throws ExtensionException {
        requireKnown(id);
        try {
            stateStore.setDisabled(id, true);
        } catch (IOException e) {
            throw new ExtensionException("Unable to persist disabled state for '" + id + "'", e);
        }
    }

    /**
     * 清除禁用标记，使扩展在下次启动时重新加载。
     */
    public void enable(String id) throws ExtensionException {
        requireKnown(id);
        try {
            stateStore.setDisabled(id, false);
        } catch (IOException e) {
            throw new ExtensionException("Unable to persist enabled state for '" + id + "'", e);
        }
    }

    /** @return 扩展是否已持久化为禁用状态。 */
    public boolean isDisabled(String id) {
        synchronized (knownIds) {
            return knownIds.contains(id) && stateStore.isDisabled(id);
        }
    }

    private void requireKnown(String id) throws ExtensionException {
        synchronized (knownIds) {
            if (!knownIds.contains(id)) {
                throw new ExtensionException("Unknown extension id '" + id + "'");
            }
        }
    }

    // ------------------------------------------------------------------
    // 卸载
    // ------------------------------------------------------------------

    /** 卸载当前已加载的扩展并清除其状态，释放对应类加载器。 */
    public synchronized void unload(String id) throws ExtensionException {
        ExtensionHandle handle = require(id);
        synchronized (handles) {
            for (ExtensionHandle consumer : handles.values()) {
                if (!consumer.descriptor().id().equals(id)
                    && consumer.descriptor().dependencyIds().contains(id)) {
                    throw new ExtensionException("Cannot unload '" + id + "' while loaded extension '"
                        + consumer.descriptor().id() + "' depends on it; unload the consumer first");
                }
            }
        }
        handle.close();
        synchronized (handles) {
            handles.remove(id);
        }
        // 目录留给下次物化复用或清除：关闭进程时删除数万个文件没有收益，且会让正在读取的资源突然消失。
        ResourcePackInstaller.Result pack = resourcePack;
        if (pack != null && pack.id().equals(id)) { resourcePack = null; }
        forgetUiFramework();
        if (id.equals(uiFrameworkReservation)) { uiFrameworkReservation = null; }
    }

    /** 按加载顺序的逆序关闭所有已加载扩展，并释放类加载器。 */
    public synchronized void shutdown() {
        List<String> ids;
        synchronized (handles) {
            ids = new ArrayList<>(handles.keySet());
        }
        // 按加载顺序的逆序卸载，使依赖方先退出。
        for (int i = ids.size() - 1; i >= 0; i--) {
            try {
                unload(ids.get(i));
            } catch (ExtensionException ignored) {
                // 关闭过程不得中途终止。
            }
        }
    }

    private ExtensionHandle require(String id) throws ExtensionException {
        ExtensionHandle handle = handle(id);
        if (handle == null) {
            throw new ExtensionException("Unknown extension id '" + id + "'");
        }
        return handle;
    }

    /**
     * 若类加载器支持释放，则将其关闭。{@code URLClassLoader} 会保持 jar 文件打开，因此必须关闭；宿主基于 dex 构建的
     * 加载器则没有需要释放的资源。
     */
    private static void closeQuietly(ClassLoader loader) {
        if (!(loader instanceof Closeable)) {
            return;
        }
        try {
            ((Closeable) loader).close();
        } catch (IOException ignored) {
            // 忽略。
        }
    }
}
