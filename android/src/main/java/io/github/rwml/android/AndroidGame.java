/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.content.Context;

import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.GameEngineLoadCallback;
import com.corrodinggames.rts.gameFramework.GameEngineRWGameEngineFactory;

import io.github.rwml.android.builtin.AndroidApp;
import io.github.rwml.api.ProgressListener;

/**
 * The Android half of bringing the game up.
 *
 * <p>Android has no launcher to hand over to, so RWML does the work itself. Starting the game is one
 * call, {@code GameEngine.getOrCreate}, whose {@code init} loads the assets, the resources and the
 * mods; there are no steps of RWML's own around it, and what the splash shows while the call runs is
 * the engine's own account of its loading, which is what the game's own splash shows too.</p>
 */
public final class AndroidGame {

    private AndroidGame() {
    }

    /**
     * Creates the process's game engine and initialises it.
     *
     * <p>The factory is set only when the engine has none, so the game's own default is kept.</p>
     */
    public static GameEngine create(Context platform) {
        return create(platform, ProgressListener.NOOP);
    }

    /** Creates the engine while reporting its loading messages to the caller's UI. */
    public static GameEngine create(Context platform, ProgressListener progress) {
        if (GameEngine.gameEngineFactory == null) {
            GameEngine.gameEngineFactory = new GameEngineRWGameEngineFactory();
            HostLog.add("The engine had no factory; installed the game's own.");
        }
        // The engine keeps the context it is given and builds its dialogs from it; an application
        // context has no window, so those dialogs throw BadTokenException. The wrapper answers window
        // and theme calls with the activity that is in front, which keeps the engine's popups valid
        // across every screen without the engine knowing which activity that is.
        EngineContext engineContext = EngineContext.obtain(platform);
        GameEngine engine = GameEngine.getOrCreate(engineContext, new EngineLoadListener(
            progress == null ? ProgressListener.NOOP : progress));
        // getOrCreate bound the game's own application-context static to the wrapper; restore the real
        // one, because the wrapper only exists to lend a window, not to stand in for the application.
        AndroidApp.setApplicationContext(platform);
        HostLog.add("The engine is up: " + engine.getClass().getName());
        return engine;
    }

    /**
     * Forwards the engine's own loading messages to the splash, as they come.
     *
     * <p>The engine reports each step it takes while it loads, and those messages are the whole of
     * the splash's account: there is nothing of RWML's own around the call worth announcing.</p>
     *
     * <p>The class extends an abstract class of the game, so one method keeps the name the
     * obfuscation gave it: {@code a(Throwable)} is the engine's failure notification.</p>
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
