/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.desktop;

import io.github.rwml.api.game.GameBoot;
import io.github.rwml.api.ui.UiHost;
import io.github.rwml.api.ui.UiSurface;
import io.github.rwml.bootstrap.GameEntry;
import io.github.rwml.bootstrap.Relauncher;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 桌面端提供给扩展界面的宿主对象。
 *
 * <p>桌面端唯一的界面就是游戏本身，因此 {@link #openScreen(String)} 会识别 {@value #GAME_SCREEN} 并调用游戏自身入口；
 * 没有扩展提供界面时，启动器也会执行相同调用。需要其他界面的扩展必须自行绘制；遇到未知名称时会报告错误，而不是静默忽略。</p>
 *
 * <p>此类有意设为包级私有：扩展接收的类型是 {@link UiHost}，具体宿主实现不属于平台契约。</p>
 */
final class DesktopUiHost implements UiHost {

    /** 启动游戏的界面名称。 */
    static final String GAME_SCREEN = "game";

    private final DesktopRuntime runtime;
    private final List<String> arguments;

    private boolean gameStarted;

    DesktopUiHost(DesktopRuntime runtime, String[] args) {
        this.runtime = runtime;
        String[] copy = args == null ? new String[0] : args.clone();
        this.arguments = Collections.unmodifiableList(Arrays.asList(copy));
    }

    @Override
    public Object platform() {
        return runtime;
    }

    @Override
    public UiSurface createSurface() {
        return new DesktopUiSurface();
    }

    @Override
    public List<String> arguments() {
        return arguments;
    }

    @Override
    public void openScreen(String screen) {
        if (services().openRegisteredScreen(screen, this)) { return; }
        if ("extensions".equals(screen)) {
            javax.swing.SwingUtilities.invokeLater(() ->
                new io.github.rwml.builtin.ExtensionManagementWindow(null, this).setVisible(true));
            return;
        }
        if (GAME_SCREEN.equals(screen) || "main-menu".equals(screen)) {
            startGame();
            return;
        }
        System.out.println("[rwml] The desktop host has no screen called '" + screen + "'. The game "
            + "screen is '" + GAME_SCREEN + "'.");
    }

    @Override
    public void restart() {
        File jar = Relauncher.ownJar();
        if (jar == null) { throw new IllegalStateException("Restart requires a packaged RWML jar"); }
        try {
            new ProcessBuilder(Relauncher.buildCommand(
                new File(System.getProperty("java.home")), jar, true,
                arguments.toArray(new String[0])))
                .directory(runtime.manager().rootDirectory().toFile()).inheritIO().start();
        } catch (IOException failure) { throw new IllegalStateException("Cannot restart RWML", failure); }
        System.exit(0);
    }

    /**
     * 桌面端唯一能显示的界面：扩展提供的引导流程，或扩展未添加步骤时的游戏自身入口。
     *
     * <p>优先使用引导流程，因为 RWML 知道其步骤顺序，而且已运行的引导流程会返回首次运行结果，而不是再启动一个游戏。如果没有扩展提供任何步骤，
     * 则保留直接调用：即使扩展只请求显示游戏界面，也必须能启动游戏。</p>
     */
    private void startGame() {
        if (gameStarted) {
            return;
        }
        gameStarted = true;
        GameBoot boot = runtime.gameBoot();
        if (boot != null && !boot.stageNames().isEmpty()) {
            boot.start();
            return;
        }
        try {
            Class<?> type = Class.forName(GameEntry.GAME_MAIN);
            type.getMethod("main", String[].class)
                .invoke(null, (Object) DesktopRuntime.gameArguments(arguments.toArray(new String[0])));
        } catch (Exception e) {
            System.err.println("[rwml] The interface asked for the game, but " + GameEntry.GAME_MAIN
                + " did not start: " + e);
        }
    }
}
