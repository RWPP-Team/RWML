/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import com.corrodinggames.rts.appFramework.MapSelectActivity;
import com.corrodinggames.rts.gameFramework.GameEngine;

/**
 * Starting a single-player game on a chosen map.
 *
 * <p>The game decides how a map is set up, and this class asks it to. {@code
 * MapSelectActivity.loadMapWithAI} is the game's own entry point for it: the level screen calls it
 * for a skirmish map, the level-options screen calls it with the chosen teams, and the menu calls it
 * for a background map. Its two branches are the whole of the rule. A map that is not a skirmish map
 * carries its own teams inside it and the engine builds them, which is what the black-and-white
 * {@code loadMap(true, …)} does; a skirmish map carries none, so the teams are built from the numbers
 * the player chose and {@code loadMap(false, …)} then loads what those numbers describe.</p>
 *
 * <p>Building those teams by hand is what this class used to do, and it is not the same thing. The
 * engine keeps one table of teams indexed by team id, and an id reaches that table only through the
 * registering form of the id; the allies take the even ids first and the opponents take the rest,
 * and every opponent carries the alliance its own id implies unless the map is played as teams. A
 * table built in any other order leaves the map's own triggers pointing at teams that resolve to
 * nothing, which drops the mission's objectives along with every hint that mentions them. None of
 * that is worth restating here, so it is not: the game is asked, once, with the same numbers its own
 * screens pass.</p>
 *
 * <p>{@code Root.loadConfigCommon}, the game's level-options screen, is where those numbers come
 * from: it reads the count of opponents from its own control, reads the chosen game mode, turns that
 * mode into a count of the opponents that join the player, and passes {@code false} for the teams
 * when the mode is the free-for-all entry. It then passes {@code 0} for that count as well, so a
 * free-for-all puts nobody on the player's side.</p>
 */
final class SinglePlayerGame {

    private static final String TAG = "RWML";

    /** The most opponents a game can be set up with, which is the width of the level screen's list. */
    static final int MAX_AI = 7;

    /** The game-mode entry that plays every team against every other, on its own. */
    static final int MODE_FREE_FOR_ALL = 4;

    private SinglePlayerGame() {
    }

    /**
     * Loads the map and opens the game screen.
     *
     * @param from      the screen starting the game
     * @param mapPath   the map's abstract storage path, of the form {@code maps/skirmish/x.tmx}
     * @param aiCount   how many opponents the map is played against, clamped to
     *                  {@code 1..}{@value #MAX_AI}
     * @param modeIndex the chosen entry of the game-mode list: its position is the number of
     *                  opponents that join the player, and the last entry plays a free-for-all
     * @return whether the game was started
     */
    static boolean start(Activity from, String mapPath, int aiCount, int modeIndex) {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null) {
            Log.w(TAG, "The engine is not up; the map cannot be loaded.");
            Toast.makeText(from, LocalizedText.ui("ui.notLoaded"), Toast.LENGTH_SHORT).show();
            return false;
        }

        int opponents = Math.max(1, Math.min(MAX_AI, aiCount));
        int mode = Math.max(0, Math.min(MODE_FREE_FOR_ALL, modeIndex));
        // The mode list is a count of the AI that join the player, which is the entry's own
        // position. The last entry is not a count at all: it plays every team against every other,
        // so no team joins the player and the sides are not drawn.
        boolean freeForAll = mode == MODE_FREE_FOR_ALL;
        int allies = freeForAll ? 0 : mode;
        boolean asTeams = !freeForAll;
        boolean skirmish = MapSelectActivity.isSkirmishMap(mapPath);

        try {
            MapSelectActivity.loadMapWithAI(mapPath, skirmish, opponents, allies, asTeams, false);
        } catch (Throwable t) {
            Log.e(TAG, "The map could not be loaded: " + mapPath, t);
            Toast.makeText(from, LocalizedText.ui("ui.loadFailedPrefix") + mapPath + ": " + t.getMessage(),
                Toast.LENGTH_LONG).show();
            return false;
        }

        Log.i(TAG, "Starting " + mapPath + " against " + opponents + " opponents, "
            + allies + " of them allied" + (asTeams ? "." : ", as a free-for-all."));
        from.startActivity(new Intent(from, InGameActivity.class).putExtra("level", mapPath));
        return true;
    }
}
