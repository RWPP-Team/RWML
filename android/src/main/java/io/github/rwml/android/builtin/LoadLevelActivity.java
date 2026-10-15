/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.files.FileLoader;

import java.util.Arrays;
import java.util.Comparator;

import io.github.rwml.android.R;

/**
 * The save list: the game's own mode layout, one button per saved game.
 *
 * <p>The engine loads a save by its file name, so this screen never has to know where the folder
 * really is, and the newest save is listed first. The button that imports files is left out: a save
 * is written by the engine, not copied in by hand, and the folder is the engine's own.</p>
 *
 * <p>Choosing a save hands it to the engine's game saver and opens the game screen, which is where
 * a saved game is continued: a save restores the game it was taken from into that screen.</p>
 */
public final class LoadLevelActivity extends BuiltinActivity {

    private MenuBackground background;

    private static final String TAG = "RWML";

    /** Where the engine keeps saved games, as its own file layer names the folder. */
    private static final String SAVES = "/SD/rustedWarfare/saves/";

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
        setTitle(LocalizedText.ui("ui.load"));
        AndroidApp.prepare(this, true);
        background = new MenuBackground(this, R.layout.level_select);

        View back = findViewById(R.id.levelButtonBack);
        if (back != null) {
            back.setOnClickListener(clicked -> finish());
        }
        hide(R.id.aiDifficulty);
        hide(R.id.modsImportMod);
        hide(R.id.messageInfo);

        listSaves();
        LocalizedText.setName(this, R.id.LevelTextTop, "menus.singlePlayer.loadSave");
        Log.i(TAG, "Save list open.");
    }

    /** Fills the list from the engine's save folder. */
    private void listSaves() {
        LinearLayout holder = findViewById(R.id.levelHolder);
        if (holder == null) {
            Log.w(TAG, "The load layout has no holder.");
            return;
        }
        holder.removeAllViews();

        String[] names = names();
        for (final String name : names) {
            holder.addView(entry(name));
        }
        if (names.length == 0) {
            TextView empty = new TextView(this);
            empty.setText(LocalizedText.ui("ui.noSaves"));
            empty.setTextColor(Color.WHITE);
            empty.setPadding(0, dip(10), 0, dip(10));
            holder.addView(empty);
        }
    }

    private String[] names() {
        String[] listed = null;
        try {
            listed = FileLoader.listDir(SAVES);
        } catch (Throwable t) {
            Log.w(TAG, "The save folder could not be listed.", t);
        }
        if (listed == null) {
            return new String[0];
        }

        int kept = 0;
        for (String name : listed) {
            if (isSave(name)) {
                kept++;
            }
        }
        String[] names = new String[kept];
        int index = 0;
        for (String name : listed) {
            if (isSave(name)) {
                names[index++] = name;
            }
        }
        Arrays.sort(names, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return Long.compare(modified(right), modified(left));
            }
        });
        return names;
    }

    private static boolean isSave(String name) {
        return name != null && !name.endsWith(".map") && !name.endsWith(".tmp");
    }

    private static long modified(String name) {
        try {
            return FileLoader.lastModified(SAVES + name);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** One save, as a button named after the saved game. */
    private View entry(final String name) {
        Button button = new Button(this);
        button.setBackgroundResource(R.drawable.btn_dropdown);
        button.setText(displayNameOf(name));
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setPadding(0, dip(16), 0, dip(16));
        button.setOnClickListener(clicked -> load(name));

        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        parameters.setMargins(0, dip(2), 0, dip(2));
        button.setLayoutParams(parameters);
        return button;
    }

    private static String displayNameOf(String name) {
        String display = name;
        if (display.endsWith(".rwsave")) {
            display = display.substring(0, display.length() - ".rwsave".length());
        }
        return display;
    }

    /** Hands the save to the engine and opens the screen a game is played on. */
    private void load(String name) {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null) {
            Toast.makeText(this, LocalizedText.ui("ui.notLoaded"), Toast.LENGTH_SHORT).show();
            return;
        }
        boolean loaded;
        try {
            loaded = engine.gameSaver.loadSaveFile(name, true);
        } catch (Throwable t) {
            Log.e(TAG, "The save " + name + " could not be loaded.", t);
            Toast.makeText(this, LocalizedText.ui("ui.loadFailedPrefix") + displayNameOf(name) + ": " + t.getMessage(),
                Toast.LENGTH_LONG).show();
            return;
        }
        if (!loaded) {
            Toast.makeText(this, LocalizedText.ui("ui.loadFailedPrefix") + displayNameOf(name) + ".",
                Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, InGameActivity.class));
    }

    private void hide(int id) {
        View view = findViewById(id);
        if (view != null) {
            view.setVisibility(View.GONE);
        }
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5F);
    }
}
