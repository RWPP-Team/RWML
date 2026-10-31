/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.net.impl

import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.network.Packet as GamePacket
import io.github.rwml.api.appKoin
import io.github.rwml.api.net.Net
import io.github.rwml.api.net.PacketDirection
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

/**
 * Common receive hook for registered native extension system packets.
 *
 * Typed packet IDs 500..999 and the API-owned namespaced envelope at 1000 are processed
 * synchronously by the game's receive worker. Receivers must not mutate simulation state directly;
 * use the game-thread dispatcher when needed.
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object NetworkPacketMixin {
    @AsmInject(
        method = "processSystemPacket(Lcom/corrodinggames/rts/gameFramework/network/Packet;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun dispatchExtensionPacket(
        callbackInfo: CallbackInfo,
        @Suppress("UNUSED_PARAMETER") target: Any,
        nativePacket: Any,
    ) {
        val network = target as NetworkEngine
        val packet = nativePacket as GamePacket
        val incomingDirection = if (network.isServer) {
            PacketDirection.CLIENT_TO_SERVER
        } else {
            PacketDirection.SERVER_TO_CLIENT
        }
        val consumed = appKoin.get<Net>().dispatchNativePacket(
            packetType = packet.type,
            payload = packet.data,
            connection = packet.connection as? Connection,
            incomingDirection = incomingDirection,
        )
        if (consumed) callbackInfo.cancel()
    }
}
