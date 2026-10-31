/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.content.Context;

import io.github.rwml.android.builtin.AndroidExtension;
import io.github.rwml.api.GameAssets;
import io.github.rwml.api.MixinBridge;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.host.HostPlatform;
import io.github.rwml.host.ResourcePackEnvironment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Android 应用的宿主平台。
 *
 * <p>所有行为都源于 Android 与 JVM 的一个关键区别：类以 dex 形式存在，在加载前会被改写，而且只有
 * 拥有已修补类的类加载器才能解析其中的 mixin 调用。因此，扩展类加载器就是启动器挂载修补后 dex
 * 的加载器，并由所有扩展共享；在这里无法隔离各个扩展。</p>
 */
public final class AndroidHost implements HostPlatform {

    private final Context context;
    private final MixinBridge mixins;
    private final ResourcePackEnvironment resourcePacks;
    private volatile ClassLoader extensionLoader;

    AndroidHost(Context context, MixinBridge mixins, ClassLoader extensionLoader,
                ResourcePackEnvironment resourcePacks) {
        this.context = context;
        this.mixins = mixins;
        this.extensionLoader = extensionLoader;
        this.resourcePacks = resourcePacks;
    }

    void updateLoader(ClassLoader loader) { extensionLoader = loader; }

    @Override
    public MixinBridge mixins() {
        return mixins;
    }

    /** 游戏资源由 APK 携带，因此扩展只能通过名称访问资源。 */
    @Override
    public GameAssets gameAssets() {
        return new AndroidGameAssets(context.getAssets());
    }

    @Override
    public ResourcePackEnvironment resourcePacks() {
        return resourcePacks;
    }

    /** 解压 APK 中携带的扩展；详见 {@link AssetInstaller}。 */
    @Override
    public List<String> stageArchives(Path extensionDirectory) throws IOException {
        return AssetInstaller.install(context, "extensions", extensionDirectory);
    }

    /**
     * RWML 随应用内置的界面，使没有安装任何扩展的新用户也能启动游戏。用户扩展若声明了自己的
     * {@code ui-framework}，则优先使用该界面；按 ID 禁用内置扩展后，应用会以无界面模式运行。
     * 此处不提供平台无关的内置界面，因为它会把控制权交给 Android 上不存在的启动器。
     */
    @Override
    public List<ExtensionDescriptor> builtInExtensions() {
        return Collections.singletonList(AndroidExtension.descriptor());
    }

    @Override
    public ClassLoader createExtensionLoader(ExtensionDescriptor descriptor) {
        return extensionLoader;
    }

    /**
     * Android 使用平台提供的 Java 版本，且无法更换，因此无法满足扩展声明的版本要求。描述符仍会
     * 报告此要求，但除此之外不会采取其他处理。
     */
    @Override
    public ExtensionDescriptor reconcileJavaRequirement(ExtensionDescriptor descriptor,
                                                        ProgressListener progress) {
        return descriptor;
    }
}
