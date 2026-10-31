/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game.map

sealed class MissionType {
    data object Normal : MissionType()
    data object Challenge : MissionType()
    data object Survival : MissionType()
    fun pathName() = toString().lowercase()
    class ModType(val mod: String) : MissionType()
}
