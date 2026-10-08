/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.builtin;

import io.github.rwml.api.Extension;
import io.github.rwml.api.ExtensionContext;
import io.github.rwml.core.ExtensionDescriptor;

/**
 * The extension RWML carries inside itself.
 *
 * <p>Its whole purpose is the interface: a fresh install has to be able to show the game without
 * downloading anything first, so the one extension that answers "start the game" ships in RWML's own
 * jar. It has no archive - its classes are on the host's class path - and it is a built-in
 * descriptor, which is why a host that wants nothing of the kind can simply not offer it and run
 * headless.</p>
 *
 * <p>It is an ordinary extension in every other respect: it is ordered against the installed ones,
 * it can be disabled by id, and a user extension that declares its own {@code ui-framework} wins over
 * it.</p>
 */
public final class BuiltinExtension implements Extension {

    /** The extension id, which is also the key the disabled flag is stored under. */
    public static final String ID = "io.github.rwml.builtin";

    private static final String NAME = "RWML Interface";
    private static final String VERSION = "1.0.0";
    private static final String DESCRIPTION =
        "Ships with RWML. Declares the ui-framework that shows the game, which is what lets a fresh "
            + "install start a game with nothing else installed. Disable it by id to run headless.";

    /**
     * The no-argument constructor every entry point must have.
     *
     * <p>It is public because the manager instantiates the class reflectively, exactly as it does an
     * extension read from an archive: to the loader a built-in is an extension whose archive is
     * already open.</p>
     */
    public BuiltinExtension() {
    }

    /**
     * The descriptor the host offers for this extension.
     *
     * <p>It is a built-in one, so it has no source archive: the classes are already loaded from the
     * host's own jar and there is nothing to inspect or reconcile.</p>
     *
     * @return the descriptor, with the interface declared
     */
    public static ExtensionDescriptor descriptor() {
        return ExtensionDescriptor.builtIn(
            ID, NAME, VERSION, DESCRIPTION,
            BuiltinExtension.class.getName(),
            VanillaInterface.class.getName());
    }

    @Override
    public void onLoad(ExtensionContext context) {
        context.log("RWML's own interface extension. It declares " + VanillaInterface.class.getName()
            + " as the ui-framework, so the host starts a game; disable '" + ID + "' to stay "
            + "headless.");
    }
}
