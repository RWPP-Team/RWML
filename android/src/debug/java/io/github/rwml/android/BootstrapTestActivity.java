/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Debug-only window that keeps instrumentation foreground without starting a competing game boot. */
public final class BootstrapTestActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        EngineContext.obtain(getApplicationContext());
        TextView text = new TextView(this);
        text.setText("RWML device regression checks");
        setContentView(text);
    }
}
