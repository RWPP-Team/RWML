/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event.hooks

import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwml.api.event.GameEventPublisher
import io.github.rwml.api.event.*
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameEngineMapType
import com.corrodinggames.rts.gameFramework.network.Packet
import io.github.rwml.api.game.impl.NativeGameLifecycle
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Operation
import kim.der.asm.api.annotation.WrapMethod
import java.lang.reflect.InvocationTargetException

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object NativeNetworkLifecycleMixin {
    @AsmInject(method = "startServer(Z)Z", target = InjectionPoint.TAIL, require = 1)
    fun publishMultiplayerHostStarted(
        callback: CallbackInfo,
        target: Any,
        @Suppress("UNUSED_PARAMETER") createAdminPlayer: Boolean,
    ) {
        val network = target as NetworkEngine
        if (callback.getReturnValue<Boolean>() == true && network.networked && network.isServer && !network.singlePlayerServer) {
            NativeGameLifecycle.onMultiplayerHostStarted(network)
        }
    }

    @AsmInject(method = "startSingleplayerServer()Z", target = InjectionPoint.TAIL, require = 1)
    fun publishSinglePlayerHostStarted(callback: CallbackInfo, target: Any) {
        val network = target as NetworkEngine
        if (callback.getReturnValue<Boolean>() == true && network.networked && network.isServer && network.singlePlayerServer) {
            NativeGameLifecycle.onSinglePlayerHostStarted(network)
        }
    }

    @AsmInject(method = "startSandboxMode()Z", target = InjectionPoint.TAIL, require = 1)
    fun syncSandboxMode(callback: CallbackInfo, target: Any) {
        if (callback.getReturnValue<Boolean>() == true) {
            NativeGameLifecycle.onSandboxModeStarted(target as NetworkEngine)
        }
    }

    @AsmInject(
        method = "connectToServerAsync(Ljava/lang/String;ZLjava/lang/Runnable;)Lcom/corrodinggames/rts/gameFramework/network/ConnectSocketToServerRunnable;",
        target = InjectionPoint.HEAD,
        require = 1,
    )
    fun publishJoinAttempt(
        @Suppress("UNUSED_PARAMETER") target: Any,
        address: String,
        @Suppress("UNUSED_PARAMETER") forceTcp: Boolean,
        @Suppress("UNUSED_PARAMETER") callback: Runnable,
    ) {
        NativeGameLifecycle.onJoinAttempt(address)
    }

    @AsmInject(
        method = "startClientConnection(Ljava/net/Socket;)Z",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun syncClientConnected(callback: CallbackInfo, target: Any, socket: java.net.Socket) {
        val network = target as NetworkEngine
        if (callback.getReturnValue<Boolean>() == true && network.networked && !network.isServer) {
            val address = socket.inetAddress?.hostAddress?.let { "$it:${socket.port}" }
            NativeGameLifecycle.onClientConnected(network, address)
            ConnectionEvents.TRANSPORT_CONNECTED.invoker.onEvent(TransportConnected(network, address))
        }
    }

    @AsmInject(method = "disconnect(Ljava/lang/String;)V", target = InjectionPoint.TAIL, require = 1)
    fun syncNetworkDisconnected(
        target: Any,
        reason: String,
    ) {
        val network = target as NetworkEngine
        if (NativeGameLifecycle.onNetworkDisconnected(network, reason)) {
            ConnectionEvents.DISCONNECTED.invoker.onEvent(NetworkDisconnected(network, reason))
        }
    }

    @AsmInject(method = "returnToBattleroom()V", target = InjectionPoint.TAIL, require = 1)
    fun syncReturnToBattleroom(target: Any) {
        val network = target as NetworkEngine
        NativeGameLifecycle.onReturnToBattleroom(network)
        RoomEvents.RETURNED.invoker.onEvent(network)
    }

    @AsmInject(
        method = "applyChangedSetup(Lcom/corrodinggames/rts/gameFramework/network/GameSetup;)V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun publishMapSelectionChanged(
        target: Any,
        setup: Any,
    ) {
        val network = target as NetworkEngine
        NativeGameLifecycle.onSetupApplied(network)
        if (network.isServer) {
            RoomEvents.SETUP_APPLIED.invoker.onEvent(
                RoomSetupApplied(network, setup as com.corrodinggames.rts.gameFramework.network.GameSetup),
            )
        }
    }

    @AsmInject(method = "processSystemPacket(Lcom/corrodinggames/rts/gameFramework/network/Packet;)V", target = InjectionPoint.TAIL, require = 1)
    fun confirmClientRoom(target: Any, packet: Packet) {
        val network = target as NetworkEngine
        if (packet.type == 115 && !network.isServer && network.networked && network.localTeam != null) {
            NativeGameLifecycle.onClientRoomJoined(network, packet.connection)
        }
    }
}

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object NativeEngineLifecycleMixin {
    private fun load(engine: GameEngine, loadAi: Boolean, keepTeams: Boolean, mapType: GameEngineMapType) =
        MapLoad(engine, engine.currentMapPath, mapType, loadAi, keepTeams)

    @WrapMethod(method = "loadMap(ZZLcom/corrodinggames/rts/gameFramework/GameEngineMapType;)V", require = 1)
    fun wrapMapLoad(
        loadAi: Boolean,
        keepTeams: Boolean,
        mapType: GameEngineMapType,
        operation: Operation<Unit>,
    ) {
        val engine = GameEngine.getInstance()
        val context = load(engine, loadAi, keepTeams, mapType)
        MapEvents.BEFORE.invoker.onEvent(context)
        try {
            operation.call(loadAi, keepTeams, mapType)
        } catch (failure: Throwable) {
            val nativeFailure = unwrapInvocationTarget(failure)
            NativeGameLifecycle.onMapLoadFailed()
            notifyMapFailure(context, nativeFailure)
            throw nativeFailure
        }

        if (engine.map?.mapLoaded == true && engine.gameJustLoaded) {
            MapEvents.LOADED.invoker.onEvent(context)
            NativeGameLifecycle.onMapLoaded()
        } else {
            NativeGameLifecycle.onMapLoadFailed()
            MapEvents.FAILED.invoker.onEvent(MapLoadFailure(context, "Native map loading returned without a loaded map"))
        }
    }

    private fun notifyMapFailure(context: MapLoad, failure: Throwable) {
        try {
            MapEvents.FAILED.invoker.onEvent(
                MapLoadFailure(context, failure.message ?: failure.javaClass.name),
            )
        } catch (notificationFailure: Throwable) {
            failure.addSuppressed(notificationFailure)
        }
    }

    private fun unwrapInvocationTarget(failure: Throwable): Throwable {
        var cause = failure
        while (cause is InvocationTargetException && cause.targetException != null) {
            cause = cause.targetException
        }
        return cause
    }

    @AsmInject(
        method = "setActivityAndGameView(Landroid/app/Activity;Lcom/corrodinggames/rts/appFramework/GameViewInterface;Z)V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun syncNativeMenuState(
        @Suppress("UNUSED_PARAMETER") target: Any,
        @Suppress("UNUSED_PARAMETER") activity: Any?,
        @Suppress("UNUSED_PARAMETER") gameView: Any?,
        resumeWithMenu: Boolean,
    ) {
        if (resumeWithMenu) {
            NativeGameLifecycle.onMenuShown()
        } else {
            GameEventPublisher.resumeGame()
        }
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/ReplayEngine")
object NativeReplayLifecycleMixin {
    @WrapMethod(method = "loadReplayFile(Ljava/lang/String;Ljava/io/File;)Z", require = 1)
    fun wrapReplayLoad(
        replayName: String,
        @Suppress("UNUSED_PARAMETER") file: java.io.File,
        operation: Operation<Boolean>,
    ): Boolean {
        val replay = GameEngine.getInstance().replayEngine
        NativeGameLifecycle.onReplayLoading()
        val context = ReplayLoad(replay, replayName)
        ReplayEvents.BEFORE.invoker.onEvent(context)
        val nativeResult = try {
            operation.call(replayName, file)
        } catch (failure: Throwable) {
            val nativeFailure = unwrapInvocationTarget(failure)
            notifyReplayFailure(context, nativeFailure)
            throw nativeFailure
        }

        val success = NativeGameLifecycle.onReplayLoaded(nativeResult && replay.isPlayingReplay())
        if (success) ReplayEvents.LOADED.invoker.onEvent(context)
        else ReplayEvents.FAILED.invoker.onEvent(context)
        return nativeResult
    }

    private fun notifyReplayFailure(context: ReplayLoad, failure: Throwable) {
        try {
            NativeGameLifecycle.onReplayLoaded(false)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
        try {
            ReplayEvents.FAILED.invoker.onEvent(context)
        } catch (notificationFailure: Throwable) {
            failure.addSuppressed(notificationFailure)
        }
    }

    private fun unwrapInvocationTarget(failure: Throwable): Throwable {
        var cause = failure
        while (cause is InvocationTargetException && cause.targetException != null) {
            cause = cause.targetException
        }
        return cause
    }
}
