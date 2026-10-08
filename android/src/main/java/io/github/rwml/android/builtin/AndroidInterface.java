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
 * The interface this extension declares: it puts the game's Android screens on screen.
 *
 * <p>It is the Android counterpart of {@code io.github.rwml.builtin.VanillaInterface}, and what it
 * does is small: it opens the builtin launcher. The player starts the game through its splash; custom
 * ui-framework extensions may supply both of those screens themselves.</p>
 */
public final class AndroidInterface implements UiFramework {

    /** The screen name every host knows: the game. */
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
