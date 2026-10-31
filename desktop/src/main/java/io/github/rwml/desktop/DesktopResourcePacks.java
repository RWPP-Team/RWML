/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.desktop;

import io.github.rwml.game.ResourcePackAssets;
import io.github.rwml.host.ResourcePackEnvironment;

import java.nio.file.Path;

/**
 * 桌面端的资源包环境。
 *
 * <p>物化目录放在 {@code data/resource-pack} 下：它是安装的一部分，与扩展状态并列，删除游戏目录时一并清理，也不会与
 * 玩家自己的模组混淆。目录只保存资源包提供的文件，其余资源仍由游戏从自己的目录读取。</p>
 */
final class DesktopResourcePacks implements ResourcePackEnvironment {

    private final Path directory;

    DesktopResourcePacks(Path gameDirectory) {
        this.directory = gameDirectory.resolve("data").resolve("resource-pack");
    }

    @Override
    public boolean supported() {
        return true;
    }

    @Override
    public Path directory() {
        return directory;
    }

    @Override
    public void activated(Path active) {
        ResourcePackAssets.use(active);
    }
}
