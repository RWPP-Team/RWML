/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api

/**
 * 由宿主在启动阶段统一回调的初始化接口。
 *
 * 扩展把需要延后到 Koin 容器就绪之后才能执行的装配放进这里，宿主只调用一次 [init]。
 */
interface Initialization {
    fun init()

    /** Called by the built-in API extension after the game adapter has finished loading. */
    fun onGameLoaded() = Unit
}
