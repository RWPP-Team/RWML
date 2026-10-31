/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game

var roomMods: Array<String> = arrayOf()
var bannedUnitList: List<String> = listOf()
var roomGameSpeed: Float = 1f

object PlayerIds {
    private var playerCount = 0
    fun next(): Int = synchronized(this) { ++playerCount }
}
