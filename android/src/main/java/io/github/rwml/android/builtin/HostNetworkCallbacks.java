/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.corrodinggames.rts.game.Team;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.network.Connection;
import com.corrodinggames.rts.gameFramework.network.NetworkCallbacks;
import com.corrodinggames.rts.gameFramework.network.NetworkEngine;
import com.corrodinggames.rts.gameFramework.network.PasswordPrompt;

/**
 * 引擎网络协议中由宿主负责的部分。
 *
 * <p>引擎不会直接驱动界面。当需要重绘对战房间、启动游戏、接收聊天消息，或向加入的客户端索要密码时，引擎会调用
 * {@link NetworkCallbacks}。具体使用哪个对象由宿主决定：桌面宿主传入自己的启动器，此类则是 Android 宿主的对应实现。
 * 引擎的默认实现不执行任何操作，因此未安装回调的宿主会看到房间界面不更新、游戏也无法启动。</p>
 *
 * <p>此处不会直接操作界面字段。引擎会从自身线程调用回调，因此每次调用都会转发到主线程；房间界面也会通过当前打开的
 * 实例获取，而不是使用旋转屏幕后可能失效的旧引用。</p>
 */
public final class HostNetworkCallbacks extends NetworkCallbacks {

    private static final String TAG = "RWML";

    /** 交给引擎的回调对象；重复安装时会复用已有对象。 */
    private static volatile HostNetworkCallbacks installed;

    /** 当前显示的对战房间；没有显示房间时为 {@code null}。 */
    private static volatile MultiplayerBattleroomActivity room;

    private final Handler main = new Handler(Looper.getMainLooper());

    private HostNetworkCallbacks() {
    }

    /**
     * 将宿主的回调交给引擎，除非引擎已经设置了回调。
     *
     * <p>引擎只创建一次，生命周期长于所有界面，因此在首次使用网络时安装回调，并一直保留。</p>
     */
    public static void install(NetworkEngine network) {
        if (network == null) {
            return;
        }
        HostNetworkCallbacks callbacks = installed;
        if (callbacks == null) {
            synchronized (HostNetworkCallbacks.class) {
                callbacks = installed;
                if (callbacks == null) {
                    callbacks = new HostNetworkCallbacks();
                    installed = callbacks;
                }
            }
        }
        network.networkCallbacks = callbacks;
    }

    /** 记录引擎回调对应的房间，并为引擎安装这些回调。 */
    static void attach(MultiplayerBattleroomActivity activity) {
        room = activity;
        GameEngine engine = GameEngine.getInstance();
        install(engine == null ? null : engine.networkEngine);
    }

    /** 清除房间记录，避免关闭后到达的调用被发送到已销毁界面。 */
    static void detach(MultiplayerBattleroomActivity activity) {
        if (room == activity) {
            room = null;
        }
    }

    /** @return 当前是否正在显示对战房间 */
    static boolean isRoomOpen() {
        return room != null;
    }

    // ------------------------------------------------------------------
    // 引擎调用的回调
    // ------------------------------------------------------------------

    /** 队伍列表、就绪状态或端口检查结果发生变化，触发房间重绘。 */
    @Override
    public void onBattleroomUIUpdate() {
        final MultiplayerBattleroomActivity activity = room;
        if (activity == null) {
            return;
        }
        main.post(activity::refresh);
    }

    /**
     * 所有客户端均已就绪：开始游戏。
     *
     * <p>加载地图并打开游戏界面，与桌面宿主收到相同调用时执行的步骤一致。如果当前没有打开房间界面，就从应用上下文
     * 启动游戏，确保房间关闭后到达的开始请求仍能执行。</p>
     */
    @Override
    public void startGameEvent() {
        Log.i(TAG, "The engine says the game is starting.");
        final MultiplayerBattleroomActivity activity = room;
        if (activity != null) {
            main.post(activity::startMultiplayerGame);
            return;
        }
        main.post(() -> startWithoutRoom());
    }

    /** 服务器关闭了房间，因此界面不应继续停留在该房间。 */
    @Override
    public void onBattleroomClosed() {
        final MultiplayerBattleroomActivity activity = room;
        if (activity != null) {
            main.post(activity::finish);
        }
    }

    /**
     * 需要向正在加入的客户端询问密码。
     *
     * <p>引擎除调用此方法外，也会通过自己的提示框请求密码：它会自行将游戏的密码对话框发送到主线程，因为连接流程内部
     * 也可能发起密码请求，而该流程不涉及宿主界面。如果此处也显示对话框，就会在第一个对话框上再叠加一个，因此这里只
     * 通知宿主。</p>
     */
    @Override
    public void onShowPasswordPrompt(final PasswordPrompt prompt) {
        Log.i(TAG, "The engine is asking for a password"
            + (prompt == null || prompt.question == null
                ? "." : " for its own question: " + prompt.question));
    }

    /** 收到聊天消息，重绘聊天日志。 */
    @Override
    public void onChatMessage(int type, String message, String senderName, Connection connection) {
        final MultiplayerBattleroomActivity activity = room;
        if (activity != null) {
            main.post(activity::refresh);
        }
    }

    /** 新增队伍，重绘玩家表格。 */
    @Override
    public void onPlayerAdded(Team team) {
        final MultiplayerBattleroomActivity activity = room;
        if (activity != null) {
            main.post(activity::refresh);
        }
    }

    @Override
    public void onPlayerJoined(Connection connection, String name, String clientId) {
        final MultiplayerBattleroomActivity activity = room;
        if (activity != null) {
            main.post(activity::refresh);
        }
    }

    @Override
    public void onPlayerDisconnected(Connection connection, String name) {
        final MultiplayerBattleroomActivity activity = room;
        if (activity != null) {
            main.post(activity::refresh);
        }
    }

    @Override
    public boolean isGameStarting() {
        MultiplayerBattleroomActivity activity = room;
        return activity != null && activity.isGameStarting();
    }

    // ------------------------------------------------------------------
    // 回退处理
    // ------------------------------------------------------------------

    /** 当对战房间未显示时，启动引擎通知开始的游戏。 */
    private void startWithoutRoom() {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null || engine.networkEngine == null || !engine.networkEngine.gameStarted) {
            return;
        }
        Context context = engine.getContext();
        if (context == null) {
            Log.w(TAG, "A game start arrived with no room and no context to start it from.");
            return;
        }
        try {
            com.corrodinggames.rts.appFramework.MultiplayerBattleroomActivity.loadMultiplayerMap();
        } catch (Throwable t) {
            Log.e(TAG, "The multiplayer map could not be loaded.", t);
            return;
        }
        android.app.Activity owner = io.github.rwml.android.EngineContext.foregroundActivity();
        if (owner != null && io.github.rwml.api.Rwml.get().ui().openRegisteredScreen("game-start",
                new io.github.rwml.android.AndroidUiHost(owner, java.util.Collections.emptyList()))) {
            return;
        }
        Intent game = new Intent(context, InGameActivity.class);
        game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(game);
    }
}
