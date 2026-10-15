/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Gallery;
import android.widget.ImageView;
import android.widget.LinearLayout;

import io.github.rwml.api.Rwml;
import io.github.rwml.api.ui.GameSettings;

import io.github.rwml.android.R;

/**
 * The help slides: four pictures that say how the game is played.
 *
 * <p>This is the game's own quick help, kept as it shipped. The slides are the game's drawables,
 * the strip underneath picks one, and tapping the picture advances to the next - or leaves, after
 * the last. Opening the screen also marks the help as seen in the game's settings, which is what
 * stops the menu's first-run prompt from offering it again.</p>
 *
 * <p>The gallery widget is the one the game used and the platform still carries it, deprecated or
 * not, so it stays: the layout names it.</p>
 */
public final class QuickHelpActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    /**
     * How long the first tap is ignored after the screen opens.
     *
     * <p>The tap that opened the screen is often still landing when it does, and without the delay
     * it advances the first slide the user never saw.</p>
     */
    private static final long TOUCH_GUARD_MILLIS = 500L;

    private Bitmap[] slides;
    private Gallery gallery;
    private ImageView image;
    private LinearLayout outer;
    private int current = -1;
    private long touchGuardUntil;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        AndroidApp.prepare(this, false);
        setContentView(R.layout.quick_help);

        int[] ids = {R.drawable.help1, R.drawable.help2, R.drawable.help3, R.drawable.help4};
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        slides = new Bitmap[ids.length];
        for (int i = 0; i < ids.length; i++) {
            slides[i] = BitmapFactory.decodeResource(getResources(), ids[i], options);
        }

        GameSettings settings = Rwml.get().ui().find(GameSettings.class);
        settings.set("hasPlayedGameOrSeenHelp", true);
        settings.save();

        gallery = (Gallery) findViewById(R.id.quickhelp_gallery);
        gallery.setAdapter(new SlideStrip());
        gallery.setOnItemClickListener((parent, view, position, rowId) -> showSlide(position));

        image = (ImageView) findViewById(R.id.quickhelp_image);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setOnClickListener(clicked -> advance());

        outer = (LinearLayout) findViewById(R.id.quickhelp_outerlayout);
        touchGuardUntil = System.currentTimeMillis() + TOUCH_GUARD_MILLIS;
        Log.i(TAG, "Help open.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        AndroidApp.prepare(this, false);
    }

    @Override
    protected void onStop() {
        // The slides are a look, not a place: navigating away from them leaves the screen.
        finish();
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        if (current == -1) {
            gallery.setSelection(0);
            showSlide(0);
        }
        super.onWindowFocusChanged(focused);
    }

    /** Advances to the next slide, or leaves when the last one was showing. */
    private void advance() {
        if (System.currentTimeMillis() < touchGuardUntil) {
            return;
        }
        int next = gallery.getSelectedItemPosition() + 1;
        if (next >= slides.length) {
            finish();
            return;
        }
        gallery.setSelection(next, true);
        showSlide(next);
    }

    /**
     * Shows one slide, sized to the room there is for it.
     *
     * <p>The picture keeps its own proportions: it is scaled until one side meets the layout, and
     * the other side is whatever the proportions make it.</p>
     */
    private void showSlide(int position) {
        current = position;
        Bitmap slide = slides[position];
        image.setImageBitmap(slide);
        int width = outer.getWidth();
        int height = outer.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        if ((int) (slide.getHeight() * ((float) width / slide.getWidth())) < height) {
            height = (int) ((float) slide.getHeight() / slide.getWidth() * width);
        } else {
            width = (int) (height * ((float) slide.getWidth() / slide.getHeight()));
        }
        ViewGroup.LayoutParams size = image.getLayoutParams();
        size.width = width;
        size.height = height;
        image.setLayoutParams(size);
    }

    /** The strip of slide thumbnails under the picture. */
    private final class SlideStrip extends BaseAdapter {

        @Override
        public int getCount() {
            return slides.length;
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView thumbnail = new ImageView(QuickHelpActivity.this);
            thumbnail.setImageBitmap(slides[position]);
            float density = getResources().getDisplayMetrics().density;
            thumbnail.setLayoutParams(new Gallery.LayoutParams(
                (int) (80 * density), (int) (70 * density)));
            thumbnail.setScaleType(ImageView.ScaleType.FIT_XY);
            return thumbnail;
        }
    }
}
