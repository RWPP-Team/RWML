/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game.map

class Replay(
    val id: Int,
    val name: String,
    private val displayNameProvider: (() -> String)? = null,
) {
    fun displayName(): String = displayNameProvider?.invoke() ?: name
}
