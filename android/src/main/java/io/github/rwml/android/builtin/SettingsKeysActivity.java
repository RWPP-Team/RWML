/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import io.github.rwml.api.Rwml;
import io.github.rwml.api.ui.GameSettings;

import java.util.ArrayList;
import java.util.List;

import io.github.rwml.android.R;

/**
 * 使用游戏自身的键盘布局显示按键绑定，并将绑定保存在引擎的 {@link GameSettings} 中。
 *
 * <p>可配置五项绑定：动作键、跳跃键和四个方向键。每个按钮都会根据游戏自身的键码表显示当前绑定；点击按钮后会提示选择新按键，
 * 随后按下的键就会成为绑定。点击“完成”会将五项设置写入引擎并保存；“取消”会放弃更改；“默认”会恢复四个方向键和居中的
 * 动作键，与游戏原生界面一致。</p>
 */
public final class SettingsKeysActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    /** 引擎自带的默认按键顺序；点击“默认”后恢复为中、上、左、右、下。 */
    private static final int DEFAULT_ACTION = KeyEvent.KEYCODE_DPAD_CENTER;
    private static final int DEFAULT_JUMP = KeyEvent.KEYCODE_DPAD_UP;
    private static final int DEFAULT_LEFT = KeyEvent.KEYCODE_DPAD_LEFT;
    private static final int DEFAULT_RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT;
    private static final int DEFAULT_DOWN = KeyEvent.KEYCODE_DPAD_DOWN;

    /** 一个按键绑定：显示该绑定的按钮、按键名称及对应键码。 */
    private static final class Binding {
        final Button button;
        final String name;
        int keyCode;

        Binding(Button button, String name, int keyCode) {
            this.button = button;
            this.name = name;
            this.keyCode = keyCode;
        }

        /** 使用游戏提供的键码名称更新绑定按钮。 */
        void show(String label) {
            button.setText(name + ": " + label);
        }
    }

    private GameSettings settings;
    private final List<Binding> bindings = new ArrayList<>();

    /** 一次性读取游戏的键码名称；键码是从 1 开始的数组索引。 */
    private String[] keyLabels;

    /** 标记是否按下“完成”；取消或直接关闭时不修改引擎绑定。 */
    private boolean saveChanges = true;

    /** 标记绑定是否已写入，避免关闭时重复写入。 */
    private boolean saved;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(LocalizedText.ui("ui.keyboard"));
        AndroidApp.prepare(this, true);
        setContentView(R.layout.settings_keyboard);

        settings = Rwml.get().ui().find(GameSettings.class);
        if (settings == null) {
            Log.w(TAG, "The settings engine is not up; the keyboard screen cannot bind.");
            Toast.makeText(this, LocalizedText.ui("ui.notLoaded"), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        bind(R.id.settingsKAction, LocalizedText.ui("ui.action"), settings.get("keyAction", Integer.class));
        bind(R.id.settingsKJump, LocalizedText.ui("ui.jump"), settings.get("keyJump", Integer.class));
        bind(R.id.settingsKLeft, LocalizedText.ui("ui.left"), settings.get("keyLeft", Integer.class));
        bind(R.id.settingsKRight, LocalizedText.ui("ui.right"), settings.get("keyRight", Integer.class));
        bind(R.id.settingsKDown, LocalizedText.ui("ui.down"), settings.get("keyDown", Integer.class));

        for (final Binding binding : bindings) {
            binding.button.setOnClickListener(clicked -> askForKey(binding));
            refresh(binding);
        }

        onClick(R.id.settingsKDone, this::saveSettings);
        onClick(R.id.settingsKCancel, this::cancel);
        onClick(R.id.settingKDefaults, this::useDefaults);
        Log.i(TAG, "Keyboard bindings open.");
    }

    /** 如果布局中存在对应按钮，则为其关联一个按键绑定。 */
    private void bind(int id, String name, int keyCode) {
        Button button = findViewById(id);
        if (button == null) {
            Log.w(TAG, "The keyboard layout has no view for " + id + ".");
            return;
        }
        bindings.add(new Binding(button, name, keyCode));
    }

    /** 显示当前绑定的按键。 */
    private void refresh(Binding binding) {
        binding.show(keyLabel(binding.keyCode));
    }

    /** 游戏为键码提供的名称；名称表中没有对应项时显示普通文本。 */
    private String keyLabel(int keyCode) {
        if (keyLabels == null) {
            keyLabels = getResources().getStringArray(R.array.keycode_labels);
        }
        if (keyCode > 0 && keyCode < keyLabels.length) {
            return keyLabels[keyCode - 1];
        }
        return "Unknown Key";
    }

    /**
     * 请求用户选择此绑定对应的按键。平台接下来报告的按键将成为新的绑定；游戏也是这样读取按键的：对话框打开期间会持有键盘焦点。
     */
    private void askForKey(final Binding binding) {
        new AlertDialog.Builder(this)
            .setMessage(LocalizedText.ui("ui.keyPrompt") + binding.name)
            .setNeutralButton(LocalizedText.ui("common.cancel"), null)
            .setOnKeyListener(new DialogInterface.OnKeyListener() {
                @Override
                public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                    binding.keyCode = keyCode;
                    refresh(binding);
                    dialog.dismiss();
                    return false;
                }
            })
            .show();
    }

    /** 将引擎的五个按键绑定恢复为游戏自带的默认值。 */
    private void useDefaults() {
        if (bindings.size() < 5) {
            return;
        }
        bindings.get(0).keyCode = DEFAULT_ACTION;
        bindings.get(1).keyCode = DEFAULT_JUMP;
        bindings.get(2).keyCode = DEFAULT_LEFT;
        bindings.get(3).keyCode = DEFAULT_RIGHT;
        bindings.get(4).keyCode = DEFAULT_DOWN;
        for (Binding binding : bindings) {
            refresh(binding);
        }
    }

    /** 将五个按键绑定写入引擎并保存，然后关闭界面。 */
    private void saveSettings() {
        if (saved || bindings.size() < 5) {
            finish();
            return;
        }
        saved = true;
        settings.set("keyAction", bindings.get(0).keyCode);
        settings.set("keyJump", bindings.get(1).keyCode);
        settings.set("keyLeft", bindings.get(2).keyCode);
        settings.set("keyRight", bindings.get(3).keyCode);
        settings.set("keyDown", bindings.get(4).keyCode);
        settings.save();
        finish();
    }

    /** 保留引擎现有的按键绑定。 */
    private void cancel() {
        saveChanges = false;
        finish();
    }

    @Override
    protected void onPause() {
        // 通过其他方式离开界面（例如返回键或主页键）时，会像游戏原生界面一样保留已选内容。“完成”通常已经写入设置，
        // saveSettings 会据此判断是否还需保存。
        if (saveChanges && isFinishing()) {
            saveSettings();
        }
        super.onPause();
    }

    private void onClick(int id, Runnable action) {
        View view = findViewById(id);
        if (view == null) {
            Log.w(TAG, "The keyboard layout has no view for " + id + ".");
            return;
        }
        view.setOnClickListener(clicked -> action.run());
    }
}
