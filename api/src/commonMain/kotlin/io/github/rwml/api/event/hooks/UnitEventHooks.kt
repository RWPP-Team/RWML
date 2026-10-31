/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event.hooks

import com.corrodinggames.rts.game.Projectile
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.Unit
import com.corrodinggames.rts.game.units.buildings.FactoryBuildQueue
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwml.api.event.GameEventPublisher
import io.github.rwml.api.event.UnitDamage
import io.github.rwml.api.event.UnitEvents
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

private data class UnitDamageSnapshot(val unit: Unit, val health: Float, val shield: Float)
private data class BuildProgressSnapshot(val unit: Unit, val progress: Float)
private data class UnitTeamSnapshot(val unit: Unit, val team: Team?)
private data class UnitKillSnapshot(val unit: Unit, val wasDead: Boolean)
private data class UnitRemovalSnapshot(val unit: Unit, val wasRemoved: Boolean)

@AsmMixin("com/corrodinggames/rts/game/units/Unit")
object UnitEventMixin {
    private val damageFrames = ThreadLocal.withInitial { ArrayDeque<UnitDamageSnapshot>() }
    private val buildProgressFrames = ThreadLocal.withInitial { ArrayDeque<BuildProgressSnapshot>() }
    private val teamFrames = ThreadLocal.withInitial { ArrayDeque<UnitTeamSnapshot>() }
    private val killFrames = ThreadLocal.withInitial { ArrayDeque<UnitKillSnapshot>() }
    private val removalFrames = ThreadLocal.withInitial { ArrayDeque<UnitRemovalSnapshot>() }

    @AsmInject(method = "<init>(Z)V", target = InjectionPoint.TAIL, require = 1)
    fun stageSpawnCandidate(target: Any, isPlaceholder: Boolean) {
        if (!isPlaceholder && UnitEvents.SPAWNED.hasListeners) {
            NativeUnitSpawnRegistry.stage(target as Unit)
        }
    }

    @AsmInject(
        method = "damage(Lcom/corrodinggames/rts/game/units/Unit;FLcom/corrodinggames/rts/game/Projectile;)F",
        target = InjectionPoint.HEAD,
        require = 1,
    )
    fun captureDamage(target: Any, @Suppress("UNUSED_PARAMETER") attacker: Any?, @Suppress("UNUSED_PARAMETER") damage: Float,
                      @Suppress("UNUSED_PARAMETER") projectile: Any?) {
        val unit = target as Unit
        damageFrames.get().addLast(UnitDamageSnapshot(unit, unit.hp, unit.shield))
    }

    @AsmInject(
        method = "damage(Lcom/corrodinggames/rts/game/units/Unit;FLcom/corrodinggames/rts/game/Projectile;)F",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun publishDamage(
        callback: CallbackInfo,
        target: Any,
        attacker: Any?,
        requestedDamage: Float,
        projectile: Any?,
    ) {
        val unit = target as Unit
        val frames = damageFrames.get()
        val before = frames.pollLast() ?: return
        if (frames.isEmpty()) damageFrames.remove()
        if (before.unit !== unit) return

        val healthDamage = (before.health - unit.hp).coerceAtLeast(0.0F)
        val shieldDamage = (before.shield - unit.shield).coerceAtLeast(0.0F)
        if (healthDamage == 0.0F && shieldDamage == 0.0F) return

        UnitEvents.DAMAGED.invoker.onEvent(
            UnitDamage(
                unit = unit,
                attacker = attacker as? Unit,
                projectile = projectile as? Projectile,
                requestedDamage = requestedDamage,
                healthDamage = healthDamage,
                shieldDamage = shieldDamage,
                remainingDamage = callback.getReturnValue<Float>() ?: 0.0F,
            ),
        )

        val engine = GameEngine.getInstance()
        if (unit.team == engine.playerTeam) {
            if (unit.isBuilding()) GameEventPublisher.localPlayerBaseDamaged()
            else GameEventPublisher.localPlayerUnitDamaged()
        }
    }

    @AsmInject(method = "setBuildProgress(F)V", target = InjectionPoint.HEAD, require = 1)
    fun captureBuildProgress(target: Any, @Suppress("UNUSED_PARAMETER") progress: Float) {
        val unit = target as Unit
        buildProgressFrames.get().addLast(BuildProgressSnapshot(unit, unit.buildProgress))
    }

    @AsmInject(method = "setBuildProgress(F)V", target = InjectionPoint.TAIL, require = 1)
    fun publishConstructionCompleted(target: Any, @Suppress("UNUSED_PARAMETER") progress: Float) {
        val unit = target as Unit
        val frames = buildProgressFrames.get()
        val before = frames.pollLast() ?: return
        if (frames.isEmpty()) buildProgressFrames.remove()
        if (before.unit !== unit || before.progress >= 1.0F || unit.buildProgress < 1.0F) return

        publishCreated(unit)
        UnitEvents.CONSTRUCTION_COMPLETED.invoker.onEvent(unit)
    }

    @AsmInject(method = "setTeam(Lcom/corrodinggames/rts/game/Team;)V", target = InjectionPoint.HEAD, require = 1)
    fun captureTeamChange(target: Any, @Suppress("UNUSED_PARAMETER") team: Any?) {
        val unit = target as Unit
        teamFrames.get().addLast(UnitTeamSnapshot(unit, unit.team))
    }

    @AsmInject(method = "setTeam(Lcom/corrodinggames/rts/game/Team;)V", target = InjectionPoint.TAIL, require = 1)
    fun publishTeamChange(target: Any, @Suppress("UNUSED_PARAMETER") team: Any?) {
        val unit = target as Unit
        val frames = teamFrames.get()
        val before = frames.pollLast() ?: return
        if (frames.isEmpty()) teamFrames.remove()
        val nextTeam = unit.team ?: return
        if (before.unit !== unit || before.team === nextTeam) return
        UnitEvents.TEAM_CHANGED.invoker.onEvent(unit)
    }

    @AsmInject(method = "kill()V", target = InjectionPoint.HEAD, require = 1)
    fun captureKill(target: Any) {
        val unit = target as Unit
        killFrames.get().addLast(UnitKillSnapshot(unit, unit.dead))
    }

    @AsmInject(method = "kill()V", target = InjectionPoint.TAIL, require = 1)
    fun publishDeath(target: Any) {
        val unit = target as Unit
        val frames = killFrames.get()
        val before = frames.pollLast() ?: return
        if (frames.isEmpty()) killFrames.remove()
        if (before.unit !== unit || before.wasDead || !unit.dead) return
        UnitEvents.DIED.invoker.onEvent(unit)
    }

    @AsmInject(method = "destroy()V", target = InjectionPoint.HEAD, require = 1)
    fun captureRemoval(target: Any) {
        val unit = target as Unit
        removalFrames.get().addLast(UnitRemovalSnapshot(unit, unit.removed))
    }

    @AsmInject(method = "destroy()V", target = InjectionPoint.TAIL, require = 1)
    fun publishRemoval(target: Any) {
        val unit = target as Unit
        val frames = removalFrames.get()
        val before = frames.pollLast() ?: return
        if (frames.isEmpty()) removalFrames.remove()
        if (before.unit === unit && !before.wasRemoved && unit.removed) {
            NativeUnitSpawnRegistry.forget(unit)
            UnitEvents.REMOVED.invoker.onEvent(unit)
        }
    }

    @AsmInject(
        method = "getNewUnits()Lcom/corrodinggames/rts/gameFramework/utility/DeferredArrayList;",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun publishCommittedUnits(@Suppress("UNUSED_PARAMETER") callback: CallbackInfo) {
        NativeUnitSpawnRegistry.publishCommitted()
    }

    @AsmInject(method = "applyDeferredUnitChanges()V", target = InjectionPoint.TAIL, require = 1)
    fun publishAppliedDeferredUnits() {
        NativeUnitSpawnRegistry.publishCommitted()
    }
}

@AsmMixin("com/corrodinggames/rts/game/units/OrderableUnit")
object UnitUpgradeEventMixin {
    @AsmInject(method = "onUnitUpgraded()V", target = InjectionPoint.TAIL, require = 1)
    fun publishUpgrade(target: Any) {
        val unit = target as OrderableUnit
        UnitEvents.UPGRADED.invoker.onEvent(unit)
        if (unit.team == GameEngine.getInstance().playerTeam) {
            GameEventPublisher.localPlayerUpgradeCompleted()
        }
    }
}

@AsmMixin("com/corrodinggames/rts/game/units/buildings/FactoryBuildQueue")
object FactoryUnitCreationEventMixin {
    @AsmInject(
        method = "spawnUnit(Lcom/corrodinggames/rts/game/units/UnitTypeInterface;FZF)Lcom/corrodinggames/rts/game/units/Unit;",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun publishFactoryUnitCreated(
        callback: CallbackInfo,
        @Suppress("UNUSED_PARAMETER") target: Any,
        @Suppress("UNUSED_PARAMETER") unitType: Any?,
        @Suppress("UNUSED_PARAMETER") exitOffset: Float,
        @Suppress("UNUSED_PARAMETER") forceExit: Boolean,
        @Suppress("UNUSED_PARAMETER") angle: Float,
    ) {
        val unit = callback.getReturnValue<Unit>() ?: return
        publishCreated(unit)
    }
}

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object UnitSpawnRegistryMixin {
    @AsmInject(method = "clearAllGameObjects()V", target = InjectionPoint.TAIL, require = 1)
    fun clearCommittedUnits() {
        NativeUnitSpawnRegistry.clear()
    }
}

private object NativeUnitSpawnRegistry {
    private val lock = Any()
    private val pending: MutableSet<Unit> = Collections.newSetFromMap(IdentityHashMap<Unit, Boolean>())

    fun stage(unit: Unit) {
        synchronized(lock) { pending.add(unit) }
    }

    fun publishCommitted() {
        if (!UnitEvents.SPAWNED.hasListeners) {
            clear()
            return
        }

        val candidates = synchronized(lock) {
            if (pending.isEmpty()) return
            pending.toList().also { pending.clear() }
        }

        for (unit in candidates) {
            if (!unit.removed && !unit.isPlaceholder) {
                UnitEvents.SPAWNED.invoker.onEvent(unit)
            }
        }
    }

    fun clear() {
        synchronized(lock) { pending.clear() }
    }

    fun forget(unit: Unit) {
        synchronized(lock) { pending.remove(unit) }
    }
}

private fun publishCreated(unit: Any) {
    val nativeUnit = unit as Unit
    UnitEvents.CREATED.invoker.onEvent(nativeUnit)
    val engine = GameEngine.getInstance()
    if (nativeUnit.team == engine.playerTeam) {
        if (nativeUnit.isBuilding()) GameEventPublisher.localPlayerBuildingConstructed()
        else GameEventPublisher.localPlayerUnitCreated()
    }
}
