/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.desktop;

import com.corrodinggames.librocket.CommonLibRocket;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.network.MasterServerConnector;
import com.corrodinggames.rts.java.DesktopHttpClientFactory;
import com.corrodinggames.rts.java.JavaCanvas;

import io.github.rwml.api.game.GameBoot;
import io.github.rwml.api.game.GameContext;
import io.github.rwml.bootstrap.GameEntry;
import io.github.rwml.game.GameUiBridge;
import io.github.rwml.api.RwmlRuntime;
import io.github.rwml.builtin.DesktopGameRulesShortcut;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 桌面端启动游戏流程的实现。
 *
 * <p>它位于 desktop 模块中，因为桌面构建依赖 Slick、LWJGL 和 libRocket，这些组件都无法跨平台复用。核心只定义引导流程的结构；
 * 桌面端各步骤由此类提供。</p>
 *
 * <p>启动器的大多数步骤只能在启动器内部执行：一个进程只持有一个 GUI 引擎，其辅助方法是包级私有的，而且只有启动器能创建引擎使用的
 * {@code Context}。因此，此类只负责剩余工作：设置引擎从公开静态字段读取的平台选项，并移交控制权。这些设置具有幂等性，因为启动器会在
 * 第一帧中写入相同的值。</p>
 */
public final class DesktopInterface {

    /** 桌面界面所需的文档字体，列表由启动器提供。 */
    static final List<String> DOCUMENT_FONTS = Collections.unmodifiableList(Arrays.asList(
        "font/Delicious-Roman.otf",
        "font/Delicious-Italic.otf",
        "font/Delicious-Bold.otf",
        "font/Delicious-BoldItalic.otf",
        "font/Roboto-Regular.ttf",
        "font/Roboto-Bold.ttf",
        "font/NotoSansCJKsc-Regular.otf",
        "font/DroidSansFallback.ttf"));

    /** 附带字体系列名称的字体，界面可通过名称请求这些字体。 */
    private static final String FALLBACK_FONT = "font/DroidSansFallback.ttf";
    private static final String FALLBACK_FAMILY = "fallback";

    private DesktopInterface() {
    }

    /** @param boot 要参与构建的启动流程 */
    static void contribute(GameBoot boot) {
        boot.add("desktop platform", DesktopInterface::choosePlatform);
        boot.add(GameBoot.ENGINE, DesktopInterface::handOverToTheGame);
    }

    /** 通过引擎读取的静态字段设置桌面选项；启动器也会设置这些字段。 */
    static void choosePlatform(GameContext context) {
        GameEngine.isDesktopPlatform = true;
        GameEngine.isDesktopCanvas = true;
        GameEngine.graphicsCanvasClass = JavaCanvas.class;
        MasterServerConnector.httpClientFactory = new DesktopHttpClientFactory();
        // 扩展加载和平台设置完成后才能注册。过早解析游戏类会导致其在用户 mixin 注册前就被定义。
        RwmlRuntime runtime = (RwmlRuntime) context.platform();
        GameUiBridge.install(runtime.ui());
        DesktopGameRulesShortcut.install(runtime.ui());
        context.log("The desktop draws through " + JavaCanvas.class.getName() + " (Slick and LWJGL).");
    }

    /**
     * 将进程控制权交给游戏自身的启动器；它负责创建窗口、GUI 引擎、音频栈和 {@code Context}，然后加载其他内容。
     * 通过映射产生的常量反射调用此方法。
     */
    static void handOverToTheGame(GameContext context) throws Exception {
        context.log("Handing over to " + GameEntry.GAME_MAIN + ".");
        Class<?> launcher = Class.forName(GameEntry.GAME_MAIN);
        launcher.getMethod("main", String[].class)
            .invoke(null, (Object) context.arguments().toArray(new String[0]));
    }

    /** @param libRocket 加载这些字体的界面引擎 */
    static void loadFonts(CommonLibRocket libRocket) {
        if (libRocket == null) {
            return;
        }
        for (String font : DOCUMENT_FONTS) {
            if (FALLBACK_FONT.equals(font)) {
                libRocket.loadFont(font, FALLBACK_FAMILY);
            } else {
                libRocket.loadFont(font);
            }
        }
    }
}
