/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.extensions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/** 管理归档时不会执行扩展代码；更改会在新的宿主进程中生效。 */
public interface ExtensionLibrary {
    Path directory();
    List<ExtensionEntry> entries() throws IOException;
    /** 启用状态针对扩展 ID 设置；同一 ID 最多加载一个版本。 */
    void setEnabled(String id, boolean enabled) throws IOException;
    /** 为下一个进程选择归档，并保留该 ID 的启用状态。 */
    default void selectVersion(Path archive) throws IOException { throw new UnsupportedOperationException(); }
    /**
     * 为下一个进程选择资源包。
     *
     * <p>资源包替换同一批游戏资源，因此同一时间只有一个生效；选择新的资源包也就让原来的退出。与版本选择一样，
     * 更改在新的宿主进程中生效。</p>
     *
     * @param id 资源包 ID；null 表示恢复为按加载顺序选择
     */
    default void selectResourcePack(String id) throws IOException {
        throw new UnsupportedOperationException();
    }
    /** @return 下次启动使用的资源包；未选择时返回 null */
    default String selectedResourcePack() { return null; }
    /** 在刷新目录时缓存；宿主可据此在启动器中保留尚未解决的选择。 */
    default boolean versionSelectionRequired() { return false; }
    ExtensionEntry importArchive(InputStream input, boolean replace) throws IOException;
    /**
     * 导入一个包含多个扩展的 zip 整合包。
     *
     * <p>包内每个带 {@code META-INF/extension.properties} 的 jar 都按 {@link #importArchive} 的规则安装。较新版本会自动
     * 替换同 ID 的受管理归档；正在运行的归档保留到重启，启动前始终为每个 ID 选出一个版本。不带说明文件的成员被跳过。</p>
     *
     * @param input 压缩包内容
     * @param replace 是否允许替换内容不同的同一版本或显式降级
     * @return 每个成员的导入结果
     */
    default ExtensionPackReport importPack(InputStream input, boolean replace) throws IOException {
        throw new UnsupportedOperationException("This host cannot import extension packs");
    }
    void remove(String id) throws IOException;
    /** 也可根据目录路径移除格式错误或重复的归档文件。 */
    void removeArchive(Path archive) throws IOException;
    boolean restartRequired();
}
