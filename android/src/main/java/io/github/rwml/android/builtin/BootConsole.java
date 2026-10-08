/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import io.github.rwml.android.HostLog;

/**
 * Puts the game's own console on the splash.
 *
 * <p>The engine reports its progress by printing to {@code System.out}, and {@link HostLog} tees that
 * stream and offers every completed line to whichever screen asked for them. This class is the splash
 * asking, and it installs the tee as well, because a screen may be the first thing in the process to
 * want one.</p>
 *
 * <p>Nothing of the host's own appears here: the lines offered are the ones the game printed.</p>
 */
final class BootConsole {

    private BootConsole() {
    }

    /** Shows the game's console on the loading line. */
    static void install() {
        HostLog.install();
        HostLog.sink(LoadingStatus::line);
    }
}
