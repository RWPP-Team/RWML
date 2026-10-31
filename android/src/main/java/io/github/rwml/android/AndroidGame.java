/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import io.github.rwml.game.GameUiBridge;

import android.content.Context;

import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.GameEngineLoadCallback;
import com.corrodinggames.rts.gameFramework.GameEngineRWGameEngineFactory;

import io.github.rwml.android.builtin.AndroidApp;
import io.github.rwml.api.ProgressListener;

/**
 * 在 Android 上启动游戏的部分。
 *
 * <p>Android 没有可供接管的启动器，因此由 RWML 自行完成启动工作。启动游戏只需调用
 * {@code GameEngine.getOrCreate}，其 {@code init} 会加载资源文件、游戏资源和模组；RWML 不会在
 * 这次调用前后插入自己的步骤。调用期间启动画面显示的是引擎自身的加载进度，与游戏原生启动画面一致。</p>
 */
public final class AndroidGame {

    private AndroidGame() {
    }

    /**
     * 创建并初始化进程的游戏引擎。
     *
     * <p>只有在引擎尚未设置工厂时才设置它，以保留游戏自身的默认值。</p>
     */
    public static GameEngine create(Context platform) {
        return create(platform, ProgressListener.NOOP);
    }

    /** 创建引擎，并将加载消息报告给调用方界面。 */
    public static GameEngine create(Context platform, ProgressListener progress) {
        if (GameEngine.gameEngineFactory == null) {
            GameEngine.gameEngineFactory = new GameEngineRWGameEngineFactory();
            HostLog.add("The engine had no factory; installed the game's own.");
        }
        // 引擎会保存传入的上下文，并据此创建对话框；应用上下文没有窗口，因此这些对话框会抛出
        // BadTokenException。这个包装器使用当前前台 Activity 响应窗口和主题相关调用，使引擎在
        // 各个界面中都能正常显示弹窗，同时无需知道当前是哪一个 Activity。
        EngineContext engineContext = EngineContext.obtain(platform);
        GameEngine engine = GameEngine.getOrCreate(engineContext, new EngineLoadListener(
            progress == null ? ProgressListener.NOOP : progress));
        // getOrCreate 将游戏的 application-context 静态字段绑定到了包装器；这里恢复真正的应用上下文，
        // 因为包装器只负责提供窗口，不应替代应用本身。
        AndroidApp.setApplicationContext(platform);
        GameUiBridge.install(AndroidBootstrap.get(platform).ui());
        HostLog.add("The engine is up: " + engine.getClass().getName());
        return engine;
    }

    /**
     * 将引擎产生的加载消息实时转发到启动画面。
     *
     * <p>引擎会报告加载过程中的每一步；启动画面只需显示这些消息，因为调用前后没有值得单独报告的
     * RWML 步骤。</p>
     *
     * <p>此类继承了游戏中的抽象类，因此有一个方法必须保留混淆后的名称：
     * {@code a(Throwable)} 是引擎的失败通知方法。</p>
     */
    private static final class EngineLoadListener extends GameEngineLoadCallback {

        private final ProgressListener progress;

        EngineLoadListener(ProgressListener progress) {
            this.progress = progress;
        }

        @Override
        public void onSetLoadingMessage(String message, boolean fromEngine) {
            HostLog.add("engine      " + message);
            progress.onProgress(0, message);
        }

        @Override
        public void onShowAlert(String message, int type) {
            HostLog.warn("The engine raised an alert: " + message);
            progress.onProgress(0, "alert: " + message);
        }

        @Override
        public void onShowMessageBox(String title, String message) {
            HostLog.warn("The engine raised a message box: " + title);
            progress.onProgress(0, "alert: " + title);
        }

        @Override
        public void a(Throwable failure) {
            HostLog.error("The engine reported a failure", failure);
            progress.onProgress(0, "engine failed: " + failure);
        }
    }
}
