/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

import io.github.rwml.android.AndroidBootstrap;
import io.github.rwml.api.game.StageReport;
import io.github.rwml.android.HostLog;
import io.github.rwml.android.R;
import io.github.rwml.android.Watchdog;

/**
 * 游戏的首个界面：负责启动引擎并显示启动进度。
 *
 * <p>先显示界面，再开始加载，因为游戏加载需要数秒；如果这段时间窗口保持空白，就无法区分加载缓慢和启动失败。界面
 * 完成绘制后会启动工作线程创建引擎，随后打开菜单。</p>
 *
 * <p>加载期间，界面会显示引擎自身报告的进度：{@link BootConsole} 分流的最新引擎输出显示在下方，最新加载消息显示在上方。
 * 加载失败时会将错误报告到 logcat，界面则保持不变；没有运行中引擎的菜单随时可能发生崩溃。</p>
 */
public final class IntroScreen extends Activity {

    /** 缓存启动可能在界面首次绘制前完成，导致首帧出现闪烁。 */
    private static final long MINIMUM_DISPLAY_MILLIS = 900L;

    private volatile boolean opening;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        AndroidApp.prepare(this, true);
        setContentView(R.layout.intro_screen);
        LoadingStatus.reset();
        BootConsole.install();

        Thread worker = new Thread(this::runBoot, "rwml-boot");
        worker.setDaemon(true);
        worker.start();
    }

    /** 创建引擎并打开菜单；加载失败时停留在当前界面。 */
    private void runBoot() {
        // 加载受 CPU 限制，而接收加载信息的界面在主线程绘制，因此加载线程使用与游戏线程相同的优先级；否则加载消息
        // 会成批到达，而不是在产生时实时显示。
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        long startedAt = System.currentTimeMillis();
        // 创建引擎时会加载资源、图形和音频，耗时较长；若不查看堆栈，就无法区分加载缓慢和线程停止。
        Watchdog watchdog = Watchdog.start("boot");
        try {
            StageReport report = AndroidBootstrap.get(this).gameBoot().start(
                (percent, message) -> LoadingStatus.progress(message));
            if (!report.isClean()) {
                throw new IllegalStateException("Game boot failed: " + report.failed());
            }
        } catch (Throwable t) {
            HostLog.error("The game could not be started", t);
            LoadingStatus.progress("failed: " + t);
            return;
        } finally {
            watchdog.stop();
        }
        HostLog.add("The game is up.");

        hold(startedAt, MINIMUM_DISPLAY_MILLIS);
        LoadingStatus.complete();
        openMenu();
    }

    /**
     * 保证启动画面至少显示指定时长，避免命中缓存时界面一闪而过。
     *
     * @param minimum 界面还需显示的时长，单位为毫秒
     */
    private void hold(long startedAt, long minimum) {
        long remaining = minimum - (System.currentTimeMillis() - startedAt);
        if (remaining <= 0L) {
            return;
        }
        try {
            Thread.sleep(remaining);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 从主线程打开一次菜单。 */
    private void openMenu() {
        synchronized (this) {
            if (opening) {
                return;
            }
            opening = true;
        }
        runOnUiThread(() -> {
            if (isFinishing()) {
                return;
            }
            Intent intent = new Intent(this, MainMenuActivity.class);
            startActivity(intent);
            AndroidApp.disableTransitionAnimation(this);
            finish();
        });
    }
}
