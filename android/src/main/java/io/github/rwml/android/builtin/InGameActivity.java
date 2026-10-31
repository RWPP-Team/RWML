/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.corrodinggames.rts.appFramework.AppContext;
import com.corrodinggames.rts.appFramework.GameActivity;
import com.corrodinggames.rts.gameFramework.GameEngine;

import io.github.rwml.android.AndroidGameSurface;
import io.github.rwml.android.AndroidUiHost;
import io.github.rwml.android.R;

/**
 * 游戏运行界面：持有游戏的绘制表面和输入事件流，并将事件转换为引擎可读取的格式，即 {@code TouchEvent} 和整数键码。
 *
 * <p>从此界面发起的游戏操作都由游戏自身代码处理。引擎会自行填充暂停菜单，并通过
 * {@code GameActivity.selectMenuOption} 回传所选项；该方法将选择发送到 Activity 自身的处理器，最终由
 * {@code selectMenuOptionInternal} 处理。设置、聊天和返回对战房间使用此宿主的界面和资源 ID；其他菜单项仍交由游戏处理。</p>
 *
 * <p>此界面补充了游戏 Android 版本所包含、而当前归档缺少的平台侧逻辑。{@code GameActivity} 会在自己的字段中保存绘制所用视图，
 * 但只有游戏 Android 版本中的游戏界面会为该字段赋值。当前应用挂载的归档只会读取它，不会写入，因此该字段为 null；读取该字段并
 * 通知视图焦点变化的 {@code GameActivity.onWindowFocusChanged} 也不能调用。该方法会执行两项操作，其中一项是
 * {@code AppContext.setupActivity}；此界面会直接调用它，从而在每次获得焦点时重新注册 Activity，并像原流程一样重新读取系统栏设置。
 * 焦点变化则直接转发给视图，这对应于原字段负责的另一项操作。</p>
 */
public final class InGameActivity extends GameActivity {

    private static final String TAG = "RWML";

    private static final int REQUEST_SETTINGS = 1;

    private AndroidGameView gameView;

    private AndroidGameSurface extensionSurface;
    private FrameLayout gameRoot;

    private AlertDialog chatDialog;

    /** Kept as Object so Android versions before API 33 never resolve the newer callback type. */
    private Object backInvokedCallback;

    /** 此界面是否已恢复过；如果是，之后出现的菜单才属于当前界面。 */
    private boolean resumedOnce;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        AndroidApp.prepare(this, true);

        // 游戏自身的布局是空容器：每个平台上的绘制视图都会由代码创建，因为具体使用哪个视图取决于设置中的渲染模式。
        restoreMinimapRendering();
        gameRoot = new FrameLayout(this);
        setContentView(gameRoot);
        gameView = new AndroidGameView(this);
        gameRoot.addView(gameView,
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        extensionSurface = AndroidUiHost.attachGameSurface(
            getIntent().getStringExtra(AndroidUiHost.EXTRA_GAME_SURFACE), this, gameRoot, state);
        registerSystemBackCallback();
    }

    /** @return 请求引擎用于绘制的表面 */
    public AndroidGameView gameView() {
        return gameView;
    }

    @Override
    protected void onResume() {
        super.onResume();
        AndroidApp.prepare(this, true);
        attachToEngine();
        if (gameView != null) {
            gameView.setDrawingEnabled(true);
        }
        if (extensionSurface != null) {
            extensionSurface.onHostResume();
        }
        if (!resumedOnce) {
            resumedOnce = true;
            clearLeftOverPauseMenu();
        }
    }

    @Override
    protected void onPause() {
        if (extensionSurface != null) {
            extensionSurface.onHostPause();
        }
        if (gameView != null) {
            gameView.setDrawingEnabled(false);
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (extensionSurface != null) {
            extensionSurface.onHostStop();
        }
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (extensionSurface != null) {
            extensionSurface.onHostSaveInstanceState(outState);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        unregisterSystemBackCallback();
        if (chatDialog != null) {
            chatDialog.dismiss();
        }
        if (extensionSurface != null) {
            extensionSurface.onHostDestroy(isChangingConfigurations());
        }
        super.onDestroy();
    }

    /**
     * 若上一个游戏留下了未关闭的暂停菜单，则关闭该菜单。
     *
     * <p>引擎保存的是暂停菜单状态，而不是界面本身，因此从游戏自身的暂停菜单退出时会留下已设置的暂停标志。下一个游戏就会以暂停状态启动，
     * 看起来像是游戏卡住，而不是菜单没有关闭。首次恢复时出现的菜单只能是上一次遗留的，因为当前界面还没有运行足够久来打开菜单；
     * 引擎自身的重置会将界面恢复到游戏启动时的状态，这正是游戏原生界面初始化时所调用的操作。</p>
     */
    private void clearLeftOverPauseMenu() {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null || engine.interfaceEngine == null || !engine.isGameRunning()) {
            return;
        }
        if (!engine.interfaceEngine.pauseMenuOpen) {
            return;
        }
        Log.i(TAG, "Closing the pause menu the previous game left open.");
        engine.interfaceEngine.resetInterfaceState(false);
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        // 父类处理器会读取上文所述的视图字段，因此无法调用；这里直接处理焦点变化。
        if (focused) {
            AppContext.setupActivity(this, false, true);
        }
        if (gameView != null) {
            gameView.onWindowFocusChanged(focused);
        }
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // A focused SurfaceView or extension overlay is dispatched before Activity.onKeyUp. Intercept BACK
        // at the Activity boundary so a child cannot consume it without reaching the surface lifecycle.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                dispatchBackToSurface();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return key(keyCode, true) || super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // 游戏原生版本在按下返回键时会退出游戏界面。引擎不会收到该按键：是否退出界面由平台决定，且引擎菜单已有自己的按钮。
        dispatchBackToSurface();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Route physical/adb KEYCODE_BACK directly. Recent Android versions may use the predictive-back
        // dispatcher without delivering a key event; registerSystemBackCallback covers that path.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!event.isCanceled()) {
                dispatchBackToSurface();
            }
            return true;
        }
        return key(keyCode, false) || super.onKeyUp(keyCode, event);
    }

    private void dispatchBackToSurface() {
        if (extensionSurface != null && !extensionSurface.isClosed()) {
            extensionSurface.onHostBackPressed();
        } else {
            finish();
        }
    }

    private void registerSystemBackCallback() {
        if (Build.VERSION.SDK_INT >= 33) {
            backInvokedCallback = Api33.register(this);
        }
    }

    private void unregisterSystemBackCallback() {
        if (Build.VERSION.SDK_INT >= 33 && backInvokedCallback != null) {
            Api33.unregister(this, backInvokedCallback);
            backInvokedCallback = null;
        }
    }

    /** API 33 types stay isolated from the class verifier on older Android versions. */
    private static final class Api33 {
        @SuppressLint("NewApi")
        static Object register(InGameActivity activity) {
            OnBackInvokedCallback callback = activity::dispatchBackToSurface;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback);
            return callback;
        }

        @SuppressLint("NewApi")
        static void unregister(InGameActivity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                (OnBackInvokedCallback) callback);
        }
    }

    /**
     * 将一次按键状态变化转发给引擎；所有平台上，引擎都使用 Android 键码作为按键表索引。菜单键和音量键由平台处理，
     * 返回键不会传到此处。
     */
    private boolean key(int keyCode, boolean down) {
        if (keyCode == KeyEvent.KEYCODE_MENU
            || keyCode == KeyEvent.KEYCODE_VOLUME_UP
            || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            || keyCode == KeyEvent.KEYCODE_BACK) {
            return false;
        }
        GameEngine engine = GameEngine.getInstance();
        if (engine == null) {
            return false;
        }
        engine.setKeyState(keyCode, down);
        return true;
    }

    // ------------------------------------------------------------------
    // 平台菜单
    // ------------------------------------------------------------------

    /**
     * 将平台菜单键操作转发给游戏。
     *
     * <p>这些 ID 来自 {@code GameActivity.onPrepareOptionsMenu} 创建的菜单项，调用的也是游戏自身方法：它会将选择发送到此 Activity
     * 的处理器，因此可在按键事件中安全地处理对话框。</p>
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        selectMenuOption(item.getItemId());
        return true;
    }

    /** 处理由当前应用提供 Android 界面和资源的菜单项。 */
    @Override
    public void selectMenuOptionInternal(int option) {
        switch (option) {
            case 2:
                closePauseMenu();
                startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
                break;
            case 13:
                showHostChatDialog(false);
                break;
            case 16:
                showHostChatDialog(true);
                break;
            case 21:
                // 引擎已经广播返回操作并重置对局。比赛开始时房间界面已结束，因此只需关闭当前界面即可返回大厅。
                closePauseMenu();
                finishActivity(REQUEST_SETTINGS);
                startActivity(new Intent(this, MultiplayerBattleroomActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
                break;
            default:
                super.selectMenuOptionInternal(option);
        }
    }

    /** 使用 APK 中的聊天布局和 ID，并沿用游戏公开频道与队伍频道的消息处理流程。 */
    private void showHostChatDialog(boolean teamOnly) {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null || engine.networkEngine == null || isFinishing()) {
            return;
        }
        if (chatDialog != null && chatDialog.isShowing()) {
            return;
        }
        View content = getLayoutInflater().inflate(R.layout.alert_chat, null);
        TextView messages = content.findViewById(R.id.chat_messages);
        EditText text = content.findViewById(R.id.chat_text);
        if (engine.networkEngine.chatLog != null) {
            messages.setText(engine.networkEngine.chatLog.getAllMessages());
        }
        text.requestFocus();
        AlertDialog dialog = chatDialog = new AlertDialog.Builder(this)
            .setTitle(teamOnly ? "Send Team Message" : "Send Message")
            .setView(content)
            .setPositiveButton(teamOnly ? "Send Team" : "Send", (clicked, which) ->
                sendChat(engine, text, teamOnly, false))
            .setNeutralButton(LocalizedText.ui("ui.sendPing"), (clicked, which) ->
                sendChat(engine, text, teamOnly, true))
            .setNegativeButton(LocalizedText.ui("common.cancel"), null)
            .create();
        dialog.setOnDismissListener(closed -> {
            chatDialog = null;
            closePauseMenu();
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
    }

    private void sendChat(GameEngine engine, EditText input, boolean teamOnly, boolean ping) {
        String message = input.getText().toString();
        if (!message.trim().isEmpty()) {
            if (teamOnly) {
                engine.networkEngine.sendChatMessageDefaultingToTeam(message);
            } else {
                engine.networkEngine.sendChatMessage(message);
            }
        }
        closePauseMenu();
        if (ping && engine.interfaceEngine != null) {
            engine.interfaceEngine.startMapPingAction();
        }
    }

    private void closePauseMenu() {
        GameEngine engine = GameEngine.getInstance();
        if (engine != null && engine.interfaceEngine != null) {
            engine.interfaceEngine.pauseMenuOpen = false;
        }
    }

    // ------------------------------------------------------------------
    // 引擎
    // ------------------------------------------------------------------

    /** 每次恢复时都将此界面及其表面交给引擎，因为引擎会继续使用已提供的视图。 */
    private void attachToEngine() {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null || gameView == null) {
            return;
        }
        restoreMinimapRendering();
        engine.setActivityAndGameView(this, gameView, false);
        gameView.pushScreenSize();
        Log.i(TAG, "The game view is attached to the engine.");
    }

    /**
     * 恢复引擎自身的小地图渲染设置。
     *
     * <p>早期版本的 RWML 曾在此处将 {@code renderWithLineWidth} 强制设为 {@code false}，仅凭一次设备观察，就将引擎队列式小地图绘制的
     * 两部分理解为“连接线”和“每个单位一个方块”。原版游戏使用该字段的默认值 {@code true}，并一直采用此渲染方式；强制启用另一分支会
     * 进入方块绘制逻辑，而该循环在点位数组填满时会多读取一个 float，最终在游戏线程中抛出
     * {@code ArrayIndexOutOfBoundsException}。</p>
     *
     * <p>该字段会与其他设置一同保存，且此平台没有对应开关，因此运行过强制设置版本的设备在升级后仍会保留旧值。在游戏界面启动时将其
     * 写回默认值，下一次保存设置时就能永久修复。</p>
     */
    private void restoreMinimapRendering() {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null || engine.settings == null) {
            return;
        }
        engine.settings.renderWithLineWidth = false;
    }
}
