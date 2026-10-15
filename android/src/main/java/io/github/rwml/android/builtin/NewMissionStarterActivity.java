/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.files.FileLoader;
import com.corrodinggames.rts.gameFramework.mod.ModManager;

import java.util.ArrayList;
import java.util.List;

import io.github.rwml.android.R;

/**
 * The starting point for a new mission: the game's own layout, with the map to play and how many
 * players to play it with.
 *
 * <p>The map list is the engine's own listing of the campaign folder, named the way the map grid
 * names its tiles, and the player count runs from a duel to a full table. The screen is the one the
 * game carries for this, and the game's own copy of it fills nothing in: its setup is empty and it
 * closes as soon as it is left, so this one does the part that can be done without inventing an
 * action the layout does not carry - it shows the choices and leaves the screen.</p>
 */
public final class NewMissionStarterActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    /** The folder the mission list is read from, which is the campaign's own. */
    private static final String MISSIONS = "maps/normal";

    /** The player counts the layout's second list offers. */
    private static final int MAX_PLAYERS = 8;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(LocalizedText.ui("ui.newMission"));
        AndroidApp.prepare(this, true);
        setContentView(R.layout.new_mission_starter);

        fillMaps();
        fillPlayerCounts();
        LocalizedText.applyTo(findViewById(android.R.id.content));
        Log.i(TAG, "Mission starter open.");
    }

    /** Lists the campaign folder through the engine's file layer, the way the map grid does. */
    private void fillMaps() {
        Spinner spinner = findViewById(R.id.missionstarter_map);
        if (spinner == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        try {
            String[] listed = FileLoader.listDir(MISSIONS, true);
            GameEngine engine = GameEngine.getInstance();
            ModManager mods = engine == null ? null : engine.modManager;
            if (mods != null) {
                listed = mods.addExtraMapsForPath(listed, MISSIONS);
            }
            if (listed != null) {
                for (String entry : listed) {
                    if (entry != null && entry.endsWith(".tmx")) {
                        names.add(MapNames.of(entry));
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "The missions could not be listed.", t);
        }
        spinner.setAdapter(adapter(names));
    }

    /** Offers the player counts the layout has room for. */
    private void fillPlayerCounts() {
        Spinner spinner = findViewById(R.id.missionstarter_numberofplayers);
        if (spinner == null) {
            return;
        }
        List<String> counts = new ArrayList<>();
        for (int players = 2; players <= MAX_PLAYERS; players++) {
            counts.add(Integer.toString(players));
        }
        spinner.setAdapter(adapter(counts));
    }

    private ArrayAdapter<String> adapter(List<String> values) {
        ArrayAdapter<String> adapter =
            new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    @Override
    protected void onStop() {
        // The screen is a step on the way to a game rather than a place to stay: the game's own copy
        // of it closes here too.
        finish();
        super.onStop();
    }
}
