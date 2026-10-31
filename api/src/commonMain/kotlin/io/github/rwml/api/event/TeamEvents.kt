/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event

import com.corrodinggames.rts.game.Team

/** Synchronous notifications emitted after native team defeat flags change. */
object TeamEvents {
    val DEFEATED = nativeNotification<Team>("team.defeated")
    val WIPED_OUT = nativeNotification<Team>("team.wiped_out")
}
