/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** 仅供调试使用的窗口：保持测试进程在前台，且不会启动另一套游戏引导流程。 */
public final class BootstrapTestActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        EngineContext.obtain(getApplicationContext());
        TextView text = new TextView(this);
        text.setText("RWML device regression checks");
        setContentView(text);
    }
}
