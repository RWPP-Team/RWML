/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event

import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.gameFramework.render.CanvasLike
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine

/** Native graphics state supplied during the final stage of a rendered frame. */
data class DrawContext(
    val engine: RWGameEngine,
    val graphics: GraphicsEngine,
    val canvas: CanvasLike?,
    val deltaTime: Float,
    val frameCount: Int,
)

/** The callback runs synchronously on the native render thread. */
object DrawEvents {
    val FRAME = nativeNotification<DrawContext>("draw.frame")
}
