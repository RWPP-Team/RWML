/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.ui;

import io.github.rwml.api.Rwml;
import io.github.rwml.api.RwmlRuntime;
import io.github.rwml.api.game.GameBoot;

import java.util.List;

/**
 * 宿主启动 {@link UiFramework} 时提供给它的对象。
 *
 * <p>此接口刻意保持精简，仅提供实现类访问平台所需的平台句柄、宿主启动参数，以及所有平台都能执行的操作：显示界面。</p>
 */
public interface UiHost {

    /** 提供共享翻译、扩展管理、事件和宿主数据服务。 */
    default UiServices services() {
        RwmlRuntime runtime = Rwml.get();
        if (runtime == null) {
            throw new IllegalStateException("The host has not prepared UI services");
        }
        return runtime.ui();
    }

    /**
     * 平台句柄。
     *
     * <p>Android 上这是启动器 Activity 的 {@code android.content.Context}，也是启动其他 Activity 的唯一方式；
     * 桌面端则是宿主运行时。该值不会为 null，但其类型只有平台自身知道，因此实现类应先确认运行平台，再进行类型转换。</p>
     *
     * @return 宿主的平台对象，不为 null
     */
    Object platform();

    /**
     * Creates an extension-owned UI surface when the platform supports native extension content.
     *
     * <p>Desktop and Android hosts return a surface that accepts their native window/view types. A framework
     * should build and attach its content on the returned surface's UI executor.</p>
     *
     * @return a newly created platform surface
     * @throws UnsupportedOperationException if this host cannot create an extension-owned surface
     */
    default UiSurface createSurface() {
        throw new UnsupportedOperationException("This host does not support extension-owned UI surfaces");
    }

    /**
     * Creates a surface over the host's already-running game view when supported.
     *
     * <p>This lets a framework keep native game input and lifecycle handling while displaying extension content
     * over the game. The returned surface may defer content creation until the game screen opens.</p>
     *
     * @return a surface associated with the game's native screen
     * @throws UnsupportedOperationException if this host cannot create game overlays
     */
    default UiSurface createGameSurface() {
        throw new UnsupportedOperationException("This host does not support game UI surfaces");
    }

    /**
     * 宿主启动时传入的参数，顺序与传入时一致。
     *
     * @return 不可变的参数列表，不为 null
     */
    List<String> arguments();

    /**
     * 共享的游戏初始化流程。界面框架可以显示自己的启动器，然后在工作线程中运行此流程并提供进度监听器，完成后再打开游戏界面。
     *
     * @return 宿主的启动流程；两个平台都会在扩展加载后提供
     */
    default GameBoot gameBoot() {
        RwmlRuntime runtime = Rwml.get();
        if (runtime == null || runtime.gameBoot() == null) {
            throw new IllegalStateException("The host has not prepared a game boot");
        }
        return runtime.gameBoot();
    }

    /**
     * 请求平台显示一个界面。
     *
     * <p>名称仅作提示，而非强制约定：平台会将已知名称映射到自身界面；遇到未知名称时会报告该情况，而不会直接失败。
     * 由于 Android 上界面启动是异步操作，此方法可能在界面显示前就已返回。</p>
     *
     * @param screen 请求的界面，例如 {@code game}
     */
    void openScreen(String screen);

    /**
     * Opens a screen with an extension surface associated with it, when supported.
     *
     * @param screen requested screen name
     * @param surface surface that should be attached to the screen
     */
    default void openScreen(String screen, UiSurface surface) {
        openScreen(screen);
    }

    /** 在新进程中重启宿主，使扩展类和 mixin 的变更生效。 */
    default void restart() {
        throw new UnsupportedOperationException("This host does not support restart");
    }
}
