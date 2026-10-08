/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package com.corrodinggames.rts.appFramework;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * An image view that takes its height from the drawable it is showing.
 *
 * <p>The game uses it for the map preview: the thumbnail is as wide as its column and the view asks
 * the drawable for the height that keeps its aspect ratio, so a wide map does not leave a gap under
 * it. {@link #setMaxFixedHeight(int)} caps that height, and {@link #setResizeWidth(boolean)} makes
 * the width follow the height instead of the other way round.</p>
 *
 * <p>This class is one of the game's own views, and the layouts RWML restores name it by its game
 * name. The archive RWML mounts does not carry it, so the app supplies it here and the restored
 * layouts stay exactly as the game wrote them.</p>
 */
public class DynamicImageView extends ImageView {

    /** The height to use instead of the drawable's own; {@code -1} means the drawable decides. */
    private int maxFixedHeight;

    /** Whether the width follows the height rather than the height the width. */
    private boolean widthResize;

    public DynamicImageView(Context context) {
        super(context);
        this.maxFixedHeight = -1;
    }

    public DynamicImageView(Context context, AttributeSet attributes) {
        super(context, attributes);
        this.maxFixedHeight = -1;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Drawable drawable = getDrawable();
        if (drawable == null) {
            // Nothing to take a shape from: the parent's own measurement stands.
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int intrinsicWidth = drawable.getIntrinsicWidth();
        int intrinsicHeight = drawable.getIntrinsicHeight();
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
            // A drawable without an intrinsic size cannot decide anything either.
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        if (!widthResize && maxFixedHeight == -1) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(width, (int) Math.ceil((float) width * intrinsicHeight / intrinsicWidth));
            return;
        }

        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (maxFixedHeight != -1) {
            height = maxFixedHeight;
        }
        int wantedWidth = (int) Math.ceil(height * ((float) intrinsicWidth / intrinsicHeight));
        int available = MeasureSpec.getSize(widthMeasureSpec);
        if (wantedWidth > available) {
            // The width is the binding constraint after all, so the height gives way to it.
            height = (int) Math.ceil((float) available * intrinsicHeight / intrinsicWidth);
        } else {
            available = wantedWidth;
        }
        setMeasuredDimension(available, height);
    }

    /** Caps the view's height, which is what the lobby's fixed-size preview uses. */
    public void setMaxFixedHeight(int height) {
        this.maxFixedHeight = height;
    }

    /** Makes the width follow the height instead of the height the width. */
    public void setResizeWidth(boolean resize) {
        this.widthResize = resize;
    }
}
