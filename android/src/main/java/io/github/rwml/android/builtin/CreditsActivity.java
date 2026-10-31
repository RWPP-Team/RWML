/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import io.github.rwml.android.R;

/**
 * 制作人员名单界面：使用游戏自身的布局和名单文本。
 *
 * <p>文本由两条本地化内容以空行连接，和游戏原界面一致：先显示制作人员名单，再显示游戏所依赖的第三方项目声明。
 * 两部分都来自引擎语言文件，因此界面语言与游戏当前设置一致。</p>
 *
 * <p>此界面无需保存内容，因此只有一个操作：关闭界面。</p>
 */
public final class CreditsActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(LocalizedText.ui("ui.credits"));
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        AndroidApp.prepare(this, true);
        setContentView(R.layout.credits);

        fillCredits();
        onClick(R.id.creditsClose, this::finish);
        Log.i(TAG, "Credits open.");
    }

    /** 写入游戏制作人员名单；无法读取时保留布局自带文本。 */
    private void fillCredits() {
        View view = findViewById(R.id.creditsText);
        if (!(view instanceof TextView)) {
            Log.w(TAG, "The credits layout has no text view.");
            return;
        }
        String main = LocalizedText.get("menus.credits.main");
        String notices = LocalizedText.get("menus.credits.notices");
        if (main == null && notices == null) {
            return;
        }
        ((TextView) view).setText(
            (main == null ? "" : main) + "\n" + (notices == null ? "" : notices));
    }

    private void onClick(int id, Runnable action) {
        View view = findViewById(id);
        if (view == null) {
            Log.w(TAG, "The credits layout has no view for " + id + ".");
            return;
        }
        view.setOnClickListener(clicked -> action.run());
    }
}
