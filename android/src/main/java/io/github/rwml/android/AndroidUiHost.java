/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import io.github.rwml.api.ui.UiHost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What an extension's interface is handed on Android.
 *
 * <p>The platform handle is the launcher activity, because starting another activity is the one thing
 * only a {@code Context} can do. The screens the host knows are mapped here, and a name it does not
 * know is reported instead of failing silently.</p>
 */
public final class AndroidUiHost implements UiHost {

    private static final String TAG = "RWML";

    /** The screen name that opens the game surface. */
    public static final String GAME_SCREEN = "game";

    private final Activity activity;
    private final List<String> arguments;

    AndroidUiHost(Activity activity, List<String> arguments) {
        this.activity = activity;
        this.arguments = Collections.unmodifiableList(
            arguments == null ? new ArrayList<String>() : new ArrayList<>(arguments));
    }

    @Override
    public Object platform() {
        return activity;
    }

    @Override
    public List<String> arguments() {
        return arguments;
    }

    @Override
    public void openScreen(String screen) {
        final String type = screenType(screen);
        if (type == null) {
            Log.w(TAG, "The Android host has no screen called '" + screen + "'.");
            return;
        }
        // A screen is an activity, and only the main thread may start one. An interface is started
        // from the loader thread, so the hop belongs here rather than in every extension.
        activity.runOnUiThread(() -> {
            Intent intent = new Intent().setClassName(activity, type);
            activity.startActivity(intent);
        });
    }

    /** @return the activity that shows {@code screen}, or {@code null} when the host has none. */
    private static String screenType(String screen) {
        if ("main-menu".equals(screen)) {
            return "io.github.rwml.android.builtin.MainMenuActivity";
        }
        return GAME_SCREEN.equals(screen) ? "io.github.rwml.android.builtin.InGameActivity" : null;
    }
}
