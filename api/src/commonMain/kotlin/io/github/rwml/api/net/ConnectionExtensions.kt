/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.net

import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.Packet as GamePacket
import io.github.rwml.api.appKoin
import org.koin.core.component.get

/**
 * 向这一条连接单独发包。
 *
 * 连接对象就是游戏自己的 [Connection]，因此不需要额外的客户端抽象。
 */
fun Connection.sendPacketToClient(packet: Packet) {
    require(packet.type != PayloadKey.PAYLOAD_ENVELOPE_PACKET_ID) {
        "Native packet 1000 is reserved for the API-owned namespaced payload envelope"
    }
    val net = appKoin.get<Net>()
    val payload = net.packetRegistry.encodeOutgoing(packet, PacketDirection.SERVER_TO_CLIENT)
    sendPacket(GamePacket(packet.type).apply { data = payload })
}

/**
 * Sends a namespaced payload to this client after checking that it advertised the same key.
 * The client must have registered the matching codec and completed payload negotiation.
 */
fun <T : Any> Connection.sendPayloadToClient(key: PayloadKey<T>, value: T) {
    appKoin.get<Net>().sendPayloadToClient(this, key, value)
}

/** Starts or refreshes payload capability negotiation with this connected client. */
fun Connection.negotiatePayloadsToClient() {
    appKoin.get<Net>().negotiatePayloadsToClient(this)
}
