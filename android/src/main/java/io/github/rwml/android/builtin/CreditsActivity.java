/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import io.github.rwml.android.R;

/**
 * The credits screen: the game's own credits layout, filled with the game's own credits text.
 *
 * <p>The text is two locale entries joined by a blank line, which is what the game's screen shows:
 * the credits proper, then the notices for the third-party work the game is built on. Both come
 * from the engine's language file, so the screen reads in whatever language the game is set to.</p>
 *
 * <p>There is nothing to save here, so the screen has a single action: close it.</p>
 */
public final class CreditsActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(LocalizedText.ui("ui.credits"));
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        AndroidApp.prepare(this, true);
        setContentView(R.layout.credits);

        fillCredits();
        onClick(R.id.creditsClose, this::finish);
        Log.i(TAG, "Credits open.");
    }

    /** Writes the game's credits text, leaving the layout's own text when it cannot be read. */
    private void fillCredits() {
        View view = findViewById(R.id.creditsText);
        if (!(view instanceof TextView)) {
            Log.w(TAG, "The credits layout has no text view.");
            return;
        }
        String main = LocalizedText.get("menus.credits.main");
        String notices = LocalizedText.get("menus.credits.notices");
        if (main == null && notices == null) {
            return;
        }
        ((TextView) view).setText(
            (main == null ? "" : main) + "\n" + (notices == null ? "" : notices));
    }

    private void onClick(int id, Runnable action) {
        View view = findViewById(id);
        if (view == null) {
            Log.w(TAG, "The credits layout has no view for " + id + ".");
            return;
        }
        view.setOnClickListener(clicked -> action.run());
    }
}
