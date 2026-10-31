/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.mixin;

import android.media.SoundPool;

import io.github.rwml.android.HostLog;
import io.github.rwml.android.ResourcePackResources;

import kim.der.asm.api.annotation.AsmInject;
import kim.der.asm.api.annotation.AsmMixin;
import kim.der.asm.api.annotation.CallbackInfo;
import kim.der.asm.api.annotation.InjectionPoint;

/**
 * 从资源包加载按资源 ID 索引的音效。
 *
 * <p>音效句柄把资源 ID 交给平台的音效池，而资源包的文件不在资源表中，因此这里以文件路径加载，再按句柄构造器的要求
 * 组装对象。句柄类型与工厂类型都通过实例自己的类加载器解析，处理方法因此不需要声明游戏类型。</p>
 *
 * <p>任一环节不可用时保持原方法执行：资源包没有替换这个音效，或游戏版本改变了句柄结构，此时沿用平台自身的加载。</p>
 */
@AsmMixin("com.corrodinggames/rts/gameFramework/sound/AndroidSoundFactory")
public final class ResourcePackSoundMixin {

    private static final String HANDLE = "com.corrodinggames.rts.gameFramework.sound.AndroidSoundHandle";
    private static final String FACTORY = "com.corrodinggames.rts.gameFramework.sound.AndroidSoundFactory";
    private static final String BASE_FACTORY = "com.corrodinggames.rts.gameFramework.sound.SoundFactory";

    private static volatile boolean announced;

    private ResourcePackSoundMixin() {
    }

    @AsmInject(
        method = "createSound(I)Lcom/corrodinggames/rts/gameFramework/sound/SoundHandle;",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1
    )
    public static void createPackedSound(CallbackInfo callbackInfo, Object self, int resId) {
        ResourcePackResources.Source source = ResourcePackResources.resolve(resId, "raw");
        if (source == null) {
            return;
        }
        SoundPool pool = (SoundPool) MixinFields.read(self, "soundPool");
        if (pool == null) {
            return;
        }
        Object handle = createHandle(self, source, pool);
        if (handle == null) {
            return;
        }
        if (!announced) {
            announced = true;
            HostLog.add("mixin      ResourcePackSoundMixin is in: sounds come from the resource pack");
        }
        callbackInfo.setReturnValue(handle);
    }

    /** @return 已加载文件并绑定句柄的句柄对象；无法构造时返回 null */
    private static Object createHandle(Object factory, ResourcePackResources.Source source, SoundPool pool) {
        int soundId;
        try {
            soundId = pool.load(source.file().getAbsolutePath(), 1);
        } catch (RuntimeException unreadable) {
            HostLog.warn("could not load " + source.file() + " into the sound pool", unreadable);
            return null;
        }
        try {
            ClassLoader loader = factory.getClass().getClassLoader();
            Class<?> factoryType = Class.forName(FACTORY, true, loader);
            Class<?> baseFactoryType = Class.forName(BASE_FACTORY, true, loader);
            Class<?> handleType = Class.forName(HANDLE, true, loader);
            Object handle = handleType
                .getConstructor(factoryType, String.class, baseFactoryType)
                .newInstance(factory, source.name(), factory);
            MixinFields.write(handle, "androidFactory", factory);
            MixinFields.write(handle, "soundId", soundId);
            return handle;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            // 句柄未能组装时释放已经加载的样本，避免资源池里留下无主的条目。
            pool.unload(soundId);
            HostLog.warn("could not build a sound handle for " + source.name(), unavailable);
            return null;
        }
    }
}
