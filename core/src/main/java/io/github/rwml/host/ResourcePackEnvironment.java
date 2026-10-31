/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.host;

import java.nio.file.Path;

/**
 * 宿主为资源包提供的目录，以及目录生效的时机。
 *
 * <p>资源包只携带它替换的文件。读取资源时先查物化目录，未命中就交回游戏自己的读取路径，因此原版资源不需要复制：
 * 游戏升级后新资源立即生效，物化目录里也不会留下与当前版本不符的旧文件。宿主只需要提供一个可写目录，并在目录
 * 就绪或撤下时收到通知。</p>
 *
 * <p>两个宿主都支持资源包。不支持资源包的宿主返回 {@link #unsupported()}：此时资源包扩展仍会被识别，但物化会
 * 失败并报告原因，而不是让玩家以为包已生效。</p>
 */
public interface ResourcePackEnvironment {

    /** @return 宿主是否能够提供资源包所需的目录 */
    default boolean supported() {
        return false;
    }

    /**
     * @return 资源包的物化目录，宿主须保证其位于可写位置；不支持资源包时返回 null
     */
    default Path directory() {
        return null;
    }

    /**
     * 报告当前生效的资源目录，供宿主的资源读取实现切换。
     *
     * <p>核心在物化完成或清除后调用一次。宿主不需要在每次读取时查询目录，也就不会在加载期间看到半成品目录。</p>
     *
     * @param directory 已就绪的资源目录；没有资源包生效时为 null
     */
    default void activated(Path directory) {
    }

    /** @return 不支持资源包的宿主使用的实现 */
    static ResourcePackEnvironment unsupported() {
        return Unsupported.INSTANCE;
    }

    /** 不支持资源包的宿主。 */
    final class Unsupported implements ResourcePackEnvironment {

        private static final Unsupported INSTANCE = new Unsupported();

        private Unsupported() {
        }
    }
}
