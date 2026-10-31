/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.builtin;

import io.github.rwml.api.Extension;
import io.github.rwml.api.ExtensionContext;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionDescriptorReader;

/**
 * RWML 自带的扩展。
 *
 * <p>它唯一的用途是提供界面：全新安装后无需先下载任何内容也应能显示游戏，因此负责“启动游戏”的扩展会随 RWML JAR 一起发布。
 * 它没有单独的归档，类直接位于宿主类路径中，并使用内置描述符；因此不需要该界面的宿主只需不提供它，即可无界面运行。</p>
 *
 * <p>除此之外，它与普通扩展完全相同：会与已安装扩展一起排序，可按 ID 禁用；若用户扩展声明自己的 {@code ui-framework}，则优先使用用户扩展。</p>
 */
public final class BuiltinExtension implements Extension {

    private static final ExtensionDescriptor DESCRIPTOR = ExtensionDescriptorReader.readBuiltIn(
        BuiltinExtension.class, "/META-INF/rwml/builtin-desktop.properties");

    /** 从随包提供的属性文件中读取的扩展 ID。 */
    public static final String ID = DESCRIPTOR.id();

    /**
     * 每个入口类都必须提供的无参构造函数。
     *
     * <p>构造函数必须公开，因为管理器会像实例化从归档读取的扩展一样，通过反射创建此类；对于加载器而言，内置扩展就是已打开归档的普通扩展。</p>
     */
    public BuiltinExtension() {
    }

    /**
     * 宿主为此扩展提供的描述符。
     *
     * <p>这是内置描述符，因此没有源归档：相关类已经从宿主自己的 JAR 加载，无需检查或协调。</p>
     *
     * @return 已声明界面的描述符
     */
    public static ExtensionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void onLoad(ExtensionContext context) {
        context.log("RWML's own interface extension. It declares " + VanillaInterface.class.getName()
            + " as the ui-framework, so the host starts a game; disable '" + ID + "' to stay "
            + "headless.");
    }
}
