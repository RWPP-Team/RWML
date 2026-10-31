/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.command

import io.github.rwml.api.i18n.Messages

/**
 * 命令文案。
 *
 * 宿主启动后通过 [attach] 注入自己的 [Messages]，命令即可跟随界面语言；未注入时退回英文，
 * 未收录的键直接返回键名本身，命令仍然可用。
 */
object CommandText {
    @Volatile
    private var messages: Messages? = null

    fun attach(messages: Messages) {
        this.messages = messages
    }

    fun text(key: String, vararg arguments: Any?): String =
        messages?.text(key, *arguments) ?: Messages.fallback(key, *arguments)
}
