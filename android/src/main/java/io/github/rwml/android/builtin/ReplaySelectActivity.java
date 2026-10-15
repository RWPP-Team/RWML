/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
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
 * The replay list: the game's own replay layout, one button per recorded game.
 *
 * <p>The folder RWML lists is the one the engine records into, and the engine loads a replay by its
 * file name, so this screen never has to know where the folder really is. The newest recording is
 * listed first, which is the order the game lists them in.</p>
 *
 * <p>Choosing one hands the file to the engine's replay engine and opens the game screen, which is
 * where a replay is watched: replays run through the same screen as a game, because they are the
 * same stream of commands played back.</p>
 */
public final class ReplaySelectActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    /** Where the engine keeps recordings, as its own file layer names the folder. */
    private static final String REPLAYS = "/SD/rustedWarfare/replays/";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(LocalizedText.ui("ui.replay"));
        AndroidApp.prepare(this, true);
        setContentView(R.layout.replay_select);

        View back = findViewById(R.id.levelButtonBack);
        if (back != null) {
            back.setOnClickListener(clicked -> finish());
        }

        listReplays();
        LocalizedText.applyTo(findViewById(android.R.id.content));
        Log.i(TAG, "Replay list open.");
    }

    /** Fills the list from the engine's replay folder. */
    private void listReplays() {
        LinearLayout holder = findViewById(R.id.replayHolder);
        if (holder == null) {
            Log.w(TAG, "The replay layout has no holder.");
            return;
        }
        holder.removeAllViews();

        String[] names = names();
        for (final String name : names) {
            holder.addView(entry(name));
        }
        if (names.length == 0) {
            TextView empty = new TextView(this);
            empty.setText(LocalizedText.ui("ui.noReplays"));
            empty.setTextColor(Color.WHITE);
            empty.setPadding(0, dip(10), 0, dip(10));
            holder.addView(empty);
        }
    }

    private String[] names() {
        String[] listed = null;
        try {
            listed = FileLoader.listDir(REPLAYS);
        } catch (Throwable t) {
            Log.w(TAG, "The replay folder could not be listed.", t);
        }
        if (listed == null) {
            return new String[0];
        }

        int kept = 0;
        for (String name : listed) {
            if (isRecording(name)) {
                kept++;
            }
        }
        String[] names = new String[kept];
        int index = 0;
        for (String name : listed) {
            if (isRecording(name)) {
                names[index++] = name;
            }
        }
        // Newest first, which is the order the game's own screen uses.
        Arrays.sort(names, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return Long.compare(modified(right), modified(left));
            }
        });
        return names;
    }

    private static boolean isRecording(String name) {
        return name != null && !name.endsWith(".map") && !name.endsWith(".tmp");
    }

    private static long modified(String name) {
        try {
            return FileLoader.lastModified(REPLAYS + name);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** One replay, as a button named after the recording. */
    private View entry(final String name) {
        Button button = new Button(this);
        button.setBackgroundResource(R.drawable.btn_dropdown);
        button.setText(displayNameOf(name));
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setPadding(0, dip(16), 0, dip(16));
        button.setOnClickListener(clicked -> play(name));

        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        parameters.setMargins(0, dip(2), 0, dip(2));
        button.setLayoutParams(parameters);
        return button;
    }

    private static String displayNameOf(String name) {
        String display = name;
        if (display.endsWith(".replay")) {
            display = display.substring(0, display.length() - ".replay".length());
        }
        return display;
    }

    /** Hands the recording to the engine and opens the screen a replay plays on. */
    private void play(String name) {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null) {
            Toast.makeText(this, LocalizedText.ui("ui.notLoaded"), Toast.LENGTH_SHORT).show();
            return;
        }
        boolean started;
        try {
            started = engine.replayEngine.loadReplay(name);
        } catch (Throwable t) {
            Log.e(TAG, "The replay " + name + " could not be loaded.", t);
            Toast.makeText(this, LocalizedText.ui("ui.loadFailedPrefix") + displayNameOf(name) + ": " + t.getMessage(),
                Toast.LENGTH_LONG).show();
            return;
        }
        if (!started) {
            Toast.makeText(this, LocalizedText.ui("ui.loadFailedPrefix") + displayNameOf(name) + ".",
                Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new android.content.Intent(this, InGameActivity.class));
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5F);
    }
}
