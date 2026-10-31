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
 * 按行排列子视图，当前行放满后自动换行的视图组。
 *
 * <p>游戏大厅布局使用它排列加入和主机面板：宽屏时并排显示，窄屏时上下堆叠。子视图之间的间距由布局自身的
 * {@code horizontalSpacing} 和 {@code verticalSpacing} 属性决定；子视图也可以通过 {@code layout_breakLine}
 * 属性单独换行。</p>
 *
 * <p>此类是游戏自带的视图之一，RWML 还原的布局文件使用游戏中的类名引用它。RWML 挂载的归档不包含此类，
 * 因此由应用在此提供实现，确保还原后的布局与游戏原始文件完全一致。</p>
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

            // 子视图超出右边界或主动要求换行时，当前行结束。即使子视图无法放入空行，也会为它新开一行以确保可见。
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

    /** 子视图的布局参数，以及它对放置方式的要求：使用自身间距，或另起一行。 */
    public static class LayoutParams extends ViewGroup.LayoutParams {

        int x;
        int y;

        /** 子视图左侧间距；负值表示使用布局自身设置。 */
        public int horizontalSpacing = -1;

        /** 此子视图是否另起一行。 */
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
