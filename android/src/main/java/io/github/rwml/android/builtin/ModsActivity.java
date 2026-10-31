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
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.corrodinggames.rts.game.units.custom.CustomUnitMetadataLoader;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.SettingsEngine;
import com.corrodinggames.rts.gameFramework.files.FileLoader;
import com.corrodinggames.rts.gameFramework.mod.Mod;
import com.corrodinggames.rts.gameFramework.mod.ModManager;

import java.util.ArrayList;
import java.util.List;

import io.github.rwml.android.R;

/**
 * 模组界面：沿用游戏自身的模组布局，以复选框列出所有已安装模组。
 *
 * <p>勾选表示启用模组。列表由引擎通过 {@link ModManager} 提供，读取方式与游戏界面一致：刷新时扫描模组目录，
 * “重新加载”按钮也会执行相同操作。保存时会通过管理器写回每个模组的勾选状态，由管理器将选择记录到引擎设置并保存。</p>
 *
 * <p>导入模组时会打开平台文件选择器，将选中的归档复制到模组目录并刷新列表。创建目录会新建用于复制模组的文件夹。
 * 通过存储访问框架关联目录属于 Android 版本文件浏览器的功能，本宿主没有携带该浏览器，因此隐藏对应按钮。</p>
 */
public final class ModsActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    /** 平台文件选择器返回结果所使用的请求码。 */
    private static final int REQUEST_IMPORT = 5;

    private SettingsEngine settings;
    private ModManager mods;
    private LinearLayout container;

    /** 列表中的一行：对应模组及显示其状态的复选框。 */
    private final List<Row> rows = new ArrayList<>();

    private static final class Row {
        final Mod mod;
        final CheckBox box;

        Row(Mod mod, CheckBox box) {
            this.mod = mod;
            this.box = box;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(LocalizedText.ui("ui.mods"));
        AndroidApp.prepare(this, true);
        setContentView(R.layout.mods);

        GameEngine engine = GameEngine.getInstance();
        if (engine == null) {
            Log.w(TAG, "The engine is not up; the mods screen cannot bind.");
            Toast.makeText(this, LocalizedText.ui("ui.notLoaded"), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        settings = engine.settings;
        mods = engine.modManager;
        if (mods == null) {
            Log.w(TAG, "The engine carries no mod manager.");
            Toast.makeText(this, LocalizedText.ui("ui.notLoaded"), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        container = findViewById(R.id.modsContainer);
        hide(R.id.modsLinkModFolder);
        hide(R.id.messageInfo);

        listMods();
        onclick(R.id.modsSave, this::save);
        onclick(R.id.modsReload, this::reload);
        onclick(R.id.modsCreateFolder, this::createFolder);
        onclick(R.id.modsImportMod, this::chooseArchive);
        onclick(R.id.modsClose, this::finish);
        LocalizedText.applyTo(findViewById(android.R.id.content));
        Log.i(TAG, "Mods open.");
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    /** 根据引擎自身的模组列表重建界面列表。 */
    private void listMods() {
        if (container == null) {
            return;
        }
        container.removeAllViews();
        rows.clear();

        List<Mod> installed = new ArrayList<>();
        try {
            Object listed = mods.refreshModList();
            if (listed instanceof List) {
                for (Object entry : (List<?>) listed) {
                    if (entry instanceof Mod) {
                        installed.add((Mod) entry);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "The mod list could not be read.", t);
        }

        fillInfo();
        for (Mod mod : installed) {
            container.addView(rowFor(mod));
        }
        if (installed.isEmpty()) {
            container.addView(message("No mods found."));
        }
    }

    /** 列表上方的存储提示，以及管理器最近报告的加载问题。 */
    private void fillInfo() {
        View view = findViewById(R.id.modExtraTextInfo);
        if (!(view instanceof TextView)) {
            return;
        }
        StringBuilder text = new StringBuilder();
        if (settings != null && settings.storageType == 0) {
            text.append("Note: Using internal game storage only")
                .append(" (Switch to external storage in game settings)");
        }
        if (ModManager.a != null) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(ModManager.a);
        }
        ((TextView) view).setText(text.toString());
    }

    /** 单个模组：复选框中显示标题，旁边显示引擎报告的状态。 */
    private View rowFor(Mod mod) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(android.view.Gravity.CENTER);
        row.setBackgroundColor(Color.argb(30, 0, 0, 0));
        int padding = dip(7);
        row.setPadding(dip(2), padding, dip(2), padding);

        CheckBox box = new CheckBox(this);
        box.setChecked(!mod.disabled);
        box.setText(mod.getTitle());
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20.0F);
        box.setTextColor(Color.WHITE);
        row.addView(box);

        String description = safe(mod.getDescriptionWithStorage());
        if (description != null) {
            row.addView(line("  " + description, Color.rgb(0, 255, 0), 14.0F));
        }
        String status = safe(mod.getStatusMessage());
        if (status != null) {
            row.addView(line(status, Color.rgb(255, 255, 255), 14.0F));
        }
        if (mod.errorMessage != null) {
            TextView error = line("  " + mod.errorMessage, Color.rgb(255, 120, 120), 14.0F);
            error.setBackgroundColor(Color.argb(108, 0, 0, 0));
            row.addView(error);
        }

        rows.add(new Row(mod, box));
        return row;
    }

    private TextView line(String text, int color, float sizeSp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTypeface(Typeface.DEFAULT);
        return view;
    }

    private TextView message(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setGravity(android.view.Gravity.CENTER);
        view.setPadding(0, dip(10), 0, dip(10));
        return view;
    }

    // ------------------------------------------------------------------
    // 按钮
    // ------------------------------------------------------------------

    /** 通过管理器写回每个复选框状态，并保存选择。 */
    private void save() {
        for (Row row : rows) {
            row.mod.disabled = !row.box.isChecked();
        }
        try {
            mods.saveSelection();
            if (settings != null) {
                settings.save();
            }
        } catch (Throwable t) {
            Log.w(TAG, "The mod selection could not be saved.", t);
            Toast.makeText(this, LocalizedText.ui("ui.saveModsFailed"), Toast.LENGTH_LONG).show();
            return;
        }

        int errored = 0;
        try {
            errored = mods.countErroredEnabledMods();
        } catch (Throwable t) {
            Log.w(TAG, "The mod errors could not be counted.", t);
        }
        String message = errored == 0
            ? "Mod changes saved. They are used the next time a game is started."
            : "Mod changes saved. " + errored + " enabled mods have errors.";
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    /** 重新读取模组目录，与游戏“重新加载”按钮的行为一致。 */
    private void reload() {
        listMods();
        LocalizedText.applyTo(findViewById(android.R.id.content));
        Toast.makeText(this, LocalizedText.ui("ui.modsReloaded"), Toast.LENGTH_SHORT).show();
    }

    /** 模组安装目录不存在时创建该目录。 */
    private void createFolder() {
        String directory;
        boolean ready;
        try {
            directory = CustomUnitMetadataLoader.getModsUnitsDirectory();
            // 目录已经存在与刚刚创建成功的效果相同。
            ready = FileLoader.createDirectory(directory) || FileLoader.isDirectory(directory);
        } catch (Throwable t) {
            Log.w(TAG, "The mods folder could not be created.", t);
            Toast.makeText(this, LocalizedText.ui("ui.modsCreateFailed"), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this,
            ready ? "Mods folder ready: " + directory : "Could not create: " + directory,
            Toast.LENGTH_LONG).show();
    }

    /** 请求平台选择要安装为模组的归档。 */
    private void chooseArchive() {
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
        importMod(data.getData());
    }

    /** 将选中的归档复制到模组目录，然后重新读取列表。 */
    private void importMod(Uri source) {
        String name = FileImport.nameOf(this, source);
        if (TextUtils.isEmpty(name)) {
            Toast.makeText(this, LocalizedText.ui("ui.noFilename"), Toast.LENGTH_LONG).show();
            return;
        }
        if (!name.endsWith(".zip") && !name.endsWith(".rwmod")) {
            new AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(LocalizedText.ui("ui.importFailed"))
                .setMessage(LocalizedText.ui("ui.modExpected") + name)
                .setPositiveButton(LocalizedText.ui("common.confirm"), null)
                .show();
            return;
        }

        String directory;
        try {
            directory = CustomUnitMetadataLoader.getModsUnitsDirectory();
            FileLoader.createDirectory(directory);
        } catch (Throwable t) {
            Log.w(TAG, "The mods folder could not be reached.", t);
            Toast.makeText(this, LocalizedText.ui("ui.modsUnavailable"), Toast.LENGTH_LONG).show();
            return;
        }

        String failure = FileImport.copyInto(this, source, FileImport.join(directory, name));
        if (failure != null) {
            Toast.makeText(this, LocalizedText.ui("ui.importFailedPrefix") + name + ": " + failure,
                Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, LocalizedText.ui("ui.importedPrefix") + name + ".", Toast.LENGTH_SHORT).show();
        listMods();
        LocalizedText.applyTo(findViewById(android.R.id.content));
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private void onclick(int id, Runnable action) {
        View view = findViewById(id);
        if (view == null) {
            Log.w(TAG, "The mods layout has no view for " + id + ".");
            return;
        }
        view.setOnClickListener(clicked -> action.run());
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

    private static String safe(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
