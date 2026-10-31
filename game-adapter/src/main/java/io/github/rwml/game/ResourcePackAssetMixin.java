/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.game;

import java.io.File;

import kim.der.asm.api.annotation.AsmInject;
import kim.der.asm.api.annotation.AsmMixin;
import kim.der.asm.api.annotation.CallbackInfo;
import kim.der.asm.api.annotation.InjectionPoint;
import kim.der.asm.api.annotation.ModifyReturnValue;

/**
 * 让游戏的资源读取先经过生效的资源包。
 *
 * <p>游戏读取资源只有两条路径：抽象路径被转换为平台路径，或被直接打开为资源流。前者用于需要文件对象的地方，后者
 * 用于流式读取。两条都改写到资源包目录，包未提供的文件仍按原逻辑处理，因此资源包只需携带它替换的文件。</p>
 *
 * <p>处理方法把资源流声明为 {@code Object}：Android 上处理方法由应用加载器定义，而该加载器看不到游戏类。构造过程
 * 位于 {@link ResourcePackAssets#open}，它通过游戏类自己的加载器解析类型。</p>
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/files/FileLoaderBackend")
public final class ResourcePackAssetMixin {

    private ResourcePackAssetMixin() {
    }

    /**
     * 把指向资源包内文件的抽象路径改写为平台路径，使后续打开操作直接使用该文件。
     *
     * <p>只改写转换结果中带 {@code assets/} 的路径。绝对路径与存储路径由游戏另行处理，资源包不参与。</p>
     */
    @ModifyReturnValue(method = "convertAbstractPath(Ljava/lang/String;)Ljava/lang/String;", require = 1)
    public static String redirectAbstractPath(String result, String path) {
        if (result == null || !ResourcePackAssets.redirects(path)) {
            return result;
        }
        String normalized = result.replace('\\', '/');
        int assets = normalized.indexOf("assets/");
        if (assets < 0) {
            return result;
        }
        String relative = normalized.substring(assets + "assets/".length());
        File file = ResourcePackAssets.file(relative);
        return file == null ? result : file.getPath();
    }

    /**
     * 用资源包中的文件替代平台资源流的打开操作。
     *
     * <p>未命中时保持原方法执行，因此包外资源、内置模组、地图与翻译都不受影响。</p>
     */
    @AsmInject(
        method = "openAsset(Ljava/lang/String;)Lcom/corrodinggames/rts/gameFramework/utility/AssetInputStream;",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1
    )
    public static void openPackedAsset(CallbackInfo callbackInfo, Object self, String path) {
        Object stream = ResourcePackAssets.open(self, path);
        if (stream != null) {
            callbackInfo.setReturnValue(stream);
        }
    }
}
