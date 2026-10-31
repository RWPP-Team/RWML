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
 * 从当前应用而非游戏归档构建时所用的 APK 中加载游戏图片。
 *
 * <p>此处与音效 mixin 执行相同的转换，处理图片加载器传给平台的 ID。游戏 drawable 会按归档中的名称暂存于当前应用，
 * 因此需要在此按名称查找 ID，而不能直接使用原值。</p>
 *
 * <p>上下文来自 {@link GameResources}，而非目标类的隐藏字段：此处理方法运行于框架构造的 mixin 实例上，目标类的字段不会被赋值。</p>
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/render/GraphicsEngineAndroid")
public final class GraphicsEngineAndroidMixin {

    /** 标记该 Mixin 是否已执行；游戏每加载一张图片都会触发它。 */
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
