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

import io.github.rwml.android.R;
import io.github.rwml.api.Rwml;
import io.github.rwml.api.ui.GameSettings;

import java.io.IOException;

/**
 * The settings screen, laid out by the game's own settings layout and backed by the game's own
 * {@link GameSettings}. Every control the layout carries is read from the engine when the screen
 * opens and written back when it is closed with Done; Cancel closes without writing.
 *
 * <p>The layout names its labels with the game's locale keys behind an {@code i:} marker, because a
 * layout cannot call into the engine's language file; {@link #applyLocaleText} replaces those markers
 * with the engine's words once the screen is up. The keyboard bindings open their own screen, which
 * writes into the same engine settings. The external-storage setup belongs to the Android build's
 * file picker, which this host does not carry, so it is hidden.</p>
 */
public final class SettingsActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    /** The choices behind the team unit-cap spinners, in the layout's order. */
    private static final int[] UNIT_CAP_OPTIONS = {100, 250, 500, 1000, 2000, 5000, 10000};

    private GameSettings settings;

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
        setTitle(LocalizedText.ui("ui.settings"));
        AndroidApp.prepare(this, true);
        setContentView(R.layout.settings);

        settings = Rwml.get().ui().find(GameSettings.class);
        if (settings == null) {
            Log.w(TAG, "The settings engine is not up; the screen cannot bind.");
            Toast.makeText(this, LocalizedText.ui("ui.notLoaded"), Toast.LENGTH_SHORT).show();
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
        setChecked(enableSounds, settings.get("enableSounds", Boolean.class));
        musicVolume.setProgress((int) (settings.get("musicVolume", Float.class) * 100.0F));
        musicVolumeText.setText(musicVolume.getProgress() + "%");
        gameVolume.setProgress((int) (settings.get("gameVolume", Float.class) * 100.0F));
        gameVolumeText.setText(gameVolume.getProgress() + "%");
        interfaceVolume.setProgress((int) (settings.get("interfaceVolume", Float.class) * 100.0F));
        interfaceVolumeText.setText(interfaceVolume.getProgress() + "%");
        scrollSpeed.setProgress((int) (settings.get("scrollSpeed", Float.class) * 100.0F - 20.0F));
        scrollSpeedText.setText(scrollSpeed.getProgress() + 20 + "%");

        setChecked(batterySaving, settings.get("batterySaving", Boolean.class));
        setChecked(highRefreshRate, settings.get("highRefreshRate", Boolean.class));
        setChecked(unlockedScreenRotation, settings.get("unlockedScreenRotation", Boolean.class));
        setChecked(showUnitWaypoints, settings.get("showUnitWaypoints", Boolean.class));
        setChecked(useMinimapAllyColors, settings.get("useMinimapAllyColors", Boolean.class));
        setChecked(showUnitGroups, settings.get("showUnitGroups", Boolean.class));
        setChecked(showHp, settings.get("showHp", Boolean.class));
        setChecked(showUnitIcons, settings.get("showUnitIcons", Boolean.class));
        setChecked(gestureZoom, settings.get("gestureZoom", Boolean.class));
        setChecked(useCircleSelect, settings.get("useCircleSelect", Boolean.class));
        setChecked(smartSelection, settings.get("smartSelection_v2", Boolean.class));
        setChecked(quickRally, settings.get("quickRally", Boolean.class));
        setChecked(doubleClickToAttackMove, settings.get("doubleClickToAttackMove", Boolean.class));
        setChecked(zoomButton, settings.get("showZoomButton", Boolean.class));
        setChecked(mouseSupport, settings.get("mouseSupport", Boolean.class));
        setChecked(keyboardSupport, settings.get("keyboardSupport", Boolean.class));
        setChecked(forceEnglish, settings.get("forceEnglish", Boolean.class));
        setChecked(saveMultiplayerReplays, settings.get("saveMultiplayerReplays", Boolean.class));
        setChecked(replaysShowRecordedChat, settings.get("replaysShowRecordedChat", Boolean.class));
        setChecked(allowGameRecording, settings.get("allowGameRecording", Boolean.class));
        setChecked(showFps, settings.get("showFps", Boolean.class));
        setChecked(newRender, settings.get("newRender", Boolean.class));
        setChecked(shaderEffects, settings.get("shaderEffects", Boolean.class));
        setChecked(teamShaders, settings.get("teamShaders", Boolean.class));
        setChecked(sendReports, settings.get("sendReports", Boolean.class));
        setChecked(immersiveFullScreen, settings.get("immersiveFullScreen", Boolean.class));
        setChecked(renderDoubleScale, settings.get("renderDoubleScale", Boolean.class));
        setChecked(renderClouds, settings.get("renderClouds", Boolean.class));
        setChecked(showWarLogOnScreen, settings.get("showWarLogOnScreen", Boolean.class));
        setChecked(classicInterface, settings.get("classicInterface", Boolean.class));
        setChecked(udpInMultiplayer, settings.get("udpInMultiplayer", Boolean.class));
        setChecked(showMapPingsOnBattlefield, settings.get("showMapPingsOnBattlefield", Boolean.class));
        setChecked(showMapPingsOnMinimap, settings.get("showMapPingsOnMinimap", Boolean.class));
        setChecked(showPlayerChatInGame, settings.get("showPlayerChatInGame", Boolean.class));
        setChecked(autoSaveEnabled, settings.get("autosaving", Boolean.class));

        aiDifficulty.setSelection(settings.get("aiDifficulty", Integer.class) + 2);
        storageType.setSelection(settings.get("storageType", Integer.class));
        setSpinnerByValue(teamUnitCapSinglePlayer, settings.get("teamUnitCapSinglePlayer", Integer.class));
        setSpinnerByValue(teamUnitCapHostedGame, settings.get("teamUnitCapHostedGame", Integer.class));
        networkPort.setText(Integer.toString(settings.get("networkPort", Integer.class)));

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
        settings.set("enableSounds", true);
        settings.set("musicVolume", musicVolume.getProgress() / 100.0F);
        settings.set("gameVolume", gameVolume.getProgress() / 100.0F);
        settings.set("interfaceVolume", interfaceVolume.getProgress() / 100.0F);
        settings.set("scrollSpeed", (scrollSpeed.getProgress() + 20.0F) / 100.0F);
        settings.set("batterySaving", batterySaving.isChecked());
        settings.set("highRefreshRate", highRefreshRate.isChecked());
        settings.set("unlockedScreenRotation", unlockedScreenRotation.isChecked());
        settings.set("showUnitWaypoints", showUnitWaypoints.isChecked());
        settings.set("useMinimapAllyColors", useMinimapAllyColors.isChecked());
        settings.set("showUnitGroups", showUnitGroups.isChecked());
        settings.set("showHp", showHp.isChecked());
        settings.set("showUnitIcons", showUnitIcons.isChecked());
        settings.set("gestureZoom", gestureZoom.isChecked());
        settings.set("useCircleSelect", useCircleSelect.isChecked());
        settings.set("smartSelection_v2", smartSelection.isChecked());
        settings.set("quickRally", quickRally.isChecked());
        settings.set("doubleClickToAttackMove", doubleClickToAttackMove.isChecked());
        settings.set("showZoomButton", zoomButton.isChecked());
        settings.set("mouseSupport", mouseSupport.isChecked());
        settings.set("keyboardSupport", keyboardSupport.isChecked());
        settings.set("forceEnglish", forceEnglish.isChecked());
        settings.set("saveMultiplayerReplays", saveMultiplayerReplays.isChecked());
        settings.set("replaysShowRecordedChat", replaysShowRecordedChat.isChecked());
        settings.set("allowGameRecording", allowGameRecording.isChecked());
        settings.set("showFps", showFps.isChecked());
        settings.set("newRender", newRender.isChecked());
        settings.set("shaderEffects", shaderEffects.isChecked());
        settings.set("teamShaders", teamShaders.isChecked());
        settings.set("sendReports", sendReports.isChecked());
        settings.set("immersiveFullScreen", immersiveFullScreen.isChecked());
        settings.set("renderDoubleScale", renderDoubleScale.isChecked());
        settings.set("renderClouds", renderClouds.isChecked());
        settings.set("showWarLogOnScreen", showWarLogOnScreen.isChecked());
        settings.set("classicInterface", classicInterface.isChecked());
        settings.set("udpInMultiplayer", udpInMultiplayer.isChecked());
        settings.set("showMapPingsOnBattlefield", showMapPingsOnBattlefield.isChecked());
        settings.set("showMapPingsOnMinimap", showMapPingsOnMinimap.isChecked());
        settings.set("showPlayerChatInGame", showPlayerChatInGame.isChecked());
        settings.set("autosaving", autoSaveEnabled.isChecked());

        settings.set("teamUnitCapSinglePlayer", valueAt(teamUnitCapSinglePlayer));
        settings.set("teamUnitCapHostedGame", valueAt(teamUnitCapHostedGame));
        settings.set("aiDifficulty", aiDifficulty.getSelectedItemPosition() - 2);
        settings.set("storageType", storageType.getSelectedItemPosition());

        try {
            settings.set("networkPort", Integer.parseInt(networkPort.getText().toString().trim()));
        } catch (NumberFormatException notANumber) {
            Log.w(TAG, "The network port is not a number; leaving it unchanged.");
        }
        if (settings.get("networkPort", Integer.class) < 1024 || settings.get("networkPort", Integer.class) > 65535) {
            settings.set("networkPort", 5123);
        }

        try {
            boolean english = settings.get("forceEnglish", Boolean.class);
            if (english) { Rwml.get().ui().messages().language("en"); }
            else if ("en".equals(Rwml.get().ui().messages().language())) {
                Rwml.get().ui().messages().language("system");
            }
        } catch (IOException failure) {
            Log.w(TAG, "Cannot save UI language", failure);
            Toast.makeText(this, LocalizedText.ui("common.error") + ": " + failure.getMessage(), Toast.LENGTH_LONG).show();
            return;
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
        input.setHint(LocalizedText.ui("ui.debugHint"));
        new android.app.AlertDialog.Builder(this)
            .setTitle(LocalizedText.ui("ui.debugTitle"))
            .setView(input)
            .setPositiveButton(LocalizedText.ui("ui.apply"), (dialog, which) -> applyDebugOption(input.getText().toString()))
            .setNegativeButton(LocalizedText.ui("common.cancel"), null)
            .show();
    }

    private void applyDebugOption(String raw) {
        String option = raw == null ? "" : raw.toLowerCase().trim().replace("  ", " ");
        String result;
        if (option.equals("show fps")) {
            settings.set("showFps", true);
            result = "Enabled fps";
        } else if (option.equals("show hp on") || option.equals("showhp on")) {
            settings.set("showHp", true);
            result = "Enabled showHp";
        } else if (option.equals("show hp off") || option.equals("showhp off")) {
            settings.set("showHp", false);
            result = "Disabled showHp";
        } else if (option.equals("autosave off")) {
            settings.set("autosaving", false);
            result = "Disabled autosave";
        } else if (option.equals("autosave on")) {
            settings.set("autosaving", true);
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
        LocalizedText.applyTo(findViewById(android.R.id.content));
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
