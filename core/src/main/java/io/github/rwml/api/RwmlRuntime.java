/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api;

import io.github.rwml.api.game.GameBoot;
import io.github.rwml.api.ui.UiServices;
import io.github.rwml.core.ExtensionManager;
import io.github.rwml.core.LoadReport;

/**
 * 正在运行的 RWML：持有扩展管理器、最近一次加载结果，以及启动游戏所需的步骤序列。
 *
 * <p>每个宿主启动扩展系统时都会构造一个实现：桌面端使用 {@code io.github.rwml.desktop.DesktopRuntime}，
 * Android 应用则在其引导程序中创建，并将其安装到 {@link Rwml}。扩展通过 {@code Rwml.get()} 访问宿主，
 * 因此 mixin 无需了解当前运行的平台也能调用宿主功能。</p>
 */
public interface RwmlRuntime {

    /** 游戏准备前即可使用；旧版或自定义运行时可以不提供。 */
    default UiServices ui() {
        throw new UnsupportedOperationException("This runtime does not provide UI services");
    }

    /** @return 加载扩展所用的管理器；宿主准备好之前为 {@code null} */
    ExtensionManager manager();

    /** @return 最近一次扩展加载结果；尚未加载时为 {@code null} */
    LoadReport report();

    /**
     * @return 此运行时所在的平台，扩展可据此判断是否应在此平台添加自身步骤
     */
    Platform platform();

    /**
     * 启动游戏的步骤序列。
     *
     * <p>宿主会填入平台所需步骤；通过 {@code ui-framework} 声明的界面应启动此流程，而不应自行访问游戏。
     * 启动流程只会运行一次，因此重复请求游戏不会创建第二个引擎。</p>
     *
     * @return 此运行时的启动流程；运行时安装后不为 null
     */
    GameBoot gameBoot();
}
