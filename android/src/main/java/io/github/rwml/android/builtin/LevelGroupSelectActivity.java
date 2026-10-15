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
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import io.github.rwml.android.R;

/**
 * The single-player mode chooser: the game's own mode layout, with one button per mode.
 *
 * <p>A mode is a folder of maps. Choosing one hands that folder to {@link LevelSelectActivity}, and
 * the folder is the whole of what this screen decides; the map grid, the difficulty and the AI
 * count all belong to the next screen, exactly as they do in the game.</p>
 *
 * <p>The custom folder is where maps copied in by hand live, and the save list is a separate
 * screen, so both are offered here the way the game offers them.</p>
 */
public final class LevelGroupSelectActivity extends BuiltinActivity {

    private MenuBackground background;

    private static final String TAG = "RWML";

    /** Where a map copied in by hand is looked for. */
    private static final String CUSTOM_MAPS = "/SD/rusted_warfare_maps";

    /** The extra a map grid reads the folder it should list from. */
    static final String EXTRA_MODE = "mode";

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
        setTitle(LocalizedText.ui("ui.singlePlayer"));
        AndroidApp.prepare(this, true);
        background = new MenuBackground(this, R.layout.level_select);

        hide(R.id.aiDifficulty);
        hide(R.id.modsImportMod);
        hide(R.id.messageInfo);

        View back = findViewById(R.id.levelButtonBack);
        if (back != null) {
            back.setOnClickListener(clicked -> finish());
        }

        LinearLayout holder = findViewById(R.id.levelHolder);
        if (holder == null) {
            Log.w(TAG, "The mode layout has no holder.");
            return;
        }
        holder.removeAllViews();

        addMode(holder, "menus.singlePlayer.campaign", "maps/normal");
        addMode(holder, "menus.singlePlayer.skirmish", "maps/skirmish");
        addMode(holder, "menus.singlePlayer.challenge", "maps/challenge");
        addMode(holder, "menus.singlePlayer.survival", "maps/survival");
        addMode(holder, "menus.singlePlayer.custom", CUSTOM_MAPS);

        String loadSave = LocalizedText.get("menus.singlePlayer.loadSave");
        addMode(holder, loadSave == null ? "Load Game" : loadSave,
            clicked -> open(new Intent(this, LoadLevelActivity.class)));

        LocalizedText.setName(this, R.id.LevelTextTop, "menus.singlePlayer.title");
        Log.i(TAG, "Single-player modes open.");
    }

    /** Adds one mode, named in the game's words and opening the folder it stands for. */
    private void addMode(LinearLayout holder, String localeKey, String folder) {
        String label = LocalizedText.get(localeKey);
        addMode(holder, label == null ? folder : label,
            clicked -> open(new Intent(this, LevelSelectActivity.class)
                .putExtra(EXTRA_MODE, folder)));
    }

    /** Adds one mode button, with the look the game's own mode buttons have. */
    private void addMode(LinearLayout holder, String label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setBackgroundResource(R.drawable.btn_dropdown);
        button.setText(label);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(Color.WHITE);
        button.setShadowLayer(1.0F, 2.0F, 2.0F, Color.argb(127, 0, 0, 0));
        int horizontal = dip(15);
        int vertical = dip(16);
        button.setPadding(horizontal, vertical, horizontal, vertical);
        button.setTextSize(14.0F);
        button.setOnClickListener(action);

        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        parameters.setMargins(0, dip(2), 0, dip(2));
        parameters.gravity = Gravity.CENTER_HORIZONTAL;
        holder.addView(button, parameters);
    }

    private void open(Intent intent) {
        startActivity(intent);
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
