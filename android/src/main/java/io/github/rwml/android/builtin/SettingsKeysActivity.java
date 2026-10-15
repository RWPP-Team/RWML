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
 * The keyboard bindings, laid out by the game's own keyboard layout and held in the engine's own
 * {@link GameSettings}.
 *
 * <p>Five bindings are configurable: the action key, the jump key and the four directions. Each
 * button shows the key it currently answers to, named from the game's own key-code table; pressing
 * one asks for the key to use instead, and the next key pressed becomes the binding. Done writes the
 * five into the engine and saves them, Cancel leaves them alone, and Defaults puts back the four
 * directions with the action key in the middle, which is what the game's own screen does.</p>
 */
public final class SettingsKeysActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    /** The engine's own defaults, which the Defaults button restores: middle, up, left, right, down. */
    private static final int DEFAULT_ACTION = KeyEvent.KEYCODE_DPAD_CENTER;
    private static final int DEFAULT_JUMP = KeyEvent.KEYCODE_DPAD_UP;
    private static final int DEFAULT_LEFT = KeyEvent.KEYCODE_DPAD_LEFT;
    private static final int DEFAULT_RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT;
    private static final int DEFAULT_DOWN = KeyEvent.KEYCODE_DPAD_DOWN;

    /** One binding: the button that shows it, what it is called, and the key code it holds. */
    private static final class Binding {
        final Button button;
        final String name;
        int keyCode;

        Binding(Button button, String name, int keyCode) {
            this.button = button;
            this.name = name;
            this.keyCode = keyCode;
        }

        /** Writes the binding onto its button, in the game's words for the key code. */
        void show(String label) {
            button.setText(name + ": " + label);
        }
    }

    private GameSettings settings;
    private final List<Binding> bindings = new ArrayList<>();

    /** The game's key-code names, read once; the code is an index into them, one-based. */
    private String[] keyLabels;

    /** Whether Done was pressed: Cancel and a plain close leave the engine's bindings alone. */
    private boolean saveChanges = true;

    /** Whether the bindings have already been written, so that closing does not write them twice. */
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

    /** Attaches one binding to its button, if the layout carries that button. */
    private void bind(int id, String name, int keyCode) {
        Button button = findViewById(id);
        if (button == null) {
            Log.w(TAG, "The keyboard layout has no view for " + id + ".");
            return;
        }
        bindings.add(new Binding(button, name, keyCode));
    }

    /** Shows the key one binding currently holds. */
    private void refresh(Binding binding) {
        binding.show(keyLabel(binding.keyCode));
    }

    /** The game's name for a key code, or a plain spelling when its table has no entry. */
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
     * Asks which key the binding should answer to. The next key the platform reports becomes the
     * binding, which is how the game reads it: the dialog holds the keyboard focus while it is up.
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

    /** Puts the engine's five bindings back to the defaults the game ships with. */
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

    /** Writes the five bindings into the engine, saves them, and closes the screen. */
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

    /** Leaves the engine's bindings as they were. */
    private void cancel() {
        saveChanges = false;
        finish();
    }

    @Override
    protected void onPause() {
        // Leaving the screen any other way - the back key, the home key - keeps what was chosen, as
        // the game's own screen does. Done has usually written it already; saveSettings says so.
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
