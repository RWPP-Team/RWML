/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.net.impl

import io.github.rwml.api.appKoin
import com.corrodinggames.rts.gameFramework.network.Connection
import io.github.rwml.api.net.Net
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import org.koin.core.component.get

/** Drops connection-scoped capabilities when the native network session is torn down. */
@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object PayloadCapabilityMixin {
    @AsmInject(
        method = "removeConnection(Lcom/corrodinggames/rts/gameFramework/network/Connection;)V",
        target = InjectionPoint.HEAD,
        require = 1,
    )
    fun forgetPayloadCapabilities(
        @Suppress("UNUSED_PARAMETER") callbackInfo: CallbackInfo,
        @Suppress("UNUSED_PARAMETER") target: Any,
        connection: Any,
    ) {
        appKoin.get<Net>().payloadRegistry.forgetPeer(connection as Connection)
    }

    @AsmInject(
        method = "disconnect(Ljava/lang/String;)V",
        target = InjectionPoint.HEAD,
        require = 1,
    )
    fun clearPayloadCapabilities(
        @Suppress("UNUSED_PARAMETER") callbackInfo: CallbackInfo,
        @Suppress("UNUSED_PARAMETER") target: Any,
        @Suppress("UNUSED_PARAMETER") reason: String,
    ) {
        appKoin.get<Net>().payloadRegistry.clearNegotiatedCapabilities()
    }
}
