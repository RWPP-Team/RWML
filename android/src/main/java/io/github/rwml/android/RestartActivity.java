/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Runs in a separate process so replacement dex never shares old class definitions. */
public final class RestartActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        android.widget.TextView message = new android.widget.TextView(this);
        message.setText("扩展已更新，正在重新加载…");
        message.setGravity(android.view.Gravity.CENTER);
        setContentView(message);
        int oldPid = getIntent().getIntExtra("rwml.oldPid", -1);
        if (oldPid > 0 && oldPid != android.os.Process.myPid()) { android.os.Process.killProcess(oldPid); }
        Thread worker = new Thread(() -> {
            // Wait for process death, so ActivityManager cannot deliver MainActivity to the old PID.
            long deadline = android.os.SystemClock.uptimeMillis() + 3000;
            while (oldPid > 0 && new java.io.File("/proc/" + oldPid).exists()
                    && android.os.SystemClock.uptimeMillis() < deadline) {
                try { Thread.sleep(25); } catch (InterruptedException stopped) { return; }
            }
            runOnUiThread(() -> {
                startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                finish();
            });
        }, "rwml-restart");
        worker.setDaemon(true); worker.start();
    }
}
