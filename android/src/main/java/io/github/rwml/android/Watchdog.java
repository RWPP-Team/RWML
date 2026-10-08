/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

/**
 * Says where a thread still is, while it is still there.
 *
 * <p>The work that brings the game up runs off the main thread and takes long enough that a reader
 * cannot tell a slow run from a stopped one, and both look the same: no lines. A watchdog started
 * around a piece of work writes the stack of the thread doing it to {@link HostLog} every few
 * seconds, so a stack that moves is a run that is proceeding and a stack that does not, under a
 * {@code WAITING} or {@code BLOCKED} state, is a run that is stuck.</p>
 *
 * <p>It interrupts nothing: a slow boot is still the only thing that can finish it.</p>
 */
public final class Watchdog {

    /** How long the work may run before the first stack is written. */
    private static final long GRACE_MILLIS = 10_000L;

    /** How long between two stacks after the first. */
    private static final long PERIOD_MILLIS = 10_000L;

    /** How finely the observer notices that the work has finished. */
    private static final long SLICE_MILLIS = 500L;

    /** How many frames of a stack are worth writing; the rest is the thread's own history. */
    private static final int MAX_FRAMES = 24;

    private final Thread watched;
    private final String what;
    private final long startedAt;

    private volatile boolean stopped;

    private Watchdog(String what, Thread watched) {
        this.what = what;
        this.watched = watched;
        this.startedAt = System.currentTimeMillis();
    }

    /**
     * Starts watching the calling thread.
     *
     * @param what the name of the work, which the reports are headed with
     * @return the watchdog; {@link #stop()} ends it
     */
    public static Watchdog start(String what) {
        return start(what, Thread.currentThread());
    }

    /**
     * Starts watching a thread.
     *
     * @param what    the name of the work, which the reports are headed with
     * @param watched the thread doing it
     * @return the watchdog; {@link #stop()} ends it
     */
    public static Watchdog start(String what, Thread watched) {
        Watchdog watchdog = new Watchdog(what, watched);
        Thread observer = new Thread(watchdog::observe, "rwml-watchdog");
        observer.setDaemon(true);
        observer.start();
        return watchdog;
    }

    /** Ends the watching. The observer notices within one slice and goes away. */
    public void stop() {
        stopped = true;
    }

    private void observe() {
        long waited = 0L;
        long nextReport = GRACE_MILLIS;
        while (!stopped) {
            sleep(SLICE_MILLIS);
            if (stopped) {
                return;
            }
            waited += SLICE_MILLIS;
            if (waited < nextReport) {
                continue;
            }
            nextReport = waited + PERIOD_MILLIS;
            report(waited);
        }
    }

    private void report(long waited) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
        if (!watched.isAlive()) {
            HostLog.warn("watchdog   '" + what + "' is over after " + seconds
                + "s; the thread has left it");
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append("watchdog   '").append(what).append("' has run for ").append(seconds)
            .append("s on thread '").append(watched.getName()).append("', state ")
            .append(watched.getState());
        StackTraceElement[] frames = watched.getStackTrace();
        int count = Math.min(frames.length, MAX_FRAMES);
        for (int i = 0; i < count; i++) {
            text.append("\n             at ").append(frames[i]);
        }
        HostLog.warn(text.toString());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
