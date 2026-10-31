/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game.map

import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import java.io.InputStream

open class Mission(
    id: Int,
    val name: String,
    val type: MissionType,
    mapName: String,
    mapType: GameSetupMapType,
    inputStreamProvider: () -> InputStream,
    displayNameProvider: (() -> String)? = null,
    enginePath: String = "maps/${type.pathName()}/$mapName.tmx",
) : GameMap(id, mapName, mapType, inputStreamProvider, displayNameProvider, enginePath)
