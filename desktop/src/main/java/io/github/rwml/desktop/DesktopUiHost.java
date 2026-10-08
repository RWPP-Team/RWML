/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.desktop;

import io.github.rwml.api.game.GameBoot;
import io.github.rwml.api.ui.UiHost;
import io.github.rwml.bootstrap.GameEntry;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What an extension's interface is handed on the desktop.
 *
 * <p>The desktop's one screen is the game itself, so {@link #openScreen(String)} recognises
 * {@value #GAME_SCREEN} and invokes the game's own entry point, which is the same call the bootstrap
 * makes when no extension claims the interface. An interface that wants another screen has to draw
 * it; an unknown name is reported rather than silently ignored.</p>
 *
 * <p>The class is package private on purpose: the type an extension receives is {@link UiHost}, and
 * what the host is on this platform is not part of the contract.</p>
 */
final class DesktopUiHost implements UiHost {

    /** The screen name that starts the game. */
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
    public List<String> arguments() {
        return arguments;
    }

    @Override
    public void openScreen(String screen) {
        if (GAME_SCREEN.equals(screen) || "main-menu".equals(screen)) {
            startGame();
            return;
        }
        System.out.println("[rwml] The desktop host has no screen called '" + screen + "'. The game "
            + "screen is '" + GAME_SCREEN + "'.");
    }

    /**
     * The one screen the desktop can put up: the boot an extension filled in, or the game's own
     * entry point when nothing filled it.
     *
     * <p>The boot is preferred because it is the sequence RWML knows about, and because a boot that
     * already ran returns its first outcome instead of starting a second game. The direct call is
     * kept for the case where no extension contributed any step: an extension that only asks for the
     * game screen still has to get one.</p>
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
                .invoke(null, (Object) arguments.toArray(new String[0]));
        } catch (Exception e) {
            System.err.println("[rwml] The interface asked for the game, but " + GameEntry.GAME_MAIN
                + " did not start: " + e);
        }
    }
}
