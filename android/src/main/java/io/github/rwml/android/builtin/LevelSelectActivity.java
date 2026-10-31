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
 * 单人游戏模式的地图网格：沿用游戏自身的网格布局，每张地图对应一个图块。
 *
 * <p>要显示的模式就是 {@link LevelGroupSelectActivity} 传入的目录。每个图块都显示地图缩略图及其名称，与游戏原界面一致；
 * 没有缩略图的地图仍会列出，因为它依然可以游玩。</p>
 *
 * <p>遭遇战地图允许选择难度、对手数量和游戏模式。其他模式没有这些选项，因此保留布局中的控件设置，由地图自身决定。
 * 选择图块后会请求确认，然后将地图交给 {@link SinglePlayerGame}。</p>
 */
public final class LevelSelectActivity extends BuiltinActivity {

    private MenuBackground background;

    private static final String TAG = "RWML";

    /** 导入地图时系统文件选择器返回的请求码。 */
    private static final int REQUEST_IMPORT = 5;

    private static final String SKIRMISH = "maps/skirmish";

    /** 一张地图：引擎加载的路径和地图卡片显示的名称。 */
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

    /** 配置遭遇战地图选项，并处理向自定义目录导入地图。 */
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
    // 地图
    // ------------------------------------------------------------------

    /** 通过引擎文件层读取模式目录，与游戏界面的读取方式一致。 */
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
            // 文件层会提供错误原因，布局也有对应的提示行。若只显示空网格，用户会以为目录中没有地图。
            showListingFailure();
            return;
        }
        for (String entry : listed) {
            String path = mapPathOf(entry);
            if (path == null) {
                continue;
            }
            // 使用引擎自身的规则按当前语言读取地图名称：语言文件查询的是地图名称，而非文件名，因为文件名不是本地化键。
            maps.add(new MapEntry(path, MapNames.of(entry)));
        }
    }

    /** 无法列出模式目录时，在界面显示文件层返回的原因。 */
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

    /** 传给引擎的条目路径；条目不是地图时为 {@code null}。 */
    private String mapPathOf(String entry) {
        if (entry == null) {
            return null;
        }
        // 引擎文件层会识别并解析这两个标记，因此不做修改，直接传递。
        if (entry.startsWith("MOD|") || entry.startsWith("NEW_PATH|")) {
            return entry;
        }
        if (!entry.endsWith(".tmx")) {
            return null;
        }
        return mode.endsWith("/") ? mode + entry : mode + "/" + entry;
    }

    /** 地图缩略图，游戏会以匹配的名称将其保存在地图旁边。 */
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
                    // 无法关闭缩略图不应导致整个地图图块加载失败。
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 启动游戏
    // ------------------------------------------------------------------

    /** 开始游戏前显示确认提示，并说明即将启动的内容。 */
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

    /** 所选模式中的对手数量。 */
    private int opponents() {
        if (!skirmish || aiCount == null) {
            return SinglePlayerGame.MAX_AI;
        }
        return Math.max(1, Math.min(SinglePlayerGame.MAX_AI,
            aiCount.getSelectedItemPosition() + 1));
    }

    /**
     * 已选择的游戏模式列表项。
     *
     * <p>选择结果由该项的位置决定。游戏的关卡选项界面也会读取同一列表，并将列表项的一基序号作为玩家一侧的队伍数，
     * 其中包含玩家自己的队伍。此处的位置正好比该序号小一，因此两边保持一致，无需由当前界面解释各列表项的含义。</p>
     */
    private int modeIndex() {
        if (!skirmish || gameMode == null) {
            return 0;
        }
        return Math.max(0, gameMode.getSelectedItemPosition());
    }

    /** 所选游戏模式，即列表中显示的名称。 */
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
    // 导入地图
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

    /** 将选中的地图复制到模式目录。 */
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
    // 地图图块
    // ------------------------------------------------------------------

    /** 每张地图对应一个卡片：缩略图位于名称上方，名称同时作为卡片按钮。 */
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
    // 辅助方法
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
