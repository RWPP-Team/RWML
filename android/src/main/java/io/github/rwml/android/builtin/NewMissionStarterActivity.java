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
 * 新任务的起始界面：沿用游戏布局，用于选择地图和参与人数。
 *
 * <p>地图列表由引擎从战役目录中读取，名称采用地图网格中显示的格式；人数范围从双人对战到满员。游戏归档中也有此界面，
 * 但其实现为空，离开时会立即关闭，因此这里完成布局本身支持的操作：显示选项并在选择后退出，不额外虚构其他操作。</p>
 */
public final class NewMissionStarterActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    /** 任务列表的读取目录，即战役自身的目录。 */
    private static final String MISSIONS = "maps/normal";

    /** 布局第二个列表提供的玩家人数选项。 */
    private static final int MAX_PLAYERS = 8;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(LocalizedText.ui("ui.newMission"));
        if (!AndroidApp.prepare(this, true)) { return; }
        setContentView(R.layout.new_mission_starter);

        fillMaps();
        fillPlayerCounts();
        LocalizedText.applyTo(findViewById(android.R.id.content));
        Log.i(TAG, "Mission starter open.");
    }

    /** 通过引擎文件层列出战役目录，方式与地图网格一致。 */
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

    /** 提供布局所支持的玩家人数选项。 */
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
        // 此界面只是进入游戏过程中的一个步骤，不是停留位置：游戏自带的实现也会在此处关闭界面。
        finish();
        super.onStop();
    }
}
