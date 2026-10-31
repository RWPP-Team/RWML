/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.content.Context;
import android.app.Activity;
import android.content.Intent;

import io.github.rwml.android.HostLog;
import io.github.rwml.api.ui.UiFramework;
import io.github.rwml.api.ui.UiHost;

/**
 * 此扩展声明的界面，用于显示游戏的 Android 界面。
 *
 * <p>这是 {@code io.github.rwml.builtin.VanillaInterface} 的 Android 对应实现，职责很简单：打开内置启动器。
 * 玩家通过启动器的启动画面进入游戏；自定义 {@code ui-framework} 扩展也可以自行提供这两个界面。</p>
 */
public final class AndroidInterface implements UiFramework {

    /** 所有宿主都支持的界面名称：游戏。 */
    static final String GAME_SCREEN = "game";

    @Override
    public void start(UiHost host) {
        Object platform = host.platform();
        if (!(platform instanceof Context)) {
            HostLog.warn("The platform handle is not a Context: " + platform);
            host.openScreen(GAME_SCREEN);
            return;
        }
        Context context = (Context) platform;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            Intent intent = new Intent(context, LauncherActivity.class);
            if (!(context instanceof Activity)) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); }
            context.startActivity(intent);
            if (context instanceof Activity) {
                ((Activity) context).finish();
            }
        });
    }
}
