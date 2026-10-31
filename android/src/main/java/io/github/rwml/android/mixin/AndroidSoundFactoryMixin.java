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
 * 从当前应用而非游戏归档构建时所用的 APK 中加载游戏音效。
 *
 * <p>归档携带的是发布时 APK 的资源 ID，并会直接交给 {@code SoundPool.load}。这些 ID 会在当前应用的资源表中解析，
 * 但可能指向其他内容或不存在；对于无法打开的 ID，平台返回的是空路径而不是错误，随后引擎加载音效时就会导致整个启动失败。
 * 因此，必须先转换每个资源 ID。</p>
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/sound/AndroidSoundFactory")
public final class AndroidSoundFactoryMixin {

    /** 标记该 Mixin 是否已执行；游戏每加载一个音效都会触发它。 */
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
