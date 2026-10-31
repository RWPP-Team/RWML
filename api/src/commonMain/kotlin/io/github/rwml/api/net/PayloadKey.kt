/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.net

/** A codec for an application-defined, namespaced network payload. */
interface PayloadCodec<T : Any> {
    fun encode(value: T): ByteArray

    fun decode(payload: ByteArray): T
}

/**
 * Identifies a payload independently of the game's numeric packet IDs.
 *
 * [namespace] must use the form `owner:channel`, for example `rwpp:room-query`. Both peers must
 * register the same namespace, schema version, direction, and size limit before exchanging values.
 */
class PayloadKey<T : Any>(
    val namespace: String,
    val version: Int,
    val direction: PacketDirection,
    val codec: PayloadCodec<T>,
    val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
) {
    init {
        require(isValidNamespace(namespace)) {
            "Payload namespace must be a lowercase namespaced identifier (owner:channel): $namespace"
        }
        require(version in 1..MAX_SCHEMA_VERSION) {
            "Payload schema version must be in 1..$MAX_SCHEMA_VERSION: $version"
        }
        require(maxPayloadBytes in 1..MAX_PAYLOAD_BYTES) {
            "maxPayloadBytes must be in 1..$MAX_PAYLOAD_BYTES: $maxPayloadBytes"
        }
    }

    companion object {
        /** Native packet number reserved exclusively for the RWML envelope. */
        const val PAYLOAD_ENVELOPE_PACKET_ID = 1000
        const val DEFAULT_MAX_PAYLOAD_BYTES = 256 * 1024
        /** Leaves enough room for the envelope header under the native 50 MB packet limit. */
        const val MAX_PAYLOAD_BYTES = 49_999_744
        const val MAX_SCHEMA_VERSION = 65_535
        const val MAX_NAMESPACE_LENGTH = 193

        internal fun isValidNamespace(value: String): Boolean {
            if (value.length !in 3..MAX_NAMESPACE_LENGTH) return false
            val separator = value.indexOf(':')
            if (separator <= 0 || separator != value.lastIndexOf(':') || separator == value.lastIndex) return false
            return isIdentifierPart(value.substring(0, separator)) &&
                isIdentifierPart(value.substring(separator + 1))
        }

        private fun isIdentifierPart(value: String): Boolean =
            value.length <= 96 && value.all {
                it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-'
            }
    }
}
