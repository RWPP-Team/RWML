/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import io.github.rwml.android.HostLog;
import io.github.rwml.api.ui.UiFramework;
import io.github.rwml.api.ui.UiHost;

/**
 * 此扩展声明的界面，用于显示游戏的 Android 界面。
 *
 * <p>这是 {@code io.github.rwml.builtin.VanillaInterface} 的 Android 对应实现。引擎尚未启动时打开启动器；
 * 引擎完成启动后则把玩家交给原生内置菜单。自定义 {@code ui-framework} 扩展仍可提供自己的界面。</p>
 */
public final class AndroidInterface implements UiFramework {

    /** 所有宿主都支持的界面名称：游戏。 */
    static final String GAME_SCREEN = "game";

    /** 根据共享引导是否完成，选择启动器或引擎原生菜单。 */
    static Class<? extends Activity> destination(boolean gameStarted) {
        return gameStarted ? MainMenuActivity.class : LauncherActivity.class;
    }

    @Override
    public void start(UiHost host) {
        Object platform = host.platform();
        if (!(platform instanceof Context)) {
            HostLog.warn("The platform handle is not a Context: " + platform);
            host.openScreen(GAME_SCREEN);
            return;
        }
        Context context = (Context) platform;
        Class<? extends Activity> destination = destination(host.gameBoot().isStarted());
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            Intent intent = new Intent(context, destination);
            if (!(context instanceof Activity)) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); }
            context.startActivity(intent);
            if (context instanceof Activity) {
                ((Activity) context).finish();
            }
        });
    }
}
