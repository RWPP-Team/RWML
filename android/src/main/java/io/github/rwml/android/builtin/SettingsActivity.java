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
 * 设置界面：使用游戏自身的设置布局，并由游戏自身的 {@link GameSettings} 提供数据。界面打开时会从引擎读取布局中的所有控件值，
 * 点击“完成”关闭时写回；点击“取消”则不写入。
 *
 * <p>布局会在游戏本地化键前加上 {@code i:} 标记，以便标识标签；布局本身无法查询引擎语言文件。界面显示后，
 * {@link #applyLocaleText} 会将这些标记替换为引擎中的文本。键盘绑定会打开独立界面，并写入同一份引擎设置。外部存储设置属于
 * Android 版本文件选择器的功能，本宿主未携带该选择器，因此会隐藏此项。</p>
 */
public final class SettingsActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    /** 队伍单位上限下拉框的选项，顺序与布局一致。 */
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

    /** 使用与游戏布局相同的 ID 查找布局中声明的所有控件。 */
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
     * 隐藏此宿主无法支持的功能：外部存储（SAF）设置，以及 Android 版本录制流程中的视频录制。键盘配置按钮仍然保留，
     * 因为本宿主包含该按钮打开的界面。
     */
    private void hideUnsupported() {
        hide(R.id.settingsSetupExternalFolder);
        hide(R.id.settingsExternalFolderInfo);
        hide(R.id.settingsStorageLayout);
        hide(R.id.settingsAllowGameRecording);
        // 依赖其他控件状态的可见性由 updateDependentVisibility() 处理。
    }

    private void hide(int id) {
        View view = findViewById(id);
        if (view != null) {
            view.setVisibility(View.GONE);
        }
    }

    /** 使用引擎当前设置填充所有控件。 */
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

    /** 将所有控件的值写回引擎设置并保存，然后关闭界面。 */
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

    /** 打开制作人员名单界面。 */
    private void openCredits() {
        startActivity(new android.content.Intent(this, CreditsActivity.class));
    }

    /** 打开模组界面。 */
    private void openMods() {
        startActivity(new android.content.Intent(this, ModsActivity.class));
    }

    /** 打开按键绑定界面；绑定信息写入同一组引擎设置。 */
    private void openKeyboardSettings() {
        startActivity(new android.content.Intent(this, SettingsKeysActivity.class));
    }

    /**
     * Android 版本的调试控制台。这里会将其中几个命令连接到它们操作的引擎字段；其他命令会明确报告为未知，而不会静默忽略。
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

    /** 仅在启用新渲染器时显示着色器开关，与游戏界面行为一致。 */
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
    // 辅助方法
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

    /** 选择值等于 {@code value} 的单位上限选项；未匹配时默认选择第一项。 */
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
     * 将布局中以 {@code i:} 标记的所有标签替换为引擎自身的文本。此标记用于弥补布局无法查询引擎语言文件的限制；
     * 本方法负责读取并替换标记内容。
     */
    private void applyLocaleText() {
        LocalizedText.applyTo(findViewById(android.R.id.content));
    }

    /** 同步百分比文本与滑动条的值。 */
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

    /** 滚动速度滑动条偏移 20，因此标签需显示引擎实际保存的值。 */
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
