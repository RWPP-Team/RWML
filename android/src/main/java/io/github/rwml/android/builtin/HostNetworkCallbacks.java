/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.corrodinggames.rts.game.Team;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.network.Connection;
import com.corrodinggames.rts.gameFramework.network.NetworkCallbacks;
import com.corrodinggames.rts.gameFramework.network.NetworkEngine;
import com.corrodinggames.rts.gameFramework.network.PasswordPrompt;

/**
 * The host's half of the engine's network protocol.
 *
 * <p>The engine never drives a screen directly. When the battleroom has to be redrawn, when the game
 * is to start, when a chat line arrives, or when a joining client has to be asked for a password, the
 * engine calls into its {@link NetworkCallbacks}, and which object that is is the host's choice: the
 * desktop host passes its own launcher, and this is the Android host's counterpart. The engine's
 * default does nothing, which is why a host that never installs one sees a battleroom that never
 * updates and a game that never starts.</p>
 *
 * <p>Nothing here touches a screen field directly. The engine calls in from its own threads, so every
 * call is posted to the main thread, and the room is reached through the one that is currently open
 * rather than through a captured reference that a rotation would leave stale.</p>
 */
public final class HostNetworkCallbacks extends NetworkCallbacks {

    private static final String TAG = "RWML";

    /** The callbacks object the engine is given; installing twice reuses it. */
    private static volatile HostNetworkCallbacks installed;

    /** The battleroom on screen, or {@code null} when none is. */
    private static volatile MultiplayerBattleroomActivity room;

    private final Handler main = new Handler(Looper.getMainLooper());

    private HostNetworkCallbacks() {
    }

    /**
     * Gives the engine this host's callbacks, unless it already has them.
     *
     * <p>The engine is created once and outlives every screen, so the callbacks are installed where
     * the network is first used and stay installed.</p>
     */
    static void install(NetworkEngine network) {
        if (network == null) {
            return;
        }
        HostNetworkCallbacks callbacks = installed;
        if (callbacks == null) {
            synchronized (HostNetworkCallbacks.class) {
                callbacks = installed;
                if (callbacks == null) {
                    callbacks = new HostNetworkCallbacks();
                    installed = callbacks;
                }
            }
        }
        network.networkCallbacks = callbacks;
    }

    /** Records the room the engine's calls are about, and installs the callbacks with it. */
    static void attach(MultiplayerBattleroomActivity activity) {
        room = activity;
        GameEngine engine = GameEngine.getInstance();
        install(engine == null ? null : engine.networkEngine);
    }

    /** Forgets the room, so a call that arrives after it closes is not posted to a dead screen. */
    static void detach(MultiplayerBattleroomActivity activity) {
        if (room == activity) {
            room = null;
        }
    }

    /** @return whether a battleroom is on screen right now. */
    static boolean isRoomOpen() {
        return room != null;
    }

    // ------------------------------------------------------------------
    // The engine's calls
    // ------------------------------------------------------------------

    /** The team list, the ready flags or the port check changed, so the room redraws. */
    @Override
    public void onBattleroomUIUpdate() {
        final MultiplayerBattleroomActivity activity = room;
        if (activity == null) {
            return;
        }
        main.post(activity::refresh);
    }

    /**
     * Every client is ready: the game begins.
     *
     * <p>The map is loaded and the play screen opened, which is the sequence the desktop host performs
     * when it receives the same call. When no room is on screen the game is started from the
     * application context instead, so a start that arrives after the room closed still lands.</p>
     */
    @Override
    public void startGameEvent() {
        Log.i(TAG, "The engine says the game is starting.");
        final MultiplayerBattleroomActivity activity = room;
        if (activity != null) {
            main.post(activity::startMultiplayerGame);
            return;
        }
        main.post(() -> startWithoutRoom());
    }

    /** The server dropped the room, so no screen should stay on it. */
    @Override
    public void onBattleroomClosed() {
        final MultiplayerBattleroomActivity activity = room;
        if (activity != null) {
            main.post(activity::finish);
        }
    }

    /**
     * A joining client has to be asked for a password.
     *
     * <p>The engine asks for one through its own prompt as well as through this call: it posts the
     * game's own password dialog to the main thread itself, because a prompt is also raised from
     * inside the engine's connect path, where no host screen is involved. Showing it here as well
     * would raise a second dialog over the first, so this is only where the host is told.</p>
     */
    @Override
    public void onShowPasswordPrompt(final PasswordPrompt prompt) {
        Log.i(TAG, "The engine is asking for a password"
            + (prompt == null || prompt.question == null
                ? "." : " for its own question: " + prompt.question));
    }

    /** A chat line arrived, so the log redraws. */
    @Override
    public void onChatMessage(int type, String message, String senderName, Connection connection) {
        final MultiplayerBattleroomActivity activity = room;
        if (activity != null) {
            main.post(activity::refresh);
        }
    }

    /** A team was added, so the player table redraws. */
    @Override
    public void onPlayerAdded(Team team) {
        final MultiplayerBattleroomActivity activity = room;
        if (activity != null) {
            main.post(activity::refresh);
        }
    }

    @Override
    public void onPlayerJoined(Connection connection, String name, String clientId) {
        final MultiplayerBattleroomActivity activity = room;
        if (activity != null) {
            main.post(activity::refresh);
        }
    }

    @Override
    public void onPlayerDisconnected(Connection connection, String name) {
        final MultiplayerBattleroomActivity activity = room;
        if (activity != null) {
            main.post(activity::refresh);
        }
    }

    @Override
    public boolean isGameStarting() {
        MultiplayerBattleroomActivity activity = room;
        return activity != null && activity.isGameStarting();
    }

    // ------------------------------------------------------------------
    // The fallback
    // ------------------------------------------------------------------

    /** Starts a game the engine announced while no battleroom was on screen. */
    private void startWithoutRoom() {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null || engine.networkEngine == null || !engine.networkEngine.gameStarted) {
            return;
        }
        Context context = engine.getContext();
        if (context == null) {
            Log.w(TAG, "A game start arrived with no room and no context to start it from.");
            return;
        }
        try {
            com.corrodinggames.rts.appFramework.MultiplayerBattleroomActivity.loadMultiplayerMap();
        } catch (Throwable t) {
            Log.e(TAG, "The multiplayer map could not be loaded.", t);
            return;
        }
        Intent game = new Intent(context, InGameActivity.class);
        game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(game);
    }
}
