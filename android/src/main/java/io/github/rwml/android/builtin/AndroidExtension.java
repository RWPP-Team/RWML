/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import io.github.rwml.api.Extension;
import io.github.rwml.api.ExtensionContext;
import io.github.rwml.core.ExtensionDescriptor;

/**
 * The extension RWML carries inside an Android app: the counterpart of
 * {@code io.github.rwml.builtin.BuiltinExtension}, so that a fresh install can show the game with
 * nothing else installed. The host offers it through {@code HostPlatform.builtInExtensions()}, which
 * makes it an ordinary extension - ordered against the installed ones, disableable by id, and beaten
 * by a user extension that declares its own {@code ui-framework}.
 *
 * <p>Android needs its own because there is no launcher to hand over to: the game's activities are not
 * in the archive the app mounts, which is the platform-independent half, so the screens the game is
 * played through are written here. {@link AndroidInterface} is what the host starts; this class exists
 * so the interface has an extension to belong to.</p>
 */
public final class AndroidExtension implements Extension {

    /** The extension id, which is also the key the disabled flag is stored under. */
    public static final String ID = "io.github.rwml.android.builtin";

    private static final String NAME = "RWML Android Interface";
    private static final String VERSION = "1.0.0";
    private static final String DESCRIPTION =
        "Ships with the Android app. Declares the ui-framework that shows the game's interface on "
            + "Android, which is what lets a fresh install start a game with nothing else installed. "
            + "Disable it by id to run headless.";

    /** Public because the manager instantiates the class reflectively, as it does any extension. */
    public AndroidExtension() {
    }

    /**
     * The descriptor the Android host offers for this extension.
     *
     * @return the descriptor, with the interface declared
     */
    public static ExtensionDescriptor descriptor() {
        return ExtensionDescriptor.builtIn(
            ID, NAME, VERSION, DESCRIPTION,
            AndroidExtension.class.getName(),
            AndroidInterface.class.getName());
    }

    @Override
    public void onLoad(ExtensionContext context) {
        context.log("RWML's Android interface extension. It declares "
            + AndroidInterface.class.getName() + " as the ui-framework, so the app shows the game; "
            + "disable '" + ID + "' to stay headless.");
    }
}
