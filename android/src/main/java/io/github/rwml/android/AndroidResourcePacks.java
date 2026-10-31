/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import io.github.rwml.game.ResourcePackAssets;
import io.github.rwml.host.ResourcePackEnvironment;

import java.nio.file.Path;

/**
 * Android 的资源包环境。
 *
 * <p>物化目录只保存资源包提供的文件，因此不需要从安装包中复制任何原版资源：{@code gui}、{@code units} 等路径由文件
 * 加载器在包内未命中时回退到安装包，{@code res} 则由平台的资源表按 ID 提供。</p>
 */
final class AndroidResourcePacks implements ResourcePackEnvironment {

    private final Path directory;

    AndroidResourcePacks(Path directory) {
        this.directory = directory;
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
        ResourcePackResources.use(active);
    }
}
