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
 * 帮助幻灯片：用四张图片介绍游戏玩法。
 *
 * <p>此处保留游戏原有的快速帮助界面。幻灯片使用游戏自带的 drawable，底部缩略条用于选择图片，点击大图可前进到下一页，
 * 最后一页之后则退出。打开此界面也会在游戏设置中标记帮助为已读，避免菜单首次运行提示再次显示。</p>
 *
 * <p>布局使用了游戏原先采用的 Gallery 控件，平台仍然保留该控件，因此即使它已弃用也继续使用。</p>
 */
public final class QuickHelpActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    /**
     * 界面打开后忽略首次点击的时长。
     *
     * <p>界面打开时，触发打开操作的点击通常还未结束；若没有延迟，就会跳过用户尚未看到的第一页。</p>
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
        // 幻灯片只用于查看，导航到其他位置时应退出此界面。
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

    /** 切换到下一页；已是最后一页时退出。 */
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
     * 显示一张幻灯片，并按可用空间调整尺寸。
     *
     * <p>图片保持原有比例：缩放至一边适配布局，另一边的尺寸由图片比例决定。</p>
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

    /** 图片下方的幻灯片缩略图栏。 */
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
