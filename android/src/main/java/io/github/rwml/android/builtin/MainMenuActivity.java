/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.corrodinggames.rts.gameFramework.GameEngine;
import io.github.rwml.api.Rwml;
import io.github.rwml.api.ui.GameUi;
import io.github.rwml.api.ui.GameSettings;

import io.github.rwml.android.R;

/**
 * 菜单：启动画面交接到的界面，也是进入游戏的入口。
 *
 * <p>布局和交互都沿用游戏自身实现：按钮名称来自游戏语言文件，版本行由引擎提供，按钮是否显示也按游戏菜单的规则决定。
 * 每个按钮都会打开游戏对应的界面：新单人游戏的模式选择器、多人游戏大厅、设置界面、模组列表或帮助幻灯片。</p>
 *
 * <p>此界面出现时引擎已经启动，因此这里只读取引擎状态，不会启动引擎；如果引擎缺失，就退回显示布局中的原始文本。</p>
 */
public final class MainMenuActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    /** 演示版购买按钮打开的商店页面。本构建版本不是演示版。 */
    private static final String STORE_PACKAGE = "com.corrodinggames.rts";

    /** 引擎绘制菜单背景地图所用的表面，位于布局后方。 */
    private AndroidGameView background;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!AndroidApp.prepare(this, true)) { return; }

        // 菜单与游戏界面一样，叠加在引擎自身的绘制内容之上：引擎在后方绘制地图，布局在上方放置按钮。顺序就是全部关键，
        // 因此在此处填充容器，而不是写入布局文件。
        FrameLayout root = new FrameLayout(this);
        background = new AndroidGameView(this);
        root.addView(background, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        getLayoutInflater().inflate(R.layout.menu, root);
        setContentView(root);

        onClick(R.id.buyButton, this::openStorePage);
        onClick(R.id.startgameButton, this::openGame);
        onClick(R.id.menuCustomButton, this::openSinglePlayer);
        onClick(R.id.helpButton, this::openHelp);
        onClick(R.id.exitgameButton, this::finish);

        onClick(R.id.multiplayerButton, this::openMultiplayer);
        onClick(R.id.settingsButton, this::openSettings);
        onClick(R.id.modsButton, this::openMods);

        Log.i(TAG, "Menu open.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!AndroidApp.prepare(this, true)) { return; }
        showBackground();
        setButtonText();
        setup();
    }

    @Override protected void onPause() {
        if (background != null) { background.setDrawingEnabled(false); }
        super.onPause();
    }

    /**
     * 在菜单后方显示引擎自身绘制的背景。
     *
     * <p>通过引擎自身的 attach 调用交接绘制表面即可：如果引擎既未加载地图，也没有正在加载的对局，该调用会显示菜单背景地图；
     * 如果游戏仍在运行，则不会干扰它，从而确保“继续”按钮状态正确。游戏原生界面只执行此操作，本界面也一样；如果在此手动
     * 加载背景，就会替换玩家暂停的游戏。</p>
     */
    private void showBackground() {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null || background == null) {
            return;
        }
        try {
            background.setDrawingEnabled(true);
            engine.setActivityAndGameView(this, background, true);
            background.pushScreenSize();
        } catch (Throwable t) {
            // 没有背景的菜单仍然可以使用，应报告该失败，但不应因此关闭整个界面。
            Log.w(TAG, "The engine's menu background could not be brought up.", t);
        }
    }

    /**
     * 根据游戏状态显示或隐藏控件：购买和模组按钮属于演示版；只有引擎中仍有尚未结束的游戏时，才显示继续按钮。
     *
     * <p>判断依据是引擎自身的加载状态，而不是启动游戏时使用的网络模式：loop 标志表示地图已加载且帧循环正在运行，ended
     * 标志表示游戏已经结束，路径检查则排除仅作为菜单背景加载的地图。游戏原生菜单也会检查这三项内容。</p>
     */
    private void setup() {
        GameUi.Snapshot game = Rwml.get().ui().game();
        View buy = findViewById(R.id.buyButton);
        if (buy != null) { buy.setVisibility(game.demo() ? View.VISIBLE : View.GONE); }
        View mods = findViewById(R.id.modsButton);
        if (mods != null) { mods.setVisibility(game.demo() ? View.GONE : View.VISIBLE); }
        View resume = findViewById(R.id.startgameButton);
        if (resume != null) { resume.setVisibility(game.canResume() ? View.VISIBLE : View.GONE); }
        TextView version = findViewById(R.id.titleInfo);
        if (version != null) { version.setText(game.version()); }
    }

    /** 使用游戏自身文本为按钮命名；无法读取时保留布局中的原始文本。 */
    private void setButtonText() {
        try {
            text(R.id.buyButton, LocalizedText.get("menus.front.buyNow"));
            text(R.id.startgameButton, LocalizedText.get("menus.front.continue"));
            text(R.id.menuCustomButton, LocalizedText.get("menus.front.singlePlayer"));
            text(R.id.multiplayerButton, LocalizedText.get("menus.front.multiplayer"));
            text(R.id.settingsButton, LocalizedText.get("menus.front.settings"));
            text(R.id.helpButton, LocalizedText.get("menus.front.help"));
            text(R.id.modsButton, LocalizedText.get("menus.front.mods"));
            text(R.id.exitgameButton, LocalizedText.get("menus.front.exit"));
        } catch (Throwable t) {
            Log.w(TAG, "The game's language file did not answer; the layout's text stands.", t);
        }
    }

    /** 打开游戏界面，继续当前正在进行的游戏。 */
    private void openGame() {
        Log.i(TAG, "Opening the game screen.");
        startActivity(new Intent(this, InGameActivity.class));
    }

    /** 开始新的单人游戏，首先进入模式选择界面。 */
    private void openSinglePlayer() {
        checkIfHelpShouldBeShown(this::openLevelSelect);
    }

    /** 打开模式选择界面，这是游戏自身进入单人游戏的流程。 */
    private void openLevelSelect() {
        Log.i(TAG, "Opening the single-player mode chooser.");
        startActivity(new Intent(this, LevelGroupSelectActivity.class));
    }

    /** 打开多人游戏大厅，从此处开始创建或加入游戏。 */
    private void openMultiplayer() {
        startActivity(new Intent(this, MultiplayerLobbyActivity.class));
    }

    /** 打开设置界面。 */
    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /** 打开模组列表。 */
    private void openMods() {
        startActivity(new Intent(this, ModsActivity.class));
    }

    /** 打开帮助幻灯片。 */
    private void openHelp() {
        startActivity(new Intent(this, QuickHelpActivity.class));
    }

    /**
     * 首次启动游戏时显示帮助幻灯片，然后执行用户请求的操作。
     *
     * <p>此提示沿用游戏自身逻辑：只显示一次，用户的选择会保存到游戏设置中，查看幻灯片也会将其标记为已读。</p>
     */
    private void checkIfHelpShouldBeShown(Runnable then) {
        if (Rwml.get().ui().find(GameSettings.class).get("hasPlayedGameOrSeenHelp", Boolean.class)) {
            then.run();
            return;
        }
        new AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_info)
            .setTitle(Rwml.get().ui().messages().text("menu.firstPlay"))
            .setMessage(Rwml.get().ui().messages().text("menu.helpPrompt"))
            .setPositiveButton(Rwml.get().ui().messages().text("common.yes"), (dialog, which) -> {
                openHelp();
                then.run();
            })
            .setNegativeButton(Rwml.get().ui().messages().text("common.no"), (dialog, which) -> then.run())
            .show();
    }

    /** 打开游戏商店页面，与演示版购买按钮的行为一致。 */
    private void openStorePage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=" + STORE_PACKAGE)));
        } catch (ActivityNotFoundException noMarket) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("http://play.google.com/store/apps/details?id=" + STORE_PACKAGE)));
            } catch (ActivityNotFoundException noBrowser) {
                Toast.makeText(this, Rwml.get().ui().messages().text("menu.storeFailed"), Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------------------------------------------------------------
    // 按名称查找布局
    // ------------------------------------------------------------------

    private void onClick(int id, Runnable action) {
        View view = findViewById(id);
        if (view == null) {
            Log.w(TAG, "The menu layout has no view for " + id + ".");
            return;
        }
        view.setOnClickListener(clicked -> action.run());
    }

    private void text(int id, String value) {
        View view = findViewById(id);
        if (view instanceof TextView && value != null) {
            ((TextView) view).setText(value);
        }
    }
}
