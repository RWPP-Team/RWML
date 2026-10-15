/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.builtin;

import io.github.rwml.api.ProgressListener;
import io.github.rwml.api.Rwml;
import io.github.rwml.api.RwmlRuntime;
import io.github.rwml.api.game.GameBoot;
import io.github.rwml.api.game.StageReport;
import io.github.rwml.api.ui.UiFramework;
import io.github.rwml.api.ui.UiHost;

/**
 * The desktop builtin game adapter. The host bootstrap is already visible before this is invoked;
 * this adapter starts the shared boot and hands control to the game's launcher.
 */
public final class VanillaInterface implements UiFramework {

    /** The screen name every host knows: the game. */
    static final String GAME_SCREEN = "game";

    @Override
    public void start(UiHost host) {
        RwmlRuntime runtime = Rwml.get();
        if (runtime == null || runtime.gameBoot() == null) {
            System.out.println("[builtin] No runtime to boot through; asking the host for the "
                + "'" + GAME_SCREEN + "' screen.");
            host.openScreen(GAME_SCREEN);
            return;
        }

        GameBoot boot = host.gameBoot();
        if (boot.stageNames().isEmpty()) {
            System.out.println("[builtin] The boot has no steps for this platform; asking the host "
                + "for the '" + GAME_SCREEN + "' screen.");
            host.openScreen(GAME_SCREEN);
            return;
        }

        System.out.println("[builtin] Starting the game through " + boot.stageNames().size()
            + " stage(s): " + boot.stageNames());
        LoadingProgressWindow window = LoadingProgressWindow.showIfPossible(
            host.services().messages().text("launcher.loading"));
        ProgressListener windowProgress = window == null ? ProgressListener.NOOP : window.listener();
        StageReport report;
        try {
            report = boot.start((percent, message) -> {
                System.out.println(String.format("[builtin] [%3d%%] %s", percent, message));
                windowProgress.onProgress(percent, message);
            });
        } finally {
            if (window != null) {
                window.close();
            }
        }

        if (report.isClean()) {
            System.out.println("[builtin] The game is up.");
        } else {
            // A partial boot is reported, not thrown: one step that could not be taken is worth
            // less than a game that starts without it.
            System.out.println("[builtin] The game came up with " + report.failed().size()
                + " stage(s) missing: " + report.failed());
        }
    }
}
