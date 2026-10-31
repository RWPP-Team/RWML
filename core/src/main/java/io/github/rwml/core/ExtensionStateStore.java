/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * 持久化每个扩展的启用或禁用选择，使其在宿主重启后仍然有效。
 *
 * <p>默认启用所有扩展；宿主下次启动时只跳过此处记录的 ID。此存储是禁用标记的唯一可信来源，并在每次变更时原子地重写
 * 后端文件，因此写入过程中发生崩溃也不会留下半写入的文件。</p>
 *
 * <p>需要在加载扩展前准备内容的宿主也会读取同一存储，因此其选择与 {@link ExtensionManager} 的选择一致。</p>
 */
public final class ExtensionStateStore {

    private static final String FILE_NAME = "extension-state.properties";
    private static final String DISABLED_KEY = "disabled";
    private static final String RESOURCE_PACK_KEY = "resourcePack";

    private final Path file;
    private final Map<String, String> disabledArchives = new LinkedHashMap<>();
    private final Map<String, String> selectedVersions = new LinkedHashMap<>();
    private final Set<String> disabled = new LinkedHashSet<>();

    /** 上次选择的资源包；null 表示由加载顺序决定。 */
    private String resourcePack;

    private ExtensionStateStore(Path file) {
        this.file = file;
    }

    /** 打开以 {@code dataDirectory} 为根目录的存储；目录不存在时会创建。 */
    public static ExtensionStateStore open(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        ExtensionStateStore store = new ExtensionStateStore(dataDirectory.resolve(FILE_NAME));
        store.read();
        return store;
    }

    /** @return {@code id} 是否已持久化为禁用状态。 */
    public synchronized boolean isDisabled(String id) {
        return disabled.contains(id);
    }

    /** @return 已持久化为禁用状态的所有 ID 的不可变快照。 */
    public synchronized Set<String> disabledIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(disabled));
    }

    /** 持久化 {@code id} 的禁用标记；此操作不会触发其他行为。 */
    public synchronized void setDisabled(String id, boolean disable) throws IOException {
        boolean changed = disable ? disabled.add(id) : disabled.remove(id);
        if (changed) {
            try {
                write();
            } catch (IOException failure) {
                if (disable) { disabled.remove(id); }
                else { disabled.add(id); }
                throw failure;
            }
        }
    }

    /** 内容标识在 Android 暂存过程中保持不变，且不依赖归档文件名。 */
    public synchronized Map<String, String> selectedVersions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(selectedVersions));
    }

    /** @return 上次选择的资源包 ID；未选择时返回 null，由加载顺序决定 */
    public synchronized String selectedResourcePack() {
        return resourcePack;
    }

    /**
     * 持久化资源包选择。
     *
     * <p>资源包在启动时物化，因此选择在下次启动才生效，与禁用标记和版本选择相同。</p>
     *
     * @param id 资源包 ID；null 表示恢复为按加载顺序选择
     */
    public synchronized void setSelectedResourcePack(String id) throws IOException {
        String previous = resourcePack;
        if (Objects.equals(previous, id)) { return; }
        resourcePack = id;
        try {
            write();
        } catch (IOException failure) {
            resourcePack = previous;
            throw failure;
        }
    }

    public synchronized void setSelectedVersion(String id, String identity) throws IOException {
        String previous = selectedVersions.get(id);
        if (Objects.equals(previous, identity)) { return; }
        if (identity == null) { selectedVersions.remove(id); }
        else { selectedVersions.put(id, identity); }
        try {
            write();
        } catch (IOException failure) {
            if (previous == null) { selectedVersions.remove(id); }
            else { selectedVersions.put(id, previous); }
            throw failure;
        }
    }

    /** 无法读取的描述信息没有 ID；根据归档内容将其隔离，但不重命名或删除文件。 */
    public synchronized boolean isArchiveDisabled(Path archive) throws IOException {
        return !disabledArchives.isEmpty() && disabledArchives.containsKey(archiveIdentity(archive));
    }

    public synchronized void quarantineArchive(Path archive, String reason) throws IOException {
        String identity = archiveIdentity(archive);
        String previous = disabledArchives.put(identity, reason);
        try { write(); }
        catch (IOException failure) {
            if (previous == null) { disabledArchives.remove(identity); }
            else { disabledArchives.put(identity, previous); }
            throw failure;
        }
    }

    private static String archiveIdentity(Path archive) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(archive)) {
                byte[] bytes = new byte[8192];
                int count;
                while ((count = input.read(bytes)) != -1) { digest.update(bytes, 0, count); }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(Character.forDigit((value >> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private void read() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(file)) {
            properties.load(stream);
        } catch (IOException | IllegalArgumentException e) {
            // 存储损坏不应阻止宿主启动；从空状态开始。
            return;
        }
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("disabledArchive.")) {
                disabledArchives.put(key.substring("disabledArchive.".length()), properties.getProperty(key));
            }
            if (key.startsWith("selected.")) {
                selectedVersions.put(key.substring("selected.".length()), properties.getProperty(key));
            }
        }
        String raw = properties.getProperty(DISABLED_KEY, "");
        for (String part : raw.split("[,;\\s]+")) {
            String id = part.trim();
            if (!id.isEmpty()) {
                disabled.add(id);
            }
        }
        String pack = properties.getProperty(RESOURCE_PACK_KEY, "").trim();
        resourcePack = pack.isEmpty() ? null : pack;
    }

    private void write() throws IOException {
        Properties properties = new Properties();
        properties.setProperty(DISABLED_KEY, String.join(",", disabled));
        if (resourcePack != null && !resourcePack.trim().isEmpty()) {
            properties.setProperty(RESOURCE_PACK_KEY, resourcePack);
        }
        for (Map.Entry<String, String> entry : selectedVersions.entrySet()) {
            properties.setProperty("selected." + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : disabledArchives.entrySet()) {
            properties.setProperty("disabledArchive." + entry.getKey(), entry.getValue());
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream stream = Files.newOutputStream(temp)) {
            properties.store(stream, "Persisted extension state and selected archive identities");
        }
        // 安全地在同一文件系统中替换；失败时回退为普通移动。
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
