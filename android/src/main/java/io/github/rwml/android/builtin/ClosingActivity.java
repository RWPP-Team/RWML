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
 * The screen the game leaves through.
 *
 * <p>It is started when the process is on its way out, so it does nothing but settle what the game
 * had open and close itself: the engine's ready flags are cleared, the way the game's own copy of
 * this screen clears them, and the screen finishes before it is ever drawn.</p>
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
        // Reaching the screen at all means leaving: there is nothing to show and nothing to wait for.
        super.onResume();
        finish();
    }
}
