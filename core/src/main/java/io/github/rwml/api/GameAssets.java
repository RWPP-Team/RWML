/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api;

import java.io.IOException;
import java.io.InputStream;

/**
 * 游戏自身资源目录的只读访问接口。
 *
 * <p>扩展无法自行判断资源的存放位置。桌面版将资源保存在游戏目录下，因此可通过文件路径打开；Android 应用将资源放在
 * APK 内，只能通过平台的资源管理器读取，并没有对应文件路径。此接口统一了两种访问方式，扩展需要地图或图块集时应通过此处
 * 读取，而不应假设资源位于目录中。</p>
 *
 * <p>资源名称沿用游戏自身的命名，并以资源根目录为相对路径，例如 {@code maps/skirmish/[p2]Example.tmx}。
 * 宿主不提供可读资源目录时，可返回 {@link #none()}；调用其 {@link #open} 会抛出 {@link IOException}，
 * 而不是因方法缺失而失败，扩展因此可以回退到自身携带的资源。</p>
 */
public interface GameAssets {

    /** @return {@code path} 在资源根目录下是否存在 */
    boolean exists(String path);

    /**
     * @param path 资源名称，相对于资源根目录
     * @return 资源字节内容
     * @throws IOException 资源不存在或不可读取时抛出
     */
    default byte[] read(String path) throws IOException {
        try (InputStream in = open(path)) {
            return in.readAllBytes();
        }
    }

    /**
     * @param path 资源名称，相对于资源根目录
     * @return 由调用方负责关闭的输入流
     * @throws IOException 资源不存在或不可读取时抛出
     */
    InputStream open(String path) throws IOException;

    /** @return 不提供资源目录的宿主使用的实现 */
    static GameAssets none() {
        return None.INSTANCE;
    }

    /** 空资源目录。 */
    final class None implements GameAssets {

        private static final None INSTANCE = new None();

        private None() {
        }

        @Override
        public boolean exists(String path) {
            return false;
        }

        @Override
        public InputStream open(String path) throws IOException {
            throw new IOException("This host does not expose the game's assets");
        }
    }
}
