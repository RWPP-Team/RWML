/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;

/** Applies shared labels when a restored layout is attached, before binding dynamic game data. */
public abstract class BuiltinActivity extends Activity {
    @Override
    public void onContentChanged() {
        super.onContentChanged();
        LocalizedText.applyLabels(findViewById(android.R.id.content));
    }
}
