/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

/**
 * The line the game last printed, for the screen that shows it while the game loads.
 *
 * <p>The game runs on a worker and reports by printing to its console; the screen draws on the main
 * thread. It holds the last line rather than a queue, because a screen drawing at a fixed rate would
 * only ever show the newest one. {@link BootConsole} is what forwards them.</p>
 */
public final class LoadingStatus {

    /** The line the game last printed. */
    private static volatile String message = "";

    /** The boot's own account of where it is, for example {@code boot [  1%] engine: assets}. */
    private static volatile String progress = "";

    /** Whether the boot has finished, successfully or not. */
    private static volatile boolean finished;

    private LoadingStatus() {
    }

    /** Records one line the game printed on its console. */
    public static void line(String message) {
        LoadingStatus.message = message == null ? "" : message;
    }

    /** Records the boot's own progress line. */
    public static void progress(String line) {
        progress = line == null ? "" : line;
    }

    /** @return the boot's own progress line, empty before the boot reports one */
    public static String progress() {
        return progress;
    }

    /** Records that the boot is over. */
    public static void complete() {
        finished = true;
    }

    /** @return the line the game last printed, never null */
    public static String message() {
        return message;
    }

    /** @return whether the boot is over */
    public static boolean isFinished() {
        return finished;
    }

    /** Forgets everything, so a second run does not show the first one's last line. */
    public static void reset() {
        message = "";
        progress = "";
        finished = false;
    }
}
