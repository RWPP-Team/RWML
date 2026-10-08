/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import io.github.rwml.android.R;

/**
 * The splash: the game's logo, a line that says the game is loading, and the line the boot last
 * reported.
 *
 * <p>It is written the way the game's own splash is written, because it takes its place: the same
 * dark background and the same logo scaled to the width of the screen. What it shows is
 * {@link LoadingStatus}: the loading message the engine set last, and the line the engine last
 * printed, so a load that reads maps, mods and assets is told apart from one that has stopped.</p>
 *
 * <p>It drives itself. The view redraws on a delay rather than on a timer the activity owns, so a
 * screen that is paused stops asking for frames, and a screen that is resumed starts again.</p>
 */
public final class IntroScreenView extends View {

    private static final String TAG = "RWML";

    /** Milliseconds between two looks at the loading lines. */
    private static final long FRAME_DELAY = 100L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap logo;

    public IntroScreenView(Context context, AttributeSet attributes) {
        super(context, attributes);
        init(context);
    }

    private void init(Context context) {
        try {
            logo = BitmapFactory.decodeResource(getResources(), R.drawable.logo);
        } catch (Throwable t) {
            Log.w(TAG, "The splash has no logo to draw.", t);
        }

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        paint.setColor(Color.WHITE);

        // The game sizes its own loading text from the screen, so that one line reads the same on a
        // phone and on a tablet. The shorter side is what a line of text has to fit across.
        float shortest = Math.min(
            getResources().getDisplayMetrics().widthPixels,
            getResources().getDisplayMetrics().heightPixels);
        paint.setTextSize(Math.max(14.0f, shortest * 0.045f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(36, 36, 36));

        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        drawLogo(canvas, width, height);
        drawProgressText(canvas, width, height);
        drawStatusText(canvas, width, height);

        // The text changes without anyone telling this view, so the next frame is asked for rather
        // than waited for.
        postInvalidateDelayed(FRAME_DELAY);
    }

    /** The logo, centred and scaled to leave a margin on the narrower side. */
    private void drawLogo(Canvas canvas, int width, int height) {
        if (logo == null || logo.getWidth() <= 0 || logo.getHeight() <= 0) {
            return;
        }
        float scale = Math.min((width - 30.0f) / logo.getWidth(), 2.0f);
        if (scale <= 0.0f) {
            return;
        }
        canvas.save();
        canvas.scale(scale, scale);
        float drawnWidth = logo.getWidth() / 2.0f;
        float drawnHeight = logo.getHeight() / 2.0f;
        canvas.drawBitmap(logo,
            width / scale / 2.0f - drawnWidth,
            height / scale / 2.0f - drawnHeight,
            null);
        canvas.restore();
    }

    /** The boot's own progress, which names the stage that is running. */
    private void drawProgressText(Canvas canvas, int width, int height) {
        String progress = LoadingStatus.progress();
        if (progress == null || progress.length() == 0) {
            return;
        }
        canvas.drawText(ellipsize(progress, width - 24.0f), width / 2.0f, height - 80.0f, paint);
    }

    /** The last line the game printed, which is what says the app is not stuck. */
    private void drawStatusText(Canvas canvas, int width, int height) {
        String message = LoadingStatus.message();
        if (message == null || message.length() == 0) {
            return;
        }
        canvas.drawText(ellipsize(message, width - 24.0f), width / 2.0f, height - 40.0f, paint);
    }

    /** Shortens one line to the width the screen gives it. */
    private String ellipsize(String text, float maxWidth) {
        if (paint.measureText(text) <= maxWidth) {
            return text;
        }
        while (text.length() > 1 && paint.measureText(text + "...") > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "...";
    }
}
