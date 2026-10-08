/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.corrodinggames.rts.gameFramework.GameEngine;

/** Reuses the engine's menu map without replacing a suspended match. */
final class MenuBackground {
    private final Activity activity;
    private final AndroidGameView view;
    MenuBackground(Activity activity, int layout) {
        this.activity = activity;
        FrameLayout root = new FrameLayout(activity);
        view = new AndroidGameView(activity);
        root.addView(view, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activity.getLayoutInflater().inflate(layout, root);
        activity.setContentView(root);
    }
    void resume() {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null) { return; }
        view.setDrawingEnabled(true);
        engine.setActivityAndGameView(activity, view, true);
        view.pushScreenSize();
    }
    void pause() { view.setDrawingEnabled(false); }
}
