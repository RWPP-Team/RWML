/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api

/** Built-in archive entry point that owns shared API initialization. */
class ApiExtension : Extension {
    override fun onLoad(context: ExtensionContext) {
        ApiRuntime.initialize(context)
        context.log("RWML API services initialized")
    }
}
