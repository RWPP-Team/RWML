/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.io

object SizeUtils {
    fun byteToKB(b: Long) = b / 1024
    fun byteToMB(b: Long) = b / (1024 * 1024)
    fun kBToByte(kb: Long) = kb * 1024
    fun kBToMB(kb: Long) = kb / 1024
    fun mBToByte(mb: Long) = mb * 1024 * 1024
    fun mBToKB(mb: Long) = mb * 1024
}