/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.mixin;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import io.github.rwml.android.HostLog;
import io.github.rwml.android.ResourcePackResources;

import kim.der.asm.api.annotation.AsmInject;
import kim.der.asm.api.annotation.AsmMixin;
import kim.der.asm.api.annotation.CallbackInfo;
import kim.der.asm.api.annotation.InjectionPoint;

/**
 * 从资源包加载按资源 ID 索引的图片。
 *
 * <p>Android 的图片加载接收资源 ID，平台按资源表读取，无法直接指向资源包中的文件。此处先取得该 ID 对应的文件名，再解码
 * 资源包中的同名文件，并按平台要求包装为游戏的位图对象。资源包未提供该图片时保持原方法执行。</p>
 *
 * <p>包装类通过实例自己的类加载器解析，因此处理方法的签名不必出现任何游戏类型；这也让 mixin 能在游戏 dex 生成前注册。</p>
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/render/GraphicsEngineAndroid")
public final class ResourcePackImageMixin {

    private static final String BITMAP_OR_TEXTURE =
        "com.corrodinggames.rts.gameFramework.render.BitmapOrTexture";

    private static volatile boolean announced;

    private ResourcePackImageMixin() {
    }

    @AsmInject(
        method = "loadImage(IZ)Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1
    )
    public static void loadPackedImage(CallbackInfo callbackInfo, Object self, int resId, boolean argb) {
        ResourcePackResources.Source source = ResourcePackResources.resolve(resId, "drawable");
        if (source == null) {
            return;
        }
        Bitmap bitmap = decode(source, argb);
        if (bitmap == null) {
            return;
        }
        Object texture = wrap(self, bitmap);
        if (texture == null) {
            bitmap.recycle();
            return;
        }
        if (!announced) {
            announced = true;
            HostLog.add("mixin      ResourcePackImageMixin is in: images come from the resource pack");
        }
        callbackInfo.setReturnValue(texture);
    }

    private static Bitmap decode(ResourcePackResources.Source source, boolean argb) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = argb ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        options.inScaled = false;
        try {
            return BitmapFactory.decodeFile(source.file().getAbsolutePath(), options);
        } catch (RuntimeException unreadable) {
            HostLog.warn("could not decode " + source.file() + " from the resource pack", unreadable);
            return null;
        }
    }

    /**
     * 用图形引擎自己的包装方法把位图变成 {@code BitmapOrTexture}。
     *
     * <p>通过反射调用而不是直接构造，包装过程因此与原生加载完全一致；引擎若在包装时补充其他状态，资源包也会得到。</p>
     */
    private static Object wrap(Object self, Bitmap bitmap) {
        try {
            return self.getClass().getMethod("wrapBitmap", Bitmap.class).invoke(self, bitmap);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            HostLog.warn("could not wrap a resource pack image as " + BITMAP_OR_TEXTURE, unavailable);
            return null;
        }
    }
}
