/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api

/**
 * 客户端当前所处的阶段。
 *
 * 界面按这份状态决定显示主菜单还是战斗界面，状态迁移通过 `StateChangedEvent` 广播。
 */
enum class State {
    Paused,
    Playing,
    Menu,
}
