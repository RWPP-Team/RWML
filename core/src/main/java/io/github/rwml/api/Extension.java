/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api;


/**
 * 所有扩展入口点都必须实现的契约。
 *
 * <p>扩展会在启动时加载一次，否则完全不加载。加载时，宿主会实例化入口点并恰好调用一次
 * {@link #onLoad(ExtensionContext)}。本接口没有启用、禁用或卸载回调；若要停止扩展运行，
 * 只能将其标记为已禁用，宿主会在下次启动时跳过它。所有回调都有默认实现，因此都可以省略。</p>
 */
public interface Extension {

    /** 入口点实例化后立即调用一次。 */
    default void onLoad(ExtensionContext context) throws Exception {
    }
}
