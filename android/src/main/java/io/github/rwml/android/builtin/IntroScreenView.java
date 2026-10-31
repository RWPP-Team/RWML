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
 * 启动画面：显示游戏徽标、游戏正在加载的提示，以及引导流程最近报告的一条信息。
 *
 * <p>该界面沿用游戏原生启动画面的设计，并替代原界面：使用相同的深色背景，以及按屏幕宽度缩放的徽标。它显示
 * {@link LoadingStatus} 中的内容：引擎最近设置的加载消息和最近输出的一条日志，以便区分正在读取地图、模组和资源的
 * 加载流程与已经停止的流程。</p>
 *
 * <p>界面会自行驱动重绘。视图通过延迟请求下一帧，而不依赖 Activity 持有的计时器，因此界面暂停时会停止请求帧，
 * 恢复后则会重新开始。</p>
 */
public final class IntroScreenView extends View {

    private static final String TAG = "RWML";

    /** 两次检查加载文本之间的毫秒数。 */
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

        // 游戏会根据屏幕尺寸调整加载文本，使同一行文字在手机和平板上都能正常显示。文本宽度按较短的一边计算。
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

        // 文本变化时不会主动通知此视图，因此应请求绘制下一帧，而不是等待通知。
        postInvalidateDelayed(FRAME_DELAY);
    }

    /** 居中并缩放 Logo，在较窄的一侧留出边距。 */
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

    /** 启动流程自身的进度，用于显示当前阶段。 */
    private void drawProgressText(Canvas canvas, int width, int height) {
        String progress = LoadingStatus.progress();
        if (progress == null || progress.length() == 0) {
            return;
        }
        canvas.drawText(ellipsize(progress, width - 24.0f), width / 2.0f, height - 80.0f, paint);
    }

    /** 游戏最后输出的一行，用于表明应用仍在运行。 */
    private void drawStatusText(Canvas canvas, int width, int height) {
        String message = LoadingStatus.message();
        if (message == null || message.length() == 0) {
            return;
        }
        canvas.drawText(ellipsize(message, width - 24.0f), width / 2.0f, height - 40.0f, paint);
    }

    /** 将单行文本缩短到屏幕可容纳的宽度。 */
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
