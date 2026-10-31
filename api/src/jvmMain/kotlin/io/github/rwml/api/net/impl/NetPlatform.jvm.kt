/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.net.impl

import java.awt.Desktop
import java.net.URI

internal actual fun apiOpenUriInBrowser(uri: String) {
    Desktop.getDesktop().browse(URI(uri))
}
