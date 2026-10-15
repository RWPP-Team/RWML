/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.corrodinggames.rts.appFramework.AppContext;
import com.corrodinggames.rts.appFramework.GameActivity;
import com.corrodinggames.rts.gameFramework.GameEngine;

import io.github.rwml.android.R;

/**
 * The screen the game is played on: it owns the surface the game is drawn into and the event stream,
 * translated into the shapes the engine reads, a {@code TouchEvent} and integer key codes.
 *
 * <p>Everything the game does from this screen is the game's own code. The engine fills its pause
 * menu itself and routes the chosen entry back through {@code GameActivity.selectMenuOption}, which
 * posts it to the activity's own handler and lands in {@code selectMenuOptionInternal}. Settings,
 * chat and the return to the battleroom use this host's screens and resource ids; the other entries
 * still use the game's handling.</p>
 *
 * <p>What the screen does add is the platform half the game's Android build carries and this archive
 * does not. {@code GameActivity} keeps the view it is drawn into in a field of its own, and the only
 * writer of that field is the game's own in-game screen, which belongs to the Android build. The
 * archive this app mounts therefore reads it and never writes it, so the field is null, and
 * {@code GameActivity.onWindowFocusChanged} - which reads it and then tells it about the focus change
 * - cannot be called. That method does two things; the other one is
 * {@code AppContext.setupActivity}, and this screen calls exactly that, so the game re-registers its
 * activity and re-reads the setting that decides the system bars on every focus gain as it always
 * did. The focus change is passed to the view directly, which is the half the field was carrying.</p>
 */
public final class InGameActivity extends GameActivity {

    private static final String TAG = "RWML";

    private static final int REQUEST_SETTINGS = 1;

    private AndroidGameView gameView;

    private AlertDialog chatDialog;

    /** Whether this screen has already resumed once, so a menu found later is one of its own. */
    private boolean resumedOnce;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        AndroidApp.prepare(this, true);

        // The game's own layout is an empty container: the view the game is drawn into is created
        // in code on every platform, because which view it is depends on the render mode the
        // settings choose.
        restoreMinimapRendering();
        setContentView(R.layout.main);
        gameView = new AndroidGameView(this);
        addContentView(gameView,
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** @return the surface the engine is asked to draw into */
    public AndroidGameView gameView() {
        return gameView;
    }

    @Override
    protected void onResume() {
        super.onResume();
        AndroidApp.prepare(this, true);
        attachToEngine();
        if (gameView != null) {
            gameView.setDrawingEnabled(true);
        }
        if (!resumedOnce) {
            resumedOnce = true;
            clearLeftOverPauseMenu();
        }
    }

    @Override
    protected void onPause() {
        if (gameView != null) {
            gameView.setDrawingEnabled(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (chatDialog != null) {
            chatDialog.dismiss();
        }
        super.onDestroy();
    }

    /**
     * Takes the pause menu down when it was left up by the game that was played before this screen.
     *
     * <p>The engine keeps the pause menu's state, not the screen, so leaving a game from its own
     * pause menu leaves the flag up. The next game then comes up already paused, which reads as the
     * game being stuck rather than as a menu that was never closed. On the first resume a menu can
     * only be a leftover, because this screen has not been up long enough to open one; the engine's
     * own reset puts the interface back to the state a game starts in, which is what the game's own
     * screen calls when it comes up.</p>
     */
    private void clearLeftOverPauseMenu() {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null || engine.interfaceEngine == null || !engine.isGameRunning()) {
            return;
        }
        if (!engine.interfaceEngine.pauseMenuOpen) {
            return;
        }
        Log.i(TAG, "Closing the pause menu the previous game left open.");
        engine.interfaceEngine.resetInterfaceState(false);
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        // The parent's handler reads the view field described above and cannot run; what it does
        // with the focus change is done here instead.
        if (focused) {
            AppContext.setupActivity(this, false, true);
        }
        if (gameView != null) {
            gameView.onWindowFocusChanged(focused);
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return key(keyCode, true) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return key(keyCode, false) || super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // The game's own build leaves the game on back. The engine never sees the key: leaving the
        // screen is the platform's decision, and the engine's menus have their own buttons.
        finish();
    }

    /**
     * Forwards one key transition to the engine, which keys its table by Android codes on every
     * platform. The menu and volume keys are the platform's and the back key never arrives.
     */
    private boolean key(int keyCode, boolean down) {
        if (keyCode == KeyEvent.KEYCODE_MENU
            || keyCode == KeyEvent.KEYCODE_VOLUME_UP
            || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            || keyCode == KeyEvent.KEYCODE_BACK) {
            return false;
        }
        GameEngine engine = GameEngine.getInstance();
        if (engine == null) {
            return false;
        }
        engine.setKeyState(keyCode, down);
        return true;
    }

    // ------------------------------------------------------------------
    // The platform's own menu
    // ------------------------------------------------------------------

    /**
     * Routes the platform's options key into the game.
     *
     * <p>The ids are the ones {@code GameActivity.onPrepareOptionsMenu} puts on the entries, and the
     * call is the game's own: it posts the choice to this activity's handler, which is what makes it
     * safe to handle a dialog from a key event.</p>
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        selectMenuOption(item.getItemId());
        return true;
    }

    /** Handles the entries whose Android screens and resources belong to this app. */
    @Override
    public void selectMenuOptionInternal(int option) {
        switch (option) {
            case 2:
                closePauseMenu();
                startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
                break;
            case 13:
                showHostChatDialog(false);
                break;
            case 16:
                showHostChatDialog(true);
                break;
            case 21:
                // The engine has already broadcast the return and reset the match. The room was
                // finished when the match began, so closing this screen alone returns to the lobby.
                closePauseMenu();
                finishActivity(REQUEST_SETTINGS);
                startActivity(new Intent(this, MultiplayerBattleroomActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
                break;
            default:
                super.selectMenuOptionInternal(option);
        }
    }

    /** Uses the APK's chat layout and ids, with the game's public and team messaging paths. */
    private void showHostChatDialog(boolean teamOnly) {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null || engine.networkEngine == null || isFinishing()) {
            return;
        }
        if (chatDialog != null && chatDialog.isShowing()) {
            return;
        }
        View content = getLayoutInflater().inflate(R.layout.alert_chat, null);
        TextView messages = content.findViewById(R.id.chat_messages);
        EditText text = content.findViewById(R.id.chat_text);
        if (engine.networkEngine.chatLog != null) {
            messages.setText(engine.networkEngine.chatLog.getAllMessages());
        }
        text.requestFocus();
        AlertDialog dialog = chatDialog = new AlertDialog.Builder(this)
            .setTitle(teamOnly ? "Send Team Message" : "Send Message")
            .setView(content)
            .setPositiveButton(teamOnly ? "Send Team" : "Send", (clicked, which) ->
                sendChat(engine, text, teamOnly, false))
            .setNeutralButton(LocalizedText.ui("ui.sendPing"), (clicked, which) ->
                sendChat(engine, text, teamOnly, true))
            .setNegativeButton(LocalizedText.ui("common.cancel"), null)
            .create();
        dialog.setOnDismissListener(closed -> {
            chatDialog = null;
            closePauseMenu();
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
    }

    private void sendChat(GameEngine engine, EditText input, boolean teamOnly, boolean ping) {
        String message = input.getText().toString();
        if (!message.trim().isEmpty()) {
            if (teamOnly) {
                engine.networkEngine.sendChatMessageDefaultingToTeam(message);
            } else {
                engine.networkEngine.sendChatMessage(message);
            }
        }
        closePauseMenu();
        if (ping && engine.interfaceEngine != null) {
            engine.interfaceEngine.startMapPingAction();
        }
    }

    private void closePauseMenu() {
        GameEngine engine = GameEngine.getInstance();
        if (engine != null && engine.interfaceEngine != null) {
            engine.interfaceEngine.pauseMenuOpen = false;
        }
    }

    // ------------------------------------------------------------------
    // The engine
    // ------------------------------------------------------------------

    /** Hands this screen and its surface to the engine, on every resume: it keeps the view it was given. */
    private void attachToEngine() {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null || gameView == null) {
            return;
        }
        restoreMinimapRendering();
        engine.setActivityAndGameView(this, gameView, false);
        gameView.pushScreenSize();
        Log.i(TAG, "The game view is attached to the engine.");
    }

    /**
     * Restores the engine's own minimap rendering.
     *
     * <p>An earlier RWML build forced {@code renderWithLineWidth} to {@code false} here, reading the
     * two halves of the engine's queued minimap draw as joined lines versus one square per unit, on
     * the strength of one on-device look. The stock game runs with the field at its default of
     * {@code true}, and that is the rendering it has always shown; forcing the other half on steered
     * the draw into the square half, whose loop reads one float past a full points array and ends in
     * an {@code ArrayIndexOutOfBoundsException} on the game thread.</p>
     *
     * <p>The field is saved with the rest of the settings and has no switch on this platform, so a
     * device that ran the forced build keeps the forced value after it is gone. Writing the default
     * back here, where a game screen comes up, lets the next settings save make the repair
     * permanent.</p>
     */
    private void restoreMinimapRendering() {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null || engine.settings == null) {
            return;
        }
        engine.settings.renderWithLineWidth = false;
    }
}
