/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import io.github.rwml.api.GameAssets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 先查找生效的资源包，再回退到宿主提供的资源。
 *
 * <p>扩展通过 {@link GameAssets} 读取游戏资源。资源包生效后，游戏读取的也是包内文件，扩展也应看到同一批文件，否则
 * 同一张纹理在两处会不一样。未命中的路径仍交给宿主，使包只需要携带它替换的文件。</p>
 */
final class LayeredGameAssets implements GameAssets {

    private final Path root;
    private final GameAssets fallback;

    LayeredGameAssets(Path root, GameAssets fallback) {
        this.root = root;
        this.fallback = fallback == null ? GameAssets.none() : fallback;
    }

    @Override
    public boolean exists(String path) {
        return resolve(path) != null || fallback.exists(path);
    }

    @Override
    public InputStream open(String path) throws IOException {
        Path file = resolve(path);
        if (file != null) {
            return Files.newInputStream(file);
        }
        return fallback.open(path);
    }

    /** @return 资源包中对应的文件；未提供或路径越界时返回 null */
    private Path resolve(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String relative = path.replace('\\', '/');
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (relative.startsWith("assets/")) {
            relative = relative.substring("assets/".length());
        }
        if (relative.isEmpty()) {
            return null;
        }
        try {
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root)) {
                return null;
            }
            return Files.isRegularFile(file) ? file : null;
        } catch (RuntimeException invalidPath) {
            // Windows 上非法字符会在此抛出；回退到宿主实现即可。
            return null;
        }
    }

    /** @return 供诊断使用的说明 */
    @Override
    public String toString() {
        return "LayeredGameAssets{" + root + " -> " + fallback + "}";
    }
}
