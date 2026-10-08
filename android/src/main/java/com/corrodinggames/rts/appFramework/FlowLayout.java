/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package com.corrodinggames.rts.appFramework;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import io.github.rwml.android.R;

/**
 * A view group that lays its children out in rows, wrapping to the next row when one is full.
 *
 * <p>The game's lobby layout uses it for the row of join and host panels, which have to sit side by
 * side on a wide screen and stack on a narrow one. The spacing between children comes from the
 * layout's own {@code horizontalSpacing} and {@code verticalSpacing} attributes, and a child may
 * carry a {@code layout_breakLine} to start a row of its own.</p>
 *
 * <p>This class is one of the game's own views, and the layouts RWML restores name it by its game
 * name. The archive RWML mounts does not carry it, so the app supplies it here and the restored
 * layouts stay exactly as the game wrote them.</p>
 */
public class FlowLayout extends ViewGroup {

    private final int horizontalSpacing;
    private final int verticalSpacing;

    public FlowLayout(Context context, AttributeSet attributes) {
        super(context, attributes);
        TypedArray values = context.obtainStyledAttributes(attributes, R.styleable.FlowLayout);
        try {
            horizontalSpacing = values.getDimensionPixelSize(R.styleable.FlowLayout_horizontalSpacing, 0);
            verticalSpacing = values.getDimensionPixelSize(R.styleable.FlowLayout_verticalSpacing, 0);
        } finally {
            values.recycle();
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attributes) {
        return new LayoutParams(getContext(), attributes);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams source) {
        return new LayoutParams(source.width, source.height);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams parameters) {
        return parameters instanceof LayoutParams;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        boolean bounded = widthMode != MeasureSpec.UNSPECIFIED;
        int available = widthSize - getPaddingRight();

        int widestRow = 0;
        int x = getPaddingLeft();
        int y = getPaddingTop();
        int rowHeight = 0;
        int spacing = 0;
        boolean breakLine = false;

        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            measureChild(child, widthMeasureSpec, heightMeasureSpec);
            LayoutParams parameters = (LayoutParams) child.getLayoutParams();
            int childSpacing = parameters.horizontalSpacing >= 0 ? parameters.horizontalSpacing : horizontalSpacing;

            // The row breaks when the child would run past the right edge, or when the child asks
            // for it. A child that does not fit an empty row still starts one, so it stays visible.
            if (bounded && (breakLine || x > getPaddingLeft() && child.getMeasuredWidth() + x > available)) {
                y = y + rowHeight + verticalSpacing;
                widestRow = Math.max(widestRow, x - childSpacing);
                x = getPaddingLeft();
                rowHeight = 0;
            }

            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            parameters.x = x;
            parameters.y = y;
            breakLine = parameters.breakLine;
            x = child.getMeasuredWidth() + childSpacing + x;
            spacing = childSpacing;
        }

        int width = Math.max(widestRow, x - spacing) + getPaddingRight();
        int height = y + rowHeight + getPaddingBottom();
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            LayoutParams parameters = (LayoutParams) child.getLayoutParams();
            child.layout(parameters.x, parameters.y,
                parameters.x + child.getMeasuredWidth(), parameters.y + child.getMeasuredHeight());
        }
    }

    /** Where a child sits, and how it asks to be placed: its own spacing, or a row of its own. */
    public static class LayoutParams extends ViewGroup.LayoutParams {

        int x;
        int y;

        /** The spacing to the left of this child; a negative value means the layout's own. */
        public int horizontalSpacing = -1;

        /** Whether this child starts a new row. */
        public boolean breakLine;

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(Context context, AttributeSet attributes) {
            super(context, attributes);
            TypedArray values = context.obtainStyledAttributes(
                attributes, R.styleable.FlowLayout_LayoutParams);
            try {
                horizontalSpacing = values.getDimensionPixelSize(
                    R.styleable.FlowLayout_LayoutParams_layout_horizontalSpacing, -1);
                breakLine = values.getBoolean(
                    R.styleable.FlowLayout_LayoutParams_layout_breakLine, false);
            } finally {
                values.recycle();
            }
        }
    }
}
