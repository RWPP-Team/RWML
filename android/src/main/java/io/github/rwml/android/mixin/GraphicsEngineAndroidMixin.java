/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.mixin;

import io.github.rwml.android.GameResources;
import io.github.rwml.android.HostLog;

import kim.der.asm.api.annotation.AsmMixin;
import kim.der.asm.api.annotation.ModifyArg;

/**
 * Loads the game's images out of this app instead of out of the APK the game archive was built in.
 *
 * <p>It is the same translation the sound mixin performs, applied to the ids the image loader hands
 * to the platform. The game's drawables are staged in this app under the names the archive knows
 * them by, so the id is looked up by name here rather than used as it stands.</p>
 *
 * <p>The context comes from {@link GameResources}, not from a shadowed field of the target: this
 * handler runs on a mixin instance the framework constructs, where no field of the target is ever
 * assigned.</p>
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/render/GraphicsEngineAndroid")
public final class GraphicsEngineAndroidMixin {

    /** Whether this mixin has said that it is in: it runs for every image the game loads. */
    private static volatile boolean announced;

    private GraphicsEngineAndroidMixin() {
    }

    @ModifyArg(
        method = "loadImage(IZ)Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;",
        index = 0
    )
    public static int loadImageFromThisApp(int res) {
        if (!announced) {
            announced = true;
            HostLog.add("mixin      GraphicsEngineAndroidMixin is in: image ids now come from this app");
        }
        return GameResources.ownResId(GameResources.context(), "drawable", res);
    }
}
