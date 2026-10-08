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
import android.util.Log;

import com.corrodinggames.rts.appFramework.AppContext;
import com.corrodinggames.rts.gameFramework.GameEngine;

import io.github.rwml.android.AndroidBootstrap;

import java.lang.reflect.Field;

/**
 * The place where this package touches the platform.
 *
 * <p>Every activity needs the same three things - the engine has to be told which context it runs
 * under, a full-screen screen has to say so, and a screen has to be startable from a context that may
 * not be an activity - and none of them belongs in an activity.</p>
 *
 * <p>The game's own equivalent cannot be called: its methods reach the platform through a copy of the
 * Android API the archive carries inside itself, so a call raises {@code NoSuchMethodError} on a
 * device. The methods here do the same work with the platform's real API. The one exception is the
 * application context, which the engine reads from a static of the game's own helper, written here by
 * name.</p>
 */
public final class AndroidApp {

    private static final String TAG = "RWML";

    /** The game's helper, which holds the application context the engine reads. */
    private static final String APP_CONTEXT_CLASS = "com.corrodinggames.rts.appFramework.AppContext";

    /** The static field of that helper holding the application context, as the mappings name it. */
    private static final String APP_CONTEXT_FIELD = "applicationContext";

    private AndroidApp() {
    }

    /**
     * Brings one activity up.
     *
     * <p>A screen other than the splash is handed over to the splash when there is no engine to
     * read. Android may restart the process on the last activity it had open, and that entry runs
     * none of the splash's work, so the dex is mounted while the engine was never made. A screen
     * that reads the engine then has nothing to show, which reads as an empty list rather than as
     * the missing engine it is. The splash is the only screen that can make one, so every other
     * screen goes there.</p>
     *
     * @param activity   the activity that is starting
     * @param fullScreen whether the activity hides the system bars
     */
    public static void prepare(Activity activity, boolean fullScreen) {
        // The runtime must be up before anything looks the game up by name: Android may restart the
        // process on the last activity it had open, which never ran the splash's boot.
        AndroidBootstrap.prepare(activity.getApplicationContext());
        GameEngine engine = GameEngine.getInstance();
        if (!(activity instanceof IntroScreen) && engine == null) {
            Log.i(TAG, "No engine is running; handing over to the splash from "
                + activity.getClass().getSimpleName() + ".");
            startScreen(activity, new Intent(activity, IntroScreen.class));
            activity.finish();
            return;
        }
        // The rest is the game's own screen setup, called rather than restated: it captures the
        // application context the engine reads, hides the system bars when the player's own setting
        // asks for it, asks the engine for a message it had no screen to show when it was raised -
        // which is where a map that failed to load ends up - and clears the window's background,
        // because the game draws its own in every frame. The game's screens call this with the same
        // arguments; the middle one is false there, and true here, because the screens this app
        // carries all fill the window.
        try {
            AppContext.setupActivity(activity, true, fullScreen);
        } catch (Throwable t) {
            // A screen whose platform setup failed must still come up: it is the screen the player
            // is looking at, and the failure would otherwise be a blank window.
            Log.w(TAG, "The game's screen setup did not complete.", t);
        }
    }

    /**
     * Turns the transition animation off.
     *
     * <p>It is a cosmetic choice the game makes as well: the interface fades itself in, and the
     * platform's animation on top of that reads as a stutter.</p>
     */
    public static void disableTransitionAnimation(Activity activity) {
        try {
            activity.overridePendingTransition(0, 0);
        } catch (Throwable t) {
            Log.w(TAG, "Could not turn the transition animation off.", t);
        }
    }

    /** Runs an action on the main thread, or where it is when the context is not an activity. */
    public static void runOnUiThread(Context context, Runnable action) {
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(action);
        } else {
            action.run();
        }
    }

    /**
     * Starts a screen from a context that may not be an activity, on whatever thread the caller is on.
     *
     * <p>The flag that gives the new screen a task is added when the context is not an activity, and
     * the launch is moved to the main thread when it is.</p>
     */
    public static void startScreen(Context context, Intent intent) {
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        Runnable launch = () -> {
            try {
                context.startActivity(intent);
            } catch (Throwable t) {
                Log.e(TAG, "Could not start " + intent.getComponent(), t);
            }
        };
        runOnUiThread(context, launch);
    }

    /**
     * Writes the application context into the game's own static, which throws when it is null.
     *
     * <p>The helper is looked up on the loader that owns the game dex, not on the app's own: the
     * platform decides whether that dex ends up on the app's class path, and when it refuses, the
     * app's loader answers nothing for the game's name.</p>
     */
    public static void setApplicationContext(Context context) {
        ClassLoader loader = AndroidBootstrap.gameLoader();
        try {
            Class<?> helper = Class.forName(APP_CONTEXT_CLASS, true,
                loader != null ? loader : context.getClassLoader());
            Field field = helper.getDeclaredField(APP_CONTEXT_FIELD);
            field.setAccessible(true);
            field.set(null, context);
            Log.i(TAG, "The engine's application context is set.");
        } catch (Throwable t) {
            Log.w(TAG, "The engine's application context could not be set. The engine will report "
                + "this itself when it is asked to start.", t);
        }
    }
}
