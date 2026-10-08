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
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.corrodinggames.rts.gameFramework.SettingsEngine;
import com.corrodinggames.rts.gameFramework.localization.LocaleEngine;

import io.github.rwml.android.R;

/**
 * The settings screen, laid out by the game's own settings layout and backed by the game's own
 * {@link SettingsEngine}. Every control the layout carries is read from the engine when the screen
 * opens and written back when it is closed with Done; Cancel closes without writing.
 *
 * <p>The layout names its labels with the game's locale keys behind an {@code i:} marker, because a
 * layout cannot call into the engine's language file; {@link #applyLocaleText} replaces those markers
 * with the engine's words once the screen is up. The keyboard bindings open their own screen, which
 * writes into the same engine settings. The external-storage setup belongs to the Android build's
 * file picker, which this host does not carry, so it is hidden.</p>
 */
public final class SettingsActivity extends Activity {

    private static final String TAG = "RWML";

    /** The choices behind the team unit-cap spinners, in the layout's order. */
    private static final int[] UNIT_CAP_OPTIONS = {100, 250, 500, 1000, 2000, 5000, 10000};

    private SettingsEngine settings;

    private CheckBox enableSounds;
    private SeekBar musicVolume;
    private TextView musicVolumeText;
    private SeekBar gameVolume;
    private TextView gameVolumeText;
    private SeekBar interfaceVolume;
    private TextView interfaceVolumeText;
    private SeekBar scrollSpeed;
    private TextView scrollSpeedText;
    private CheckBox batterySaving;
    private CheckBox highRefreshRate;
    private CheckBox unlockedScreenRotation;
    private CheckBox showUnitWaypoints;
    private CheckBox showUnitGroups;
    private CheckBox gestureZoom;
    private CheckBox useCircleSelect;
    private CheckBox smartSelection;
    private CheckBox quickRally;
    private CheckBox doubleClickToAttackMove;
    private CheckBox zoomButton;
    private CheckBox mouseSupport;
    private CheckBox keyboardSupport;
    private CheckBox forceEnglish;
    private Spinner teamUnitCapSinglePlayer;
    private Spinner teamUnitCapHostedGame;
    private CheckBox saveMultiplayerReplays;
    private CheckBox replaysShowRecordedChat;
    private CheckBox allowGameRecording;
    private CheckBox showHp;
    private CheckBox showFps;
    private CheckBox newRender;
    private CheckBox shaderEffects;
    private CheckBox teamShaders;
    private CheckBox sendReports;
    private CheckBox showUnitIcons;
    private CheckBox autoSaveEnabled;
    private CheckBox immersiveFullScreen;
    private CheckBox renderDoubleScale;
    private CheckBox renderClouds;
    private CheckBox showWarLogOnScreen;
    private CheckBox classicInterface;
    private CheckBox useMinimapAllyColors;
    private Spinner aiDifficulty;
    private Spinner storageType;
    private EditText networkPort;
    private CheckBox udpInMultiplayer;
    private CheckBox showMapPingsOnBattlefield;
    private CheckBox showMapPingsOnMinimap;
    private CheckBox showPlayerChatInGame;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Settings");
        AndroidApp.prepare(this, true);
        setContentView(R.layout.settings);

        settings = SettingsEngine.getInstance(getBaseContext());
        if (settings == null) {
            Log.w(TAG, "The settings engine is not up; the screen cannot bind.");
            Toast.makeText(this, "The game is not loaded yet.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        findControls();
        hideUnsupported();
        loadFromEngine();
        wireListeners();
        applyLocaleText();
        Log.i(TAG, "Settings open.");
    }

    /** Looks up every control the layout names, by the same ids the game's layout uses. */
    private void findControls() {
        enableSounds = findViewById(R.id.SettingsEnableSounds);
        musicVolume = findViewById(R.id.musicVolume);
        musicVolumeText = findViewById(R.id.musicVolumeText);
        gameVolume = findViewById(R.id.gameVolume);
        gameVolumeText = findViewById(R.id.gameVolumeText);
        interfaceVolume = findViewById(R.id.interfaceVolume);
        interfaceVolumeText = findViewById(R.id.interfaceVolumeText);
        scrollSpeed = findViewById(R.id.scrollSpeed);
        scrollSpeedText = findViewById(R.id.scrollSpeedText);
        batterySaving = findViewById(R.id.settingsBatterySaving);
        highRefreshRate = findViewById(R.id.settingsHighRefreshRate);
        unlockedScreenRotation = findViewById(R.id.settingsUnlockedScreenRotation);
        showUnitWaypoints = findViewById(R.id.settingsShowUnitWaypoints);
        showUnitGroups = findViewById(R.id.settingsShowUnitGroups);
        gestureZoom = findViewById(R.id.settingsGestureZoom);
        useCircleSelect = findViewById(R.id.settingsUseCircleSelect);
        smartSelection = findViewById(R.id.settingsSmartSelection);
        quickRally = findViewById(R.id.settingsQuickRally);
        doubleClickToAttackMove = findViewById(R.id.settingsDoubleClickToAttackMove);
        zoomButton = findViewById(R.id.settingsZoomButton);
        mouseSupport = findViewById(R.id.settingsMouseSupport);
        keyboardSupport = findViewById(R.id.settingsKeyboardSupport);
        forceEnglish = findViewById(R.id.settingsForceEnglish);
        teamUnitCapSinglePlayer = findViewById(R.id.teamUnitCapSinglePlayer);
        teamUnitCapHostedGame = findViewById(R.id.teamUnitCapHostedGame);
        saveMultiplayerReplays = findViewById(R.id.settingsSaveMultiplayerReplays);
        replaysShowRecordedChat = findViewById(R.id.settingsReplaysShowRecordedChat);
        allowGameRecording = findViewById(R.id.settingsAllowGameRecording);
        showHp = findViewById(R.id.settingsShowHp);
        showFps = findViewById(R.id.settingsShowFps);
        newRender = findViewById(R.id.settingsNewRender);
        shaderEffects = findViewById(R.id.settingsShaderEffects);
        teamShaders = findViewById(R.id.settingsTeamShaders);
        sendReports = findViewById(R.id.settingsSendReports);
        showUnitIcons = findViewById(R.id.settingsShowUnitIcons);
        autoSaveEnabled = findViewById(R.id.settingsAutoSaveEnabled);
        immersiveFullScreen = findViewById(R.id.settingsImmersiveFullScreen);
        renderDoubleScale = findViewById(R.id.settingsRenderDoubleScale);
        renderClouds = findViewById(R.id.settingsRenderClouds);
        showWarLogOnScreen = findViewById(R.id.settingsShowWarLogOnScreen);
        classicInterface = findViewById(R.id.settingsClassicInterface);
        useMinimapAllyColors = findViewById(R.id.settingsUseMinimapAllyColors);
        aiDifficulty = findViewById(R.id.aiDifficulty);
        storageType = findViewById(R.id.storageType);
        networkPort = findViewById(R.id.settingsNetworkPort);
        udpInMultiplayer = findViewById(R.id.settingsUdpInMultiplayer);
        showMapPingsOnBattlefield = findViewById(R.id.settingsShowMapPingsOnBattlefield);
        showMapPingsOnMinimap = findViewById(R.id.settingsShowMapPingsOnMinimap);
        showPlayerChatInGame = findViewById(R.id.settingsShowPlayerChatInGame);
    }

    /**
     * Hides what this host cannot honour: the external-storage (SAF) setup and the video recording
     * that belongs to the Android build's capture path. The keyboard-configuration button stays,
     * because this host carries the screen it opens.
     */
    private void hideUnsupported() {
        hide(R.id.settingsSetupExternalFolder);
        hide(R.id.settingsExternalFolderInfo);
        hide(R.id.settingsStorageLayout);
        hide(R.id.settingsAllowGameRecording);
        // Visibility that depends on another control is applied by updateDependentVisibility().
    }

    private void hide(int id) {
        View view = findViewById(id);
        if (view != null) {
            view.setVisibility(View.GONE);
        }
    }

    /** Fills every control from the engine's current settings. */
    private void loadFromEngine() {
        setChecked(enableSounds, settings.enableSounds);
        musicVolume.setProgress((int) (settings.musicVolume * 100.0F));
        musicVolumeText.setText(musicVolume.getProgress() + "%");
        gameVolume.setProgress((int) (settings.gameVolume * 100.0F));
        gameVolumeText.setText(gameVolume.getProgress() + "%");
        interfaceVolume.setProgress((int) (settings.interfaceVolume * 100.0F));
        interfaceVolumeText.setText(interfaceVolume.getProgress() + "%");
        scrollSpeed.setProgress((int) (settings.scrollSpeed * 100.0F - 20.0F));
        scrollSpeedText.setText(scrollSpeed.getProgress() + 20 + "%");

        setChecked(batterySaving, settings.batterySaving);
        setChecked(highRefreshRate, settings.highRefreshRate);
        setChecked(unlockedScreenRotation, settings.unlockedScreenRotation);
        setChecked(showUnitWaypoints, settings.showUnitWaypoints);
        setChecked(useMinimapAllyColors, settings.useMinimapAllyColors);
        setChecked(showUnitGroups, settings.showUnitGroups);
        setChecked(showHp, settings.showHp);
        setChecked(showUnitIcons, settings.showUnitIcons);
        setChecked(gestureZoom, settings.gestureZoom);
        setChecked(useCircleSelect, settings.useCircleSelect);
        setChecked(smartSelection, settings.smartSelection_v2);
        setChecked(quickRally, settings.quickRally);
        setChecked(doubleClickToAttackMove, settings.doubleClickToAttackMove);
        setChecked(zoomButton, settings.showZoomButton);
        setChecked(mouseSupport, settings.mouseSupport);
        setChecked(keyboardSupport, settings.keyboardSupport);
        setChecked(forceEnglish, settings.forceEnglish);
        setChecked(saveMultiplayerReplays, settings.saveMultiplayerReplays);
        setChecked(replaysShowRecordedChat, settings.replaysShowRecordedChat);
        setChecked(allowGameRecording, settings.allowGameRecording);
        setChecked(showFps, settings.showFps);
        setChecked(newRender, settings.newRender);
        setChecked(shaderEffects, settings.shaderEffects);
        setChecked(teamShaders, settings.teamShaders);
        setChecked(sendReports, settings.sendReports);
        setChecked(immersiveFullScreen, settings.immersiveFullScreen);
        setChecked(renderDoubleScale, settings.renderDoubleScale);
        setChecked(renderClouds, settings.renderClouds);
        setChecked(showWarLogOnScreen, settings.showWarLogOnScreen);
        setChecked(classicInterface, settings.classicInterface);
        setChecked(udpInMultiplayer, settings.udpInMultiplayer);
        setChecked(showMapPingsOnBattlefield, settings.showMapPingsOnBattlefield);
        setChecked(showMapPingsOnMinimap, settings.showMapPingsOnMinimap);
        setChecked(showPlayerChatInGame, settings.showPlayerChatInGame);
        setChecked(autoSaveEnabled, settings.autosaving);

        aiDifficulty.setSelection(settings.aiDifficulty + 2);
        storageType.setSelection(settings.storageType);
        setSpinnerByValue(teamUnitCapSinglePlayer, settings.teamUnitCapSinglePlayer);
        setSpinnerByValue(teamUnitCapHostedGame, settings.teamUnitCapHostedGame);
        networkPort.setText(Integer.toString(settings.networkPort));

        updateDependentVisibility();
    }

    private void wireListeners() {
        musicVolume.setOnSeekBarChangeListener(new PercentageListener(musicVolumeText));
        gameVolume.setOnSeekBarChangeListener(new PercentageListener(gameVolumeText));
        interfaceVolume.setOnSeekBarChangeListener(new PercentageListener(interfaceVolumeText));
        scrollSpeed.setOnSeekBarChangeListener(new ScrollSpeedListener());
        newRender.setOnCheckedChangeListener((button, checked) -> updateDependentVisibility());

        onClick(R.id.settingsDone, this::saveSettings);
        onClick(R.id.settingsCancel, this::finish);
        onClick(R.id.settingsCredits, this::openCredits);
        onClick(R.id.settingsMods, this::openMods);
        onClick(R.id.settingsDebugOptions, this::openDebugOptions);
        onClick(R.id.settingsConfKeys, this::openKeyboardSettings);
    }

    /** Writes every control back into the engine's settings, saves them, and closes the screen. */
    private void saveSettings() {
        settings.enableSounds = true;
        settings.musicVolume = musicVolume.getProgress() / 100.0F;
        settings.gameVolume = gameVolume.getProgress() / 100.0F;
        settings.interfaceVolume = interfaceVolume.getProgress() / 100.0F;
        settings.scrollSpeed = (scrollSpeed.getProgress() + 20.0F) / 100.0F;
        settings.batterySaving = batterySaving.isChecked();
        settings.highRefreshRate = highRefreshRate.isChecked();
        settings.unlockedScreenRotation = unlockedScreenRotation.isChecked();
        settings.showUnitWaypoints = showUnitWaypoints.isChecked();
        settings.useMinimapAllyColors = useMinimapAllyColors.isChecked();
        settings.showUnitGroups = showUnitGroups.isChecked();
        settings.showHp = showHp.isChecked();
        settings.showUnitIcons = showUnitIcons.isChecked();
        settings.gestureZoom = gestureZoom.isChecked();
        settings.useCircleSelect = useCircleSelect.isChecked();
        settings.smartSelection_v2 = smartSelection.isChecked();
        settings.quickRally = quickRally.isChecked();
        settings.doubleClickToAttackMove = doubleClickToAttackMove.isChecked();
        settings.showZoomButton = zoomButton.isChecked();
        settings.mouseSupport = mouseSupport.isChecked();
        settings.keyboardSupport = keyboardSupport.isChecked();
        settings.forceEnglish = forceEnglish.isChecked();
        settings.saveMultiplayerReplays = saveMultiplayerReplays.isChecked();
        settings.replaysShowRecordedChat = replaysShowRecordedChat.isChecked();
        settings.allowGameRecording = allowGameRecording.isChecked();
        settings.showFps = showFps.isChecked();
        settings.newRender = newRender.isChecked();
        settings.shaderEffects = shaderEffects.isChecked();
        settings.teamShaders = teamShaders.isChecked();
        settings.sendReports = sendReports.isChecked();
        settings.immersiveFullScreen = immersiveFullScreen.isChecked();
        settings.renderDoubleScale = renderDoubleScale.isChecked();
        settings.renderClouds = renderClouds.isChecked();
        settings.showWarLogOnScreen = showWarLogOnScreen.isChecked();
        settings.classicInterface = classicInterface.isChecked();
        settings.udpInMultiplayer = udpInMultiplayer.isChecked();
        settings.showMapPingsOnBattlefield = showMapPingsOnBattlefield.isChecked();
        settings.showMapPingsOnMinimap = showMapPingsOnMinimap.isChecked();
        settings.showPlayerChatInGame = showPlayerChatInGame.isChecked();
        settings.autosaving = autoSaveEnabled.isChecked();

        settings.teamUnitCapSinglePlayer = valueAt(teamUnitCapSinglePlayer);
        settings.teamUnitCapHostedGame = valueAt(teamUnitCapHostedGame);
        settings.aiDifficulty = aiDifficulty.getSelectedItemPosition() - 2;
        settings.storageType = storageType.getSelectedItemPosition();

        try {
            settings.networkPort = Integer.parseInt(networkPort.getText().toString().trim());
        } catch (NumberFormatException notANumber) {
            Log.w(TAG, "The network port is not a number; leaving it unchanged.");
        }
        if (settings.networkPort < 1024 || settings.networkPort > 65535) {
            settings.networkPort = 5123;
        }

        settings.save();
        finish();
    }

    /** Opens the credits screen. */
    private void openCredits() {
        startActivity(new android.content.Intent(this, CreditsActivity.class));
    }

    /** Opens the mods screen. */
    private void openMods() {
        startActivity(new android.content.Intent(this, ModsActivity.class));
    }

    /** Opens the keyboard bindings, which are written into the same engine settings. */
    private void openKeyboardSettings() {
        startActivity(new android.content.Intent(this, SettingsKeysActivity.class));
    }

    /**
     * The Android build's debug console. A few of its commands are wired here, against the engine
     * fields they touch; anything else is reported as unknown rather than silently ignored.
     */
    private void openDebugOptions() {
        final EditText input = new EditText(this);
        input.setHint("debug option");
        new android.app.AlertDialog.Builder(this)
            .setTitle("Set Debug Options")
            .setView(input)
            .setPositiveButton("Apply", (dialog, which) -> applyDebugOption(input.getText().toString()))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void applyDebugOption(String raw) {
        String option = raw == null ? "" : raw.toLowerCase().trim().replace("  ", " ");
        String result;
        if (option.equals("show fps")) {
            settings.showFps = true;
            result = "Enabled fps";
        } else if (option.equals("show hp on") || option.equals("showhp on")) {
            settings.showHp = true;
            result = "Enabled showHp";
        } else if (option.equals("show hp off") || option.equals("showhp off")) {
            settings.showHp = false;
            result = "Disabled showHp";
        } else if (option.equals("autosave off")) {
            settings.autosaving = false;
            result = "Disabled autosave";
        } else if (option.equals("autosave on")) {
            settings.autosaving = true;
            result = "Enabled autosave";
        } else {
            result = "Unknown debug option: " + option;
        }
        settings.save();
        Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
    }

    /** Shows the shader toggles only while the new renderer is on, as the game's screen does. */
    private void updateDependentVisibility() {
        int visibility = newRender != null && newRender.isChecked() ? View.VISIBLE : View.GONE;
        if (shaderEffects != null) {
            shaderEffects.setVisibility(visibility);
        }
        if (teamShaders != null) {
            teamShaders.setVisibility(visibility);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void onClick(int id, Runnable action) {
        View view = findViewById(id);
        if (view == null) {
            Log.w(TAG, "The settings layout has no view for " + id + ".");
            return;
        }
        view.setOnClickListener(clicked -> action.run());
    }

    private static void setChecked(CheckBox box, boolean value) {
        if (box != null) {
            box.setChecked(value);
        }
    }

    /** Selects the unit-cap entry whose value equals {@code value}, defaulting to the first entry. */
    private void setSpinnerByValue(Spinner spinner, int value) {
        if (spinner == null) {
            return;
        }
        int selection = 0;
        for (int i = 0; i < UNIT_CAP_OPTIONS.length; i++) {
            if (UNIT_CAP_OPTIONS[i] == value) {
                selection = i;
            }
        }
        spinner.setSelection(selection);
    }

    private static int valueAt(Spinner spinner) {
        if (spinner == null) {
            return UNIT_CAP_OPTIONS[0];
        }
        int index = spinner.getSelectedItemPosition();
        if (index < 0 || index >= UNIT_CAP_OPTIONS.length) {
            return UNIT_CAP_OPTIONS[0];
        }
        return UNIT_CAP_OPTIONS[index];
    }

    /**
     * Replaces every label the layout marks with {@code i:} with the engine's own words. The marker
     * exists because a layout cannot call into the engine's language file; this is where it is read.
     */
    private void applyLocaleText() {
        View root = findViewById(android.R.id.content);
        if (root != null) {
            applyLocaleText(root);
        }
    }

    private void applyLocaleText(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (isLocaleMarker(text)) {
                String resolved = translate(text.toString().substring(2));
                if (resolved != null) {
                    ((TextView) view).setText(resolved);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyLocaleText(group.getChildAt(i));
            }
        }
    }

    private static boolean isLocaleMarker(CharSequence text) {
        return text != null && text.length() > 2 && text.charAt(0) == 'i' && text.charAt(1) == ':';
    }

    private static String translate(String key) {
        try {
            String value = LocaleEngine.getString(key);
            return value == null || value.isEmpty() ? null : value;
        } catch (Throwable t) {
            Log.w(TAG, "The language file did not answer for '" + key + "'.", t);
            return null;
        }
    }

    /** Keeps a percentage label in step with its seek bar. */
    private final class PercentageListener implements SeekBar.OnSeekBarChangeListener {
        private final TextView label;

        PercentageListener(TextView label) {
            this.label = label;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (label != null) {
                label.setText(progress + "%");
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }

    /** The scroll-speed bar is offset by 20, so its label reads the value the engine stores. */
    private final class ScrollSpeedListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (scrollSpeedText != null) {
                scrollSpeedText.setText(progress + 20 + "%");
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
