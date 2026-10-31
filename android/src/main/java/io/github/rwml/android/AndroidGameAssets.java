/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.content.res.AssetManager;

import io.github.rwml.api.GameAssets;

import java.io.IOException;
import java.io.InputStream;

/**
 * APK 中打包的游戏资源。
 *
 * <p>这些资源没有可供返回的文件路径：它们位于安装包中，由平台通过 {@link AssetManager} 读取，
 * 调用方只能通过名称访问。这就是扩展 API 提供字节数据，而不是目录路径的原因。</p>
 */
final class AndroidGameAssets implements GameAssets {

    private final AssetManager assets;

    AndroidGameAssets(AssetManager assets) {
        this.assets = assets;
    }

    @Override
    public boolean exists(String path) {
        try (InputStream ignored = assets.open(path)) {
            return true;
        } catch (IOException missing) {
            return false;
        }
    }

    @Override
    public InputStream open(String path) throws IOException {
        return assets.open(path);
    }
}
