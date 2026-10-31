/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game

import kotlinx.serialization.Serializable

const val roomProtocolVersion: Int = 3

/** Extra protocol fields advertised by RWPP-created rooms. */
@Serializable
data class RoomOption(
    var canTransferMod: Boolean = false,
    val allModsSize: Int = 0,
    val protocolVersion: Int = roomProtocolVersion
)
