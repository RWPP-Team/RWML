/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event.events

import io.github.rwml.api.event.AbstractEvent
import com.corrodinggames.rts.game.Team

sealed class PlayerEvent(val player: Team) : AbstractEvent()

/**
 * Event that is fired when a player joins the game.
 */
class PlayerJoinEvent(player: Team) : PlayerEvent(player)

/**
 * Event that is fired when a player leaves the game.
 */
class PlayerLeaveEvent(player: Team, val reason: String) : PlayerEvent(player)

/**
 * Event that is fired when a player is defeated.
 */
class PlayerDefeatedEvent(player: Team) : PlayerEvent(player)
