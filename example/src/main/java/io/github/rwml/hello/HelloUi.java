/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.hello;

import io.github.rwml.api.ui.UiFramework;
import io.github.rwml.api.ui.UiHost;

/**
 * 示例中声明需要界面的扩展。
 *
 * <p>扩展和界面有意拆分为两个类。扩展入口负责加载；界面由宿主启动，且每个进程只能有一个界面。将二者分开后，即使其他扩展提供界面，
 * 示例扩展仍能正常加载。这也是 {@code ui-framework} 声明单独界面类，而不重复使用扩展入口的原因。</p>
 *
 * <p>此类体现了预期的职责划分：平台负责界面表面、事件和 GL 上下文；扩展只决定是否需要界面，以及从哪个屏幕开始。Android 宿主提供游戏表面，
 * 桌面宿主则直接提供游戏本身；此类无需知道当前对接的是哪种平台。</p>
 */
public final class HelloUi implements UiFramework {

    /** 所有宿主都识别的界面名称：游戏。 */
    private static final String GAME_SCREEN = "game";

    @Override
    public void start(UiHost host) {
        // 有意通过标准流输出：Android 应用会将其镜像到自身日志中，因此即使没有连接调试器也能看到该行。
        System.out.println("[hello] The host is " + host.platform().getClass().getName()
            + " and was started with " + host.arguments().size() + " argument(s).");
        System.out.println("[hello] Asking it for the '" + GAME_SCREEN + "' screen.");
        host.openScreen(GAME_SCREEN);
    }
}
