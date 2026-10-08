/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api;


/**
 * Contract implemented by every extension entry point.
 *
 * <p>An extension is either loaded once at startup or it is not loaded at all.
 * When it is loaded the host instantiates the entry point and invokes
 * {@link #onLoad(ExtensionContext)} exactly once. There is no enable/disable
 * or unload callback: the only way to stop an extension from running is to
 * mark it disabled, after which the host simply skips it on the next startup.
 * All callbacks are optional and may be omitted thanks to the default
 * implementation.</p>
 */
public interface Extension {

    /** Called once, immediately after the entry point has been instantiated. */
    default void onLoad(ExtensionContext context) throws Exception {
    }
}
