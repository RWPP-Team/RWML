/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.SettingsEngine;
import com.corrodinggames.rts.gameFramework.files.FileLoader;
import com.corrodinggames.rts.gameFramework.mod.ModManager;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import io.github.rwml.android.R;

/**
 * The map grid of one single-player mode: the game's own grid layout, one tile per map.
 *
 * <p>The mode is the folder to list, handed over by {@link LevelGroupSelectActivity}. Each tile is
 * the map's thumbnail with its name under it, which is what the game's own screen shows; a map with
 * no thumbnail is still listed, since it is playable without one.</p>
 *
 * <p>Skirmish maps let the difficulty, the number of opponents and the game mode be chosen. The
 * other modes carry no such choices, so their controls stay as the layout wrote them and the map
 * decides. Choosing a tile asks for confirmation and then hands the map to
 * {@link SinglePlayerGame}.</p>
 */
public final class LevelSelectActivity extends BuiltinActivity {

    private MenuBackground background;

    private static final String TAG = "RWML";

    /** The request code the platform's file picker answers with when a map is imported. */
    private static final int REQUEST_IMPORT = 5;

    private static final String SKIRMISH = "maps/skirmish";

    /** One map: the path the engine loads, and the name the tile shows. */
    private static final class MapEntry {
        final String path;
        final String name;

        MapEntry(String path, String name) {
            this.path = path;
            this.name = name;
        }
    }

    private String mode;
    private boolean skirmish;
    private boolean custom;
    private Spinner aiDifficulty;
    private Spinner aiCount;
    private Spinner gameMode;
    private GridView grid;
    private final List<MapEntry> maps = new ArrayList<>();

    @Override protected void onResume() {
        super.onResume();
        if (background != null) { background.resume(); }
    }
    @Override protected void onPause() {
        if (background != null) { background.pause(); }
        super.onPause();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(LocalizedText.ui("ui.levels"));
        AndroidApp.prepare(this, true);
        background = new MenuBackground(this, R.layout.level_select_grid);

        Intent opening = getIntent();
        mode = opening == null ? null : opening.getStringExtra(LevelGroupSelectActivity.EXTRA_MODE);
        if (mode == null || mode.isEmpty()) {
            Log.w(TAG, "The map grid was opened without a mode.");
            finish();
            return;
        }
        skirmish = SKIRMISH.equals(mode);
        custom = mode.contains("/SD/");

        View back = findViewById(R.id.levelButtonBack);
        if (back != null) {
            back.setOnClickListener(clicked -> finish());
        }

        bindControls();
        listMaps();
        LocalizedText.applyTo(findViewById(android.R.id.content));
        Log.i(TAG, "Map grid open for " + mode + " (" + maps.size() + " maps).");
    }

    /** Wires the choices a skirmish map offers, and the map import for the custom folder. */
    private void bindControls() {
        aiDifficulty = findViewById(R.id.aiDifficulty);
        aiCount = findViewById(R.id.aiCount);
        gameMode = findViewById(R.id.gameMode);

        SettingsEngine settings = SettingsEngine.getInstance(getBaseContext());
        if (aiDifficulty != null) {
            aiDifficulty.setSelection(settings == null ? 2 : settings.aiDifficulty + 2);
        }
        if (skirmish) {
            show(aiCount, View.VISIBLE);
            show(gameMode, View.VISIBLE);
            if (aiCount != null) {
                aiCount.setSelection(3);
            }
        }

        View importButton = findViewById(R.id.modsImportMod);
        if (importButton != null) {
            importButton.setVisibility(custom ? View.VISIBLE : View.GONE);
            importButton.setOnClickListener(clicked -> chooseMap());
        }

        View message = findViewById(R.id.messageInfo);
        if (message != null) {
            message.setVisibility(View.GONE);
        }

        grid = findViewById(R.id.levelHolder);
        if (grid != null) {
            grid.setNumColumns(1);
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            grid.setAdapter(new MapAdapter());
            grid.setOnItemClickListener((parent, view, position, id) -> confirmStart(maps.get(position)));
        }
    }

    // ------------------------------------------------------------------
    // The maps
    // ------------------------------------------------------------------

    /** Reads the mode's folder through the engine's file layer, the way the game's screen does. */
    private void listMaps() {
        maps.clear();
        String[] listed = null;
        try {
            listed = FileLoader.listDir(mode, true);
            GameEngine engine = GameEngine.getInstance();
            ModManager mods = engine == null ? null : engine.modManager;
            if (mods != null) {
                listed = mods.addExtraMapsForPath(listed, mode);
            }
        } catch (Throwable t) {
            Log.w(TAG, "The maps in " + mode + " could not be listed.", t);
        }
        if (listed == null) {
            // The file layer says what went wrong and the layout carries a line for it. An empty
            // grid with nothing said about it reads as a folder that holds no maps.
            showListingFailure();
            return;
        }
        for (String entry : listed) {
            String path = mapPathOf(entry);
            if (path == null) {
                continue;
            }
            // The engine's own reading of the file name, in this language: the language file is
            // asked for a map name, never for the file's name, because that is not a locale key.
            maps.add(new MapEntry(path, MapNames.of(entry)));
        }
    }

    /** Puts the file layer's own reason on screen when the mode's folder cannot be listed. */
    private void showListingFailure() {
        TextView message = findViewById(R.id.messageInfo);
        if (message == null) {
            return;
        }
        String reason = FileLoader.takeErrorMessage();
        message.setText(reason == null || reason.isEmpty()
            ? "The maps in " + mode + " could not be listed." : reason);
        message.setVisibility(View.VISIBLE);
    }

    /** The path the engine is given for one listed entry, or {@code null} when it is not a map. */
    private String mapPathOf(String entry) {
        if (entry == null) {
            return null;
        }
        // The engine's file layer understands these two tags and resolves them itself, so they are
        // passed through untouched.
        if (entry.startsWith("MOD|") || entry.startsWith("NEW_PATH|")) {
            return entry;
        }
        if (!entry.endsWith(".tmx")) {
            return null;
        }
        return mode.endsWith("/") ? mode + entry : mode + "/" + entry;
    }

    /** The map's thumbnail, which the game stores beside it under a matching name. */
    private Drawable thumbnailOf(MapEntry entry) {
        String base = entry.path.endsWith(".tmx")
            ? entry.path.substring(0, entry.path.length() - 4) : entry.path;
        String thumbnail = base + "_map.png";
        InputStream stream = null;
        try {
            if (!FileLoader.fileExists(thumbnail)) {
                return null;
            }
            stream = FileLoader.openByPath(thumbnail);
            return stream == null ? null : Drawable.createFromStream(stream, null);
        } catch (Throwable t) {
            Log.w(TAG, "The thumbnail of " + entry.path + " could not be read.", t);
            return null;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                    // A thumbnail that will not close is not worth failing the tile over.
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Starting
    // ------------------------------------------------------------------

    /** Asks before a game is started, showing what is about to run. */
    private void confirmStart(MapEntry entry) {
        int opponents = opponents();
        int mode = modeIndex();
        StringBuilder message = new StringBuilder();
        message.append("Difficulty: ").append(difficultyName()).append('\n');
        if (skirmish) {
            message.append("Number of AIs: ").append(opponents).append('\n');
            message.append("Game mode: ").append(modeName()).append('\n');
        }
        message.append("Map: ").append(entry.name);

        new AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_info)
            .setTitle(LocalizedText.ui("ui.startPrompt"))
            .setMessage(message.toString())
            .setPositiveButton(LocalizedText.ui("launcher.start"), (dialog, which) -> start(entry, opponents, mode))
            .setNegativeButton(LocalizedText.ui("common.cancel"), null)
            .show();
    }

    private void start(MapEntry entry, int opponents, int mode) {
        SettingsEngine settings = SettingsEngine.getInstance(getBaseContext());
        if (settings != null && aiDifficulty != null) {
            settings.aiDifficulty = aiDifficulty.getSelectedItemPosition() - 2;
            settings.save();
        }
        SinglePlayerGame.start(this, entry.path, opponents, mode);
    }

    /** How many opponents the chosen mode plays against. */
    private int opponents() {
        if (!skirmish || aiCount == null) {
            return SinglePlayerGame.MAX_AI;
        }
        return Math.max(1, Math.min(SinglePlayerGame.MAX_AI,
            aiCount.getSelectedItemPosition() + 1));
    }

    /**
     * The chosen entry of the game-mode list.
     *
     * <p>Its position is the whole of the choice. The game's own level-options screen reads the same
     * list and keeps the entry's one-based number as the size of the player's side of the map,
     * including the player's own team; the position is that number less one, so the two agree
     * without this screen having to know what any entry means.</p>
     */
    private int modeIndex() {
        if (!skirmish || gameMode == null) {
            return 0;
        }
        return Math.max(0, gameMode.getSelectedItemPosition());
    }

    /** The chosen game mode, in the words the list shows. */
    private String modeName() {
        if (gameMode == null) {
            return "";
        }
        int position = gameMode.getSelectedItemPosition();
        if (position < 0 || position >= gameMode.getCount()) {
            return "";
        }
        return String.valueOf(gameMode.getItemAtPosition(position));
    }

    private String difficultyName() {
        if (aiDifficulty == null) {
            return "normal";
        }
        int position = aiDifficulty.getSelectedItemPosition();
        if (position >= 0 && position < aiDifficulty.getCount()) {
            return String.valueOf(aiDifficulty.getItemAtPosition(position));
        }
        return "normal";
    }

    // ------------------------------------------------------------------
    // Importing a map
    // ------------------------------------------------------------------

    private void chooseMap() {
        if (!FileImport.choose(this, REQUEST_IMPORT)) {
            Toast.makeText(this, LocalizedText.ui("ui.noPicker"), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST_IMPORT) {
            super.onActivityResult(request, result, data);
            return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        importMap(data.getData());
    }

    /** Copies a picked map into the mode's folder. */
    private void importMap(Uri source) {
        String name = FileImport.nameOf(this, source);
        if (name == null || !name.endsWith(".tmx")) {
            new AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(LocalizedText.ui("ui.importFailed"))
                .setMessage(LocalizedText.ui("ui.mapExpected") + name)
                .setPositiveButton(LocalizedText.ui("common.confirm"), null)
                .show();
            return;
        }
        String failure = FileImport.copyInto(this, source, FileImport.join(mode, name));
        if (failure != null) {
            Toast.makeText(this, LocalizedText.ui("ui.importFailedPrefix") + name + ": " + failure,
                Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, LocalizedText.ui("ui.importedPrefix") + name + ".", Toast.LENGTH_SHORT).show();
        listMaps();
        if (grid != null && grid.getAdapter() instanceof BaseAdapter) {
            ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
        }
        LocalizedText.applyTo(findViewById(android.R.id.content));
    }

    // ------------------------------------------------------------------
    // The tiles
    // ------------------------------------------------------------------

    /** One tile per map: the thumbnail over its name, with the name acting as the tile's button. */
    private final class MapAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return maps.size();
        }

        @Override
        public Object getItem(int position) {
            return maps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            MapEntry entry = maps.get(position);

            LinearLayout tile = new LinearLayout(LevelSelectActivity.this);
            tile.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setBackgroundColor(Color.argb(50, 0, 0, 0));
            tile.setPadding(dip(2), dip(4), dip(2), dip(2));

            Drawable thumbnail = thumbnailOf(entry);
            if (thumbnail != null) {
                ImageView image = new ImageView(LevelSelectActivity.this);
                image.setImageDrawable(thumbnail);
                image.setAdjustViewBounds(true);
                image.setMaxHeight(dip(100));
                image.setPadding(dip(3), dip(1), dip(3), dip(1));
                image.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                tile.addView(image);
            }

            Button label = new Button(LevelSelectActivity.this, null, R.style.LevelButton);
            label.setText(entry.name);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setPadding(dip(3), dip(5), dip(3), dip(5));
            label.setTextSize(18.0F);
            label.setTextColor(Color.WHITE);
            label.setShadowLayer(1.0F, 1.0F, 1.0F, Color.argb(127, 0, 0, 0));
            label.setGravity(Gravity.CENTER);
            label.setBackgroundDrawable(null);
            label.setFocusable(false);
            label.setClickable(false);
            tile.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            return tile;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void show(View view, int visibility) {
        if (view != null) {
            view.setVisibility(visibility);
        }
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5F);
    }
}
