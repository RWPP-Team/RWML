/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */
package io.github.rwml.android.builtin;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;

import io.github.rwml.android.AndroidBootstrap;
import io.github.rwml.api.game.GameBoot;
import io.github.rwml.api.game.StageReport;


/** Device regression tests without a dependency on a second test framework. */
public final class NavigationRegressionRunner extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle results = new Bundle();
        int resultCode = Activity.RESULT_CANCELED;
        Activity bootstrapScreen = null;
        try {
            // Keep the test process in the foreground while the first-run dex cache is built.
            // Some devices suspend even instrumented processes that have no visible activity.
            bootstrapScreen = startActivitySync(new Intent().setClassName(getTargetContext(),
                "io.github.rwml.android.BootstrapTestActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AndroidBootstrap runtime = AndroidBootstrap.get(getTargetContext());
            verifyEarlyInterface(runtime);
            verifySharedGameBoot(runtime.gameBoot());
            // Do not load checks whose signatures mention game classes before mounting their dex.
            new NavigationRegressionChecks(this).run();
            results.putString("stream", "\nTests run: 6, Failures: 0\n");
            resultCode = Activity.RESULT_OK;
        } catch (Throwable failure) {
            results.putString("stream", android.util.Log.getStackTraceString(failure));
        } finally {
            if (bootstrapScreen != null) {
                Activity screen = bootstrapScreen;
                runOnMainSync(screen::finish);
            }
        }
        finish(resultCode, results);
    }

    private void verifySharedGameBoot(GameBoot boot) {
        Bundle status = new Bundle();
        status.putString("class", getClass().getName());
        status.putString("test", "sharedGameBoot");
        status.putInt("numtests", 6);
        sendStatus(1, status);
        try {
            if (boot == null) {
                throw new AssertionError("Android must expose the same boot contract as desktop");
            }
            java.util.List<String> progress = new java.util.ArrayList<>();
            StageReport report = boot.start((percent, message) -> progress.add(message));
            if (!report.isClean() || progress.isEmpty() || boot.start() != report) {
                throw new AssertionError("The shared boot must report progress and run only once: "
                    + report.failed());
            }
            sendStatus(0, status);
        } catch (Throwable failure) {
            status.putString("stack", android.util.Log.getStackTraceString(failure));
            sendStatus(-2, status);
            throw failure;
        }
    }

    private void verifyEarlyInterface(AndroidBootstrap runtime) throws Exception {
        Bundle status = new Bundle();
        status.putString("class", getClass().getName());
        status.putString("test", "interfaceBeforeGameDex");
        status.putInt("numtests", 6);
        sendStatus(1, status);
        runtime.prepareInterface();
        if (AndroidBootstrap.gameLoader() != null || runtime.gameBoot() == null
                || runtime.gameBoot().isStarted() || runtime.uiFramework() == null) {
            throw new AssertionError("The selected UI must be loadable before mounting/booting the game");
        }
        sendStatus(0, status);
    }
}
