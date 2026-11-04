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
 * 存档列表：沿用游戏自身的模式布局，每个存档对应一个按钮。
 *
 * <p>引擎通过文件名加载存档，因此此界面无需知道存档目录的实际位置，列表按时间从新到旧排列。这里不提供文件导入按钮：
 * 存档由引擎写入，而非手动复制，且存档目录由引擎管理。</p>
 *
 * <p>选择存档后，会将其交给引擎的游戏存档处理器并打开游戏界面，以继续该存档中保存的游戏。</p>
 */
public final class LoadLevelActivity extends BuiltinActivity {

    private MenuBackground background;

    private static final String TAG = "RWML";

    /** 引擎存放存档的位置，目录名称由引擎文件层定义。 */
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
        if (!AndroidApp.prepare(this, true)) { return; }
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

    /** 从引擎的存档目录填充列表。 */
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

    /** 一个存档按钮，名称取自存档。 */
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

    /** 将存档交给引擎，并打开游戏运行界面。 */
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
