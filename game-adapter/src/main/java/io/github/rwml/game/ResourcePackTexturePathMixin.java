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
 * 让 libRocket 的纹理与文档路径先经过资源包。
 *
 * <p>界面纹理按 {@code drawable:}、{@code assets:} 或裸文件名引用，由 libRocket 自行拼成平台路径；这条路径既不走文件
 * 加载器，也不按资源 ID 解析，因此需要单独处理。</p>
 *
 * <p>同一方法也用于定位 rml 文档，所以资源包替换 {@code gui} 下的文档同样生效。</p>
 */
@AsmMixin("com/corrodinggames/librocket/CommonLibRocket")
public final class ResourcePackTexturePathMixin {

    private ResourcePackTexturePathMixin() {
    }

    @ModifyReturnValue(method = "convertTexturePath(Ljava/lang/String;)Ljava/lang/String;", require = 1)
    public static String redirectTexturePath(String result, String texturePath) {
        if (result == null) {
            return null;
        }
        String inside = ResourcePackAssets.inside(result);
        if (inside != null) {
            return inside;
        }
        File file = ResourcePackAssets.textureFile(texturePath);
        return file == null ? result : file.getPath();
    }
}
