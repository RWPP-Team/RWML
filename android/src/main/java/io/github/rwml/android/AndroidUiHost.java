/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;

import io.github.rwml.api.ui.UiHost;
import io.github.rwml.api.ui.UiSurface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Android 上提供给扩展界面的宿主对象。
 *
 * <p>平台句柄是启动器 Activity，因为只有 {@code Context} 能启动另一个 Activity。宿主支持的界面
 * 名称会在此处映射；遇到未知名称时会记录报告，而不会静默失败。</p>
 */
public final class AndroidUiHost implements UiHost {

    private static final String TAG = "RWML";

    /** 用于打开游戏表面的界面名称。 */
    public static final String GAME_SCREEN = "game";
    public static final String EXTRA_GAME_SURFACE = "io.github.rwml.android.gameSurface";

    private final Activity activity;
    private final List<String> arguments;

    public AndroidUiHost(Activity activity, List<String> arguments) {
        this.activity = activity;
        this.arguments = Collections.unmodifiableList(
            arguments == null ? new ArrayList<String>() : new ArrayList<>(arguments));
    }

    @Override
    public Object platform() {
        return activity;
    }

    @Override
    public UiSurface createSurface() {
        if (!(activity instanceof ComponentActivity)) {
            throw new UnsupportedOperationException(
                "Extension UI surfaces require a ComponentActivity-backed host");
        }
        return new AndroidUiSurface((ComponentActivity) activity);
    }

    @Override
    public UiSurface createGameSurface() {
        return new AndroidGameSurface(activity);
    }

    @Override
    public List<String> arguments() {
        return arguments;
    }

    @Override
    public void restart() {
        activity.runOnUiThread(() -> activity.startActivity(new Intent(activity, RestartActivity.class)
            .putExtra("rwml.oldPid", android.os.Process.myPid())));
    }

    @Override
    public void openScreen(String screen) {
        if (services().openRegisteredScreen(screen, this)) { return; }
        final String type = screenType(screen);
        if (type == null) {
            Log.w(TAG, "The Android host has no screen called '" + screen + "'.");
            return;
        }
        // 界面对应一个 Activity，只有主线程才能启动它。界面由加载线程启动，因此线程切换应由此处
        // 统一处理，而不应要求每个扩展各自实现。
        activity.runOnUiThread(() -> {
            Intent intent = new Intent().setClassName(activity, type);
            activity.startActivity(intent);
        });
    }

    @Override
    public void openScreen(String screen, UiSurface surface) {
        if (GAME_SCREEN.equals(screen) && surface instanceof AndroidGameSurface) {
            AndroidGameSurface gameSurface = (AndroidGameSurface) surface;
            activity.runOnUiThread(() -> {
                if (gameSurface.isClosed() || gameSurface.isActivityActive()) {
                    return;
                }
                Intent intent = new Intent()
                    .setClassName(activity, "io.github.rwml.android.builtin.InGameActivity")
                    .putExtra(EXTRA_GAME_SURFACE, gameSurface.id());
                activity.startActivity(intent);
            });
            return;
        }
        openScreen(screen);
    }

    /** Attaches a pending extension surface to the existing game Activity's view hierarchy. */
    public static AndroidGameSurface attachGameSurface(
        String surfaceId,
        Activity activity,
        FrameLayout gameRoot,
        Bundle savedInstanceState
    ) {
        return AndroidGameSurface.attachTo(surfaceId, activity, gameRoot, savedInstanceState);
    }

    /** @return 显示 {@code screen} 的 Activity；宿主没有此界面时返回 {@code null}。 */
    private static String screenType(String screen) {
        if ("launcher".equals(screen)) {
            return "io.github.rwml.android.builtin.LauncherActivity";
        }
        if ("extensions".equals(screen)) {
            return "io.github.rwml.android.builtin.ExtensionManagerActivity";
        }
        if ("extension-catalog".equals(screen)) {
            return "io.github.rwml.android.builtin.ExtensionCatalogActivity";
        }
        if ("main-menu".equals(screen)) {
            return "io.github.rwml.android.builtin.MainMenuActivity";
        }
        return GAME_SCREEN.equals(screen) ? "io.github.rwml.android.builtin.InGameActivity" : null;
    }
}
