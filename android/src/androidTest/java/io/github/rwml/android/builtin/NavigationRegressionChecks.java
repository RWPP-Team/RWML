/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */
package io.github.rwml.android.builtin;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;

import com.corrodinggames.rts.appFramework.MapSelectActivity;
import com.corrodinggames.rts.game.RWGameEngine;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.network.NetworkEngine;

import io.github.rwml.android.R;

import java.lang.reflect.Field;

final class NavigationRegressionChecks {
    private final Instrumentation instrumentation;
    private final GameEngine engine = GameEngine.getInstance();
    private final NetworkEngine network = engine.networkEngine;
    private InGameActivity game;

    NavigationRegressionChecks(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    void run() throws Exception {
        check("singlePlayerMenuBackgroundAndRows", this::singlePlayerMenuBackgroundAndRows);
        // No remote players, listening socket or master-server registration are involved.
        MapSelectActivity.loadMapWithAI("maps/skirmish/[p8]Many Islands (8p).tmx",
            true, 1, 0, true, false);
        network.startSingleplayerServer();
        // Keep multiplayer host rules when the room starts another map, without opening a socket.
        network.singlePlayerServer = false;
        network.serverVisible = false;
        network.gameStarted = true;
        game = (InGameActivity) instrumentation.startActivitySync(new Intent(
            instrumentation.getTargetContext(), InGameActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        try {
            check("settingsRoundTrip", this::settingsRoundTrip);
            check("publicAndTeamChat", this::publicAndTeamChat);
            check("returnAndRestartBattleroom", this::returnAndRestartBattleroom);
        } finally {
            instrumentation.runOnMainSync(() -> {
                game.finish();
                network.disconnect("navigation regression finished");
            });
        }
    }

    private void singlePlayerMenuBackgroundAndRows() {
        Activity modes = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),
            LevelGroupSelectActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            android.widget.LinearLayout holder = modes.findViewById(R.id.levelHolder);
            require(holder != null && holder.getChildCount() > 0, "Mode buttons must exist");
            for (int i = 0; i < holder.getChildCount(); i++) {
                require(holder.getChildAt(i).getLayoutParams().width == android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    "Each single-player mode must fill its row");
            }
            require(backgroundOf(modes).isDrawingEnabled(), "Single-player mode screen must show the game background");
        });
        Activity maps = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),
            LevelSelectActivity.class).putExtra(LevelGroupSelectActivity.EXTRA_MODE, "maps/normal")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            android.widget.GridView list = maps.findViewById(R.id.levelHolder);
            require(list != null && list.getNumColumns() == 1, "Campaign maps must use one full-width column");
            require(backgroundOf(maps).isDrawingEnabled(), "Campaign list must show the game background");
            maps.finish();
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> modes.finish());
        instrumentation.waitForIdleSync();
    }

    private static AndroidGameView backgroundOf(Activity activity) {
        android.view.ViewGroup content = activity.findViewById(android.R.id.content);
        android.view.ViewGroup root = (android.view.ViewGroup) content.getChildAt(0);
        require(root.getChildAt(0) instanceof AndroidGameView, "Menu layout must sit over a game surface");
        return (AndroidGameView) root.getChildAt(0);
    }

    private void settingsRoundTrip() {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
            SettingsActivity.class.getName(), null, false);
        try {
            instrumentation.runOnMainSync(() -> game.selectMenuOption(2));
            Activity settings = monitor.waitForActivityWithTimeout(10000);
            require(settings instanceof SettingsActivity, "Settings menu must open the host settings");
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                require(settings.findViewById(R.id.settingsDone) != null, "Settings controls must exist");
                require(!game.gameView().isDrawingEnabled(), "Covered game surface must stop drawing");
                settings.findViewById(R.id.settingsCancel).performClick();
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                require(game.gameView().isDrawingEnabled(), "Game must resume drawing after settings");
                require(!game.isFinishing(), "Settings must preserve the game activity");
            });
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    private void publicAndTeamChat() throws Exception {
        Field field = InGameActivity.class.getDeclaredField("chatDialog");
        field.setAccessible(true);
        for (int option : new int[]{13, 16}) {
            String message = "navigation-chat-" + option;
            instrumentation.runOnMainSync(() -> {
                engine.interfaceEngine.pauseMenuOpen = true;
                game.selectMenuOption(option);
            });
            instrumentation.waitForIdleSync();
            AlertDialog dialog = (AlertDialog) field.get(game);
            require(dialog != null && dialog.isShowing(), "Chat menu must open a dialog");
            instrumentation.runOnMainSync(() -> {
                require(dialog.findViewById(R.id.chat_messages) != null, "Chat log must use APK ids");
                EditText input = dialog.findViewById(R.id.chat_text);
                require(input != null, "Chat input must use APK ids");
                input.setText(message);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            instrumentation.waitForIdleSync();
            require(network.chatLog.getAllMessages().contains(message), "Chat message must be sent locally");
            require(!engine.interfaceEngine.pauseMenuOpen, "Sending chat must close the pause menu");
        }
        instrumentation.runOnMainSync(() -> {
            engine.interfaceEngine.pauseMenuOpen = true;
            game.selectMenuOption(13);
        });
        instrumentation.waitForIdleSync();
        AlertDialog cancelled = (AlertDialog) field.get(game);
        instrumentation.runOnMainSync(cancelled::cancel);
        instrumentation.waitForIdleSync();
        require(!engine.interfaceEngine.pauseMenuOpen, "Cancelling chat must restore game input");
    }

    private void returnAndRestartBattleroom() {
        for (int pass = 0; pass < 2; pass++) {
            Instrumentation.ActivityMonitor roomMonitor = instrumentation.addMonitor(
                MultiplayerBattleroomActivity.class.getName(), null, false);
            MultiplayerBattleroomActivity room;
            try {
                // Exercise the real engine return protocol, including match reset and menu option 21.
                ((RWGameEngine) engine).gameThreadTaskQueue.add(
                    (Runnable) () -> network.sendReturnToBattleroom(null));
                Activity returned = roomMonitor.waitForActivityWithTimeout(15000);
                require(returned instanceof MultiplayerBattleroomActivity,
                    "Engine return must reopen the host battleroom, pass " + pass
                        + ", networked=" + network.networked + ", started=" + network.gameStarted
                        + ", loaded=" + engine.gameJustLoaded + ", running=" + engine.gameLoopActive);
                room = (MultiplayerBattleroomActivity) returned;
                instrumentation.waitForIdleSync();
                require(network.networked, "Return must preserve the network session");
                require(!network.gameStarted, "Return must reset the current match");
            } finally {
                instrumentation.removeMonitor(roomMonitor);
            }
            Instrumentation.ActivityMonitor gameMonitor = instrumentation.addMonitor(
                InGameActivity.class.getName(), null, false);
            try {
                instrumentation.runOnMainSync(() ->
                    room.findViewById(R.id.battleroom_startNetButton).performClick());
                Activity restarted = gameMonitor.waitForActivityWithTimeout(15000);
                require(restarted instanceof InGameActivity, "Returned room must be able to start again");
                game = (InGameActivity) restarted;
                instrumentation.waitForIdleSync();
                require(network.gameStarted, "Restart must run the engine's start protocol");
                require(network.networked, "Restart must preserve the network session");
            } finally {
                instrumentation.removeMonitor(gameMonitor);
            }
        }
    }

    private void check(String name, CheckedAction action) throws Exception {
        Bundle status = new Bundle();
        status.putString("class", getClass().getName());
        status.putString("test", name);
        status.putInt("numtests", 6);
        instrumentation.sendStatus(1, status);
        try {
            action.run();
            instrumentation.sendStatus(0, status);
        } catch (Throwable failure) {
            status.putString("stack", android.util.Log.getStackTraceString(failure));
            instrumentation.sendStatus(-2, status);
            throw new AssertionError(name + " failed", failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private interface CheckedAction {
        void run() throws Exception;
    }
}
