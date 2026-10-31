/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.net.packets

import com.corrodinggames.rts.gameFramework.network.InputNetStream
import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import io.github.rwml.api.net.InternalPacketType
import io.github.rwml.api.net.Packet

@Suppress("MemberVisibilityCanBePrivate")
object GamePacket {
    fun getPacket(packetType: Int, action: (OutputNetStream) -> Unit): Packet = object : Packet() {
        override val type: Int = packetType

        override fun readPacket(input: InputNetStream) {}

        override fun writePacket(output: OutputNetStream) {
            action(output)
        }
    }

    fun getChatPacket(title: String?, message: String, color: Int): Packet =
        getPacket(InternalPacketType.CHAT.type) { output ->
            output.writeUTF(message)
            output.writeByte(3)
            output.writeNullableUTF(title)
            output.writeInt(0)
            output.writeInt(color)
        }
}
