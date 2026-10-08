/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package com.corrodinggames.rts.appFramework;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.ListView;

/**
 * A list view that grows to show every row, instead of scrolling inside a box of its own.
 *
 * <p>The game's lobby layout puts this inside a scroll view, and the whole screen is what scrolls;
 * a list that kept its own window would both double up the scrolling and clip the rows. The height
 * is recalculated whenever the row count changes, from the measured height of the first row.</p>
 *
 * <p>This class is one of the game's own views, and the layouts RWML restores name it by its game
 * name. The archive RWML mounts does not carry it, so the app supplies it here and the restored
 * layouts stay exactly as the game wrote them.</p>
 */
public class ExpandedListView extends ListView {

    /** The row count the height was last worked out for. */
    private int measuredCount;

    public ExpandedListView(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        expandToContents();
        super.onDraw(canvas);
    }

    /** Sets the view's height to as many rows as it holds, once per change of the row count. */
    private void expandToContents() {
        int count = getCount();
        if (count == measuredCount) {
            return;
        }
        measuredCount = count;

        ViewGroup.LayoutParams parameters = getLayoutParams();
        if (parameters == null) {
            return;
        }
        int rowHeight = count > 0 && getChildAt(0) != null ? getChildAt(0).getHeight() : 0;
        parameters.height = rowHeight * count;
        setLayoutParams(parameters);
    }
}
