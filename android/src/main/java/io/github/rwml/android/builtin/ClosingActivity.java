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

import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.network.NetworkEngine;

/**
 * 游戏退出时经过的界面。
 *
 * <p>该界面在进程即将退出时启动，因此只负责清理游戏当前打开的内容，然后关闭自身：它会像游戏自带的同名界面一样，
 * 清除引擎的就绪标志，并在绘制前结束。</p>
 */
public final class ClosingActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        GameEngine engine = GameEngine.getInstance();
        NetworkEngine network = engine == null ? null : engine.networkEngine;
        if (network != null) {
            try {
                network.clearClientReadyFlags();
            } catch (Throwable t) {
                Log.w(TAG, "The ready flags could not be cleared while closing.", t);
            }
        }
        finish();
    }

    @Override
    protected void onResume() {
            // 进入此界面就意味着即将退出：没有需要显示或等待的内容。
        super.onResume();
        finish();
    }
}
