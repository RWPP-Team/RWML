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
import android.annotation.SuppressLint;
import android.widget.ImageView;

/**
 * 根据当前显示的 drawable 调整自身高度的图像视图。
 *
 * <p>游戏用它显示地图预览：缩略图宽度与所在栏相同，视图根据 drawable 的宽高比计算高度，避免宽地图下方出现空隙。
 * {@link #setMaxFixedHeight(int)} 可限制高度，{@link #setResizeWidth(boolean)} 则可改为根据高度调整宽度。</p>
 *
 * <p>此类是游戏自带的视图之一，RWML 还原的布局文件使用游戏中的类名引用它。RWML 挂载的归档不包含此类，
 * 因此由应用在此提供实现，确保还原后的布局与游戏原始文件完全一致。</p>
 */
// This game-provided view intentionally uses the platform ImageView to preserve its rendering behavior.
@SuppressLint("AppCompatCustomView")
public class DynamicImageView extends ImageView {

    /** 覆盖 drawable 自身高度的值；{@code -1} 表示由 drawable 自行决定。 */
    private int maxFixedHeight;

    /** 宽度是否随高度变化，而不是高度随宽度变化。 */
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
            // 没有可用于计算尺寸的内容：沿用父类的测量结果。
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int intrinsicWidth = drawable.getIntrinsicWidth();
        int intrinsicHeight = drawable.getIntrinsicHeight();
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
            // drawable 没有固有尺寸，也就无法据此计算视图尺寸。
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
        // 最终由宽度决定尺寸，因此高度需随宽度调整。
            height = (int) Math.ceil((float) available * intrinsicHeight / intrinsicWidth);
        } else {
            available = wantedWidth;
        }
        setMeasuredDimension(available, height);
    }

    /** 限制视图高度；大厅中的固定尺寸预览使用此设置。 */
    public void setMaxFixedHeight(int height) {
        this.maxFixedHeight = height;
    }

    /** 设置宽度随高度变化，而不是高度随宽度变化。 */
    public void setResizeWidth(boolean resize) {
        this.widthResize = resize;
    }
}
