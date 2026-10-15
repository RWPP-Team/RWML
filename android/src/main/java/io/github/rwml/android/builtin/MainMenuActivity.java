/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.corrodinggames.rts.gameFramework.GameEngine;
import io.github.rwml.api.Rwml;
import io.github.rwml.api.ui.GameUi;
import io.github.rwml.api.ui.GameSettings;

import io.github.rwml.android.R;

/**
 * The menu: the screen the splash hands over to, and the way into a game.
 *
 * <p>The layout is the game's own, and so is everything done with it: the buttons are named in the
 * game's language file, the version line is the engine's, and which buttons show is decided the way
 * the game's own menu decides it. Each button leads to the screen the game leads to: the mode chooser
 * for a new single-player game, the lobby for multiplayer, the settings screen, the mod list and the
 * help slides.</p>
 *
 * <p>The engine is already up by the time this screen appears, so everything asked of it is read, not
 * started, and a missing engine degrades the screen to its layout text.</p>
 */
public final class MainMenuActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    /** The store page the demo's buy button leads to. This build is never the demo. */
    private static final String STORE_PACKAGE = "com.corrodinggames.rts";

    /** The surface the engine's own menu map is drawn into, behind the layout. */
    private AndroidGameView background;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        AndroidApp.prepare(this, true);

        // The menu is a layout over the engine's own rendering, the way the game's screens are: the
        // engine draws a map behind, the layout puts the buttons on top. The order of the two is the
        // whole of it, so the container is filled here rather than in a layout file.
        FrameLayout root = new FrameLayout(this);
        background = new AndroidGameView(this);
        root.addView(background, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        getLayoutInflater().inflate(R.layout.menu, root);
        setContentView(root);

        onClick(R.id.buyButton, this::openStorePage);
        onClick(R.id.startgameButton, this::openGame);
        onClick(R.id.menuCustomButton, this::openSinglePlayer);
        onClick(R.id.helpButton, this::openHelp);
        onClick(R.id.exitgameButton, this::finish);

        onClick(R.id.multiplayerButton, this::openMultiplayer);
        onClick(R.id.settingsButton, this::openSettings);
        onClick(R.id.modsButton, this::openMods);

        Log.i(TAG, "Menu open.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        AndroidApp.prepare(this, true);
        showBackground();
        setButtonText();
        setup();
    }

    @Override protected void onPause() {
        if (background != null) { background.setDrawingEnabled(false); }
        super.onPause();
    }

    /**
     * Puts the engine's own background behind the menu.
     *
     * <p>The surface is handed over through the engine's own attach call, and that is the whole of
     * it: the call brings up a menu map when the engine has neither a loaded map nor a match being
     * loaded, and it leaves a game that is still going alone, which is what keeps the continue button
     * honest. The game's own screen does exactly this and nothing more, and so does this one; loading
     * a background here by hand would replace a game the player suspended.</p>
     */
    private void showBackground() {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null || background == null) {
            return;
        }
        try {
            background.setDrawingEnabled(true);
            engine.setActivityAndGameView(this, background, true);
            background.pushScreenSize();
        } catch (Throwable t) {
            // A menu without its background is still a menu, and the failure is worth reporting
            // rather than worth losing the screen over.
            Log.w(TAG, "The engine's menu background could not be brought up.", t);
        }
    }

    /**
     * Shows and hides what the state of the game decides: the buy and mods buttons belong to the demo
     * build, continue exists while a game is still on the engine and has not ended.
     *
     * <p>The test is the engine's own load state rather than the network mode a game was started
     * through: the loop flag says a map is loaded and its frames are running, the ended flag says it
     * has finished, and the path check keeps a map the engine loaded only to sit behind this menu
     * from reading as a game. The game's own menu reads the same three things.</p>
     */
    private void setup() {
        GameUi.Snapshot game = Rwml.get().ui().game();
        View buy = findViewById(R.id.buyButton);
        if (buy != null) { buy.setVisibility(game.demo() ? View.VISIBLE : View.GONE); }
        View mods = findViewById(R.id.modsButton);
        if (mods != null) { mods.setVisibility(game.demo() ? View.GONE : View.VISIBLE); }
        View resume = findViewById(R.id.startgameButton);
        if (resume != null) { resume.setVisibility(game.canResume() ? View.VISIBLE : View.GONE); }
        TextView version = findViewById(R.id.titleInfo);
        if (version != null) { version.setText(game.version()); }
    }

    /** Names the buttons in the game's own words; the layout's own text stands when they cannot be read. */
    private void setButtonText() {
        try {
            text(R.id.buyButton, LocalizedText.get("menus.front.buyNow"));
            text(R.id.startgameButton, LocalizedText.get("menus.front.continue"));
            text(R.id.menuCustomButton, LocalizedText.get("menus.front.singlePlayer"));
            text(R.id.multiplayerButton, LocalizedText.get("menus.front.multiplayer"));
            text(R.id.settingsButton, LocalizedText.get("menus.front.settings"));
            text(R.id.helpButton, LocalizedText.get("menus.front.help"));
            text(R.id.modsButton, LocalizedText.get("menus.front.mods"));
            text(R.id.exitgameButton, LocalizedText.get("menus.front.exit"));
        } catch (Throwable t) {
            Log.w(TAG, "The game's language file did not answer; the layout's text stands.", t);
        }
    }

    /** Opens the game screen, continuing the game in progress. */
    private void openGame() {
        Log.i(TAG, "Opening the game screen.");
        startActivity(new Intent(this, InGameActivity.class));
    }

    /** Starts a new single-player game, which begins by choosing a mode. */
    private void openSinglePlayer() {
        checkIfHelpShouldBeShown(this::openLevelSelect);
    }

    /** Opens the mode chooser, which is the game's own way into a single-player game. */
    private void openLevelSelect() {
        Log.i(TAG, "Opening the single-player mode chooser.");
        startActivity(new Intent(this, LevelGroupSelectActivity.class));
    }

    /** Opens the multiplayer lobby, which is where hosting and joining both begin. */
    private void openMultiplayer() {
        startActivity(new Intent(this, MultiplayerLobbyActivity.class));
    }

    /** Opens the settings screen. */
    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /** Opens the mod list. */
    private void openMods() {
        startActivity(new Intent(this, ModsActivity.class));
    }

    /** Opens the help slides. */
    private void openHelp() {
        startActivity(new Intent(this, QuickHelpActivity.class));
    }

    /**
     * Offers the help slides the first time a game is started, then runs what was asked for.
     *
     * <p>The prompt is the game's own: it fires once, the answer is remembered in the game's
     * settings, and viewing the slides marks them seen as well.</p>
     */
    private void checkIfHelpShouldBeShown(Runnable then) {
        if (Rwml.get().ui().find(GameSettings.class).get("hasPlayedGameOrSeenHelp", Boolean.class)) {
            then.run();
            return;
        }
        new AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_info)
            .setTitle(Rwml.get().ui().messages().text("menu.firstPlay"))
            .setMessage(Rwml.get().ui().messages().text("menu.helpPrompt"))
            .setPositiveButton(Rwml.get().ui().messages().text("common.yes"), (dialog, which) -> {
                openHelp();
                then.run();
            })
            .setNegativeButton(Rwml.get().ui().messages().text("common.no"), (dialog, which) -> then.run())
            .show();
    }

    /** Opens the game's store page, which is what the demo's buy button does. */
    private void openStorePage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=" + STORE_PACKAGE)));
        } catch (ActivityNotFoundException noMarket) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("http://play.google.com/store/apps/details?id=" + STORE_PACKAGE)));
            } catch (ActivityNotFoundException noBrowser) {
                Toast.makeText(this, Rwml.get().ui().messages().text("menu.storeFailed"), Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------------------------------------------------------------
    // The layout, looked up by name
    // ------------------------------------------------------------------

    private void onClick(int id, Runnable action) {
        View view = findViewById(id);
        if (view == null) {
            Log.w(TAG, "The menu layout has no view for " + id + ".");
            return;
        }
        view.setOnClickListener(clicked -> action.run());
    }

    private void text(int id, String value) {
        View view = findViewById(id);
        if (view instanceof TextView && value != null) {
            ((TextView) view).setText(value);
        }
    }
}
