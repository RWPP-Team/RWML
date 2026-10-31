/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.net.impl

import android.content.Intent
import android.net.Uri
import com.corrodinggames.rts.gameFramework.GameEngine

internal actual fun apiOpenUriInBrowser(uri: String) {
    val context = GameEngine.getInstance().context
    val flags = if (context !is android.app.Activity) Intent.FLAG_ACTIVITY_NEW_TASK else 0
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(flags))
}
