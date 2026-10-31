/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import io.github.rwml.api.Extension;
import io.github.rwml.api.ExtensionContext;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionDescriptorReader;

/**
 * RWML 随 Android 应用提供的扩展，对应于 {@code io.github.rwml.builtin.BuiltinExtension}，使全新安装的应用
 * 无需额外安装内容也能显示游戏。宿主通过 {@code HostPlatform.builtInExtensions()} 提供它，因此它与普通扩展
 * 一样参与排序、可按 ID 禁用，并且会让位于声明了自有 {@code ui-framework} 的用户扩展。
 *
 * <p>Android 需要提供自己的实现，因为这里没有可供接管的启动器：游戏 Activity 不在应用挂载的平台无关归档中，
 * 因此游戏使用的界面需要在此实现。宿主启动的是 {@link AndroidInterface}；此类则用于为该界面提供所属扩展。</p>
 */
public final class AndroidExtension implements Extension {

    private static final ExtensionDescriptor DESCRIPTOR = ExtensionDescriptorReader.readBuiltIn(
        AndroidExtension.class, "/META-INF/rwml/builtin-android.properties");

    /** 从随包属性文件中读取的扩展 ID。 */
    public static final String ID = DESCRIPTOR.id();

    /** 此类由管理器通过反射实例化，与其他扩展相同，因此构造函数必须公开。 */
    public AndroidExtension() {
    }

    /**
     * Android 宿主为此扩展提供的描述符。
     *
     * @return 已声明界面的描述符
     */
    public static ExtensionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void onLoad(ExtensionContext context) {
        context.log("RWML's Android interface extension. It declares "
            + AndroidInterface.class.getName() + " as the ui-framework, so the app shows the game; "
            + "disable '" + ID + "' to stay headless.");
    }
}
