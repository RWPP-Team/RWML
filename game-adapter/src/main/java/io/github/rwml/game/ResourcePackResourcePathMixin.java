/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.game;

import java.io.File;

import kim.der.asm.api.annotation.AsmMixin;
import kim.der.asm.api.annotation.ModifyReturnValue;

/**
 * 让桌面版按资源 ID 加载的图片与音效先经过资源包。
 *
 * <p>桌面引擎把资源 ID 解析为资源文件路径，再由平台按路径打开，这条路径不经过文件加载器，因此文件加载器的重定向无法覆盖
 * 它。此处直接返回资源包中的同名文件，未提供时保持游戏原本的解析结果。</p>
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/CommonUtils")
public final class ResourcePackResourcePathMixin {

    private ResourcePackResourcePathMixin() {
    }

    @ModifyReturnValue(method = "getResourcePathForId(I)Ljava/lang/String;", require = 1)
    public static String redirectResourcePath(String result, int resourceId) {
        File file = ResourcePackAssets.resourceFile("drawable", resourceId);
        if (file == null) {
            file = ResourcePackAssets.resourceFile("raw", resourceId);
        }
        return file == null ? result : file.getPath();
    }
}
