/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.mixin;

import io.github.rwml.android.HostLog;
import io.github.rwml.android.builtin.MultiplayerLobbyActivity;

import kim.der.asm.api.annotation.AsmInject;
import kim.der.asm.api.annotation.AsmMixin;

/**
 * Carries the engine's own "the games on the master server have changed" notice to the lobby.
 *
 * <p>The engine keeps the set of games it has discovered, and it announces a change to that set by
 * calling a static method on the class that used to be the game's server list screen. That class is
 * a shell in the archive this app runs: the screen itself belongs to the desktop build's own
 * interface, which this host does not carry, so the notice is thrown away and a list that is open
 * while the engine fills it in never redraws.</p>
 *
 * <p>The notice is what the engine sends when it adds a game it has just been told about and when
 * it drops the games it has not seen for a while, so without it the lobby can list games that have
 * closed and miss the ones that have opened. The call is redirected to the lobby, which is the
 * screen the notice was for.</p>
 */
@AsmMixin("com/corrodinggames/rts/appFramework/ServerListActivity")
public final class ServerListNotifyMixin {

    /** Whether the notice has been carried once; it fires constantly, so it is said once. */
    private static volatile boolean announced;

    private ServerListNotifyMixin() {
    }

    /**
     * Runs at the head of the engine's notice and hands it to the lobby.
     *
     * <p>Nothing of the original method is skipped: it does nothing on this platform, and it is left
     * alone so that a game change that gives it something to do still has its say.</p>
     */
    @AsmInject(method = "l")
    public static void whenTheGameListChanges() {
        announce();
        MultiplayerLobbyActivity.onServerListChanged();
    }

    private static void announce() {
        if (announced) {
            return;
        }
        announced = true;
        HostLog.add("mixin      ServerListNotifyMixin is in: the lobby hears about changed games");
    }
}
