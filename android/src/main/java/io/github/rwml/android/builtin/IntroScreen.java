/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

import io.github.rwml.android.AndroidBootstrap;
import io.github.rwml.api.game.StageReport;
import io.github.rwml.android.HostLog;
import io.github.rwml.android.R;
import io.github.rwml.android.Watchdog;

/**
 * The first screen of the game: it starts the engine and says so while it does.
 *
 * <p>The screen goes up first and the loading starts after, because loading the game takes seconds
 * and a blank window for those seconds is indistinguishable from a failure. It draws itself, then
 * starts a worker that creates the engine, then opens the menu.</p>
 *
 * <p>While it loads, the screen shows the engine's own account of the load: the line the engine
 * printed last, which {@link BootConsole} tees onto the lower line, and the loading message the
 * engine set last, on the upper one. A load that fails is reported to logcat and the screen stays
 * where it is - the menu without a running engine behind it is a screen of crashes waiting to
 * happen.</p>
 */
public final class IntroScreen extends Activity {

    /** A cached boot finishes before the screen has been drawn once, and one frame reads as a glitch. */
    private static final long MINIMUM_DISPLAY_MILLIS = 900L;

    private volatile boolean opening;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        AndroidApp.prepare(this, true);
        setContentView(R.layout.intro_screen);
        LoadingStatus.reset();
        BootConsole.install();

        Thread worker = new Thread(this::runBoot, "rwml-boot");
        worker.setDaemon(true);
        worker.start();
    }

    /** Creates the engine and opens the menu; a failed load leaves the screen here. */
    private void runBoot() {
        // The load is CPU-bound and the screen it feeds draws on the main thread, so the load runs
        // at the priority the game's own game thread takes; without it the loading lines arrive in
        // bursts instead of as they happen.
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        long startedAt = System.currentTimeMillis();
        // Creating the engine loads the assets, the graphics and the audio, which takes long enough
        // that a slow load cannot be told from a stopped one without looking at the stack.
        Watchdog watchdog = Watchdog.start("boot");
        try {
            StageReport report = AndroidBootstrap.get(this).gameBoot().start(
                (percent, message) -> LoadingStatus.progress(message));
            if (!report.isClean()) {
                throw new IllegalStateException("Game boot failed: " + report.failed());
            }
        } catch (Throwable t) {
            HostLog.error("The game could not be started", t);
            LoadingStatus.progress("failed: " + t);
            return;
        } finally {
            watchdog.stop();
        }
        HostLog.add("The game is up.");

        hold(startedAt, MINIMUM_DISPLAY_MILLIS);
        LoadingStatus.complete();
        openMenu();
    }

    /**
     * Holds the splash up for its minimum, so that a cached boot does not flash.
     *
     * @param minimum how much longer the screen owes the reader, in milliseconds
     */
    private void hold(long startedAt, long minimum) {
        long remaining = minimum - (System.currentTimeMillis() - startedAt);
        if (remaining <= 0L) {
            return;
        }
        try {
            Thread.sleep(remaining);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Opens the menu once, from the main thread. */
    private void openMenu() {
        synchronized (this) {
            if (opening) {
                return;
            }
            opening = true;
        }
        runOnUiThread(() -> {
            if (isFinishing()) {
                return;
            }
            Intent intent = new Intent(this, MainMenuActivity.class);
            startActivity(intent);
            AndroidApp.disableTransitionAnimation(this);
            finish();
        });
    }
}
