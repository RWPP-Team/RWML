/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.hello;

import io.github.rwml.api.ui.UiFramework;
import io.github.rwml.api.ui.UiHost;

/**
 * The sample's declaration that an interface is wanted.
 *
 * <p>The extension and its interface are two classes on purpose. An extension's entry point is what
 * gets loaded; the interface is a thing the host starts, and only one process may have one. Keeping
 * them apart means the sample keeps loading even when something else owns the interface, and it is
 * why {@code ui-framework} names a class rather than reusing the entry point.</p>
 *
 * <p>What this class does is the whole of the intended division: the platform owns the surface, the
 * events and the GL context, and an extension only decides that an interface is wanted and which
 * screen to start with. On Android the host answers with the game surface, on the desktop with the
 * game itself, and this class never learns which of the two it is talking to.</p>
 */
public final class HelloUi implements UiFramework {

    /** The screen name every host knows: the game. */
    private static final String GAME_SCREEN = "game";

    @Override
    public void start(UiHost host) {
        // Printing through the standard streams is deliberate: on Android the app mirrors them into
        // its own log, so the line is visible without a debugger attached.
        System.out.println("[hello] The host is " + host.platform().getClass().getName()
            + " and was started with " + host.arguments().size() + " argument(s).");
        System.out.println("[hello] Asking it for the '" + GAME_SCREEN + "' screen.");
        host.openScreen(GAME_SCREEN);
    }
}
