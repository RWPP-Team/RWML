/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.mixin;

import android.content.Context;
import android.media.SoundPool;

import io.github.rwml.android.GameResources;
import io.github.rwml.android.HostLog;

import kim.der.asm.api.annotation.AsmMixin;
import kim.der.asm.api.annotation.Redirect;

/**
 * Loads the game's sounds out of this app instead of out of the APK the game archive was built in.
 *
 * <p>The archive carries the resource ids of the APK it shipped in and hands them straight to
 * {@code SoundPool.load}. Those ids resolve against this app's resource table, where they name
 * something else or nothing, and the platform's answer to an id it cannot open is a null path rather
 * than an error: the engine's sound load was what brought the whole boot down with it. Every id is
 * therefore translated first.</p>
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/sound/AndroidSoundFactory")
public final class AndroidSoundFactoryMixin {

    /** Whether this mixin has said that it is in: it runs for every sound the game loads. */
    private static volatile boolean announced;

    private AndroidSoundFactoryMixin() {
    }

    @Redirect(
        method = "createSound",
        target = "android.media.SoundPool.load(Landroid/content/Context;II)I"
    )
    public static int loadFromThisApp(SoundPool pool, Context context, int resId, int priority) {
        if (!announced) {
            announced = true;
            HostLog.add("mixin      AndroidSoundFactoryMixin is in: sound ids now come from this app");
        }
        int own = GameResources.ownResId(context, "raw", resId);
        if (own == 0) {
            return 0;
        }
        return pool.load(context, own, priority);
    }
}
