/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event

import com.corrodinggames.rts.game.Projectile
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.Unit

/** Reports damage after the native damage call changed health or shield. */
data class UnitDamage(
    val unit: Unit,
    val attacker: Unit?,
    val projectile: Projectile?,
    val requestedDamage: Float,
    val healthDamage: Float,
    val shieldDamage: Float,
    val remainingDamage: Float,
)

/** Synchronous notifications for native unit state transitions. */
object UnitEvents {
    /** Fires when the unit enters the committed live-unit list. */
    val SPAWNED = nativeNotification<Unit>("unit.spawned")
    /** Fires when construction changes from incomplete to complete, or a factory creates a finished unit. */
    val CREATED = nativeNotification<Unit>("unit.created")
    val CONSTRUCTION_COMPLETED = nativeNotification<Unit>("unit.construction_completed")
    val DAMAGED = nativeNotification<UnitDamage>("unit.damaged")
    val TEAM_CHANGED = nativeNotification<Unit>("unit.team_changed")
    val UPGRADED = nativeNotification<OrderableUnit>("unit.upgraded")
    val DIED = nativeNotification<Unit>("unit.died")
    val REMOVED = nativeNotification<Unit>("unit.removed")
}
