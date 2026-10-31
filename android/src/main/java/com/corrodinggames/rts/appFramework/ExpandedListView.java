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
 * 自动扩展以显示所有行，而不是在自身区域内滚动的列表视图。
 *
 * <p>游戏大厅布局将此视图放在滚动视图中，整个屏幕负责滚动；若列表自身也保留滚动区域，就会出现双重滚动且行内容被裁切。
 * 每当行数变化时，都会根据首行测量高度重新计算列表高度。</p>
 *
 * <p>此类是游戏自带的视图之一，RWML 还原的布局文件使用游戏中的类名引用它。RWML 挂载的归档不包含此类，
 * 因此由应用在此提供实现，确保还原后的布局与游戏原始文件完全一致。</p>
 */
public class ExpandedListView extends ListView {

    /** 上次计算高度时的行数。 */
    private int measuredCount;

    public ExpandedListView(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        expandToContents();
        super.onDraw(canvas);
    }

    /** 每次行数变化时，将视图高度调整为恰好容纳所有行。 */
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
