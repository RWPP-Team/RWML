/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

/**
 * 游戏最近输出的一条日志，供加载期间显示该内容的界面使用。
 *
 * <p>游戏在工作线程中运行，并通过控制台输出进度；界面则在主线程绘制。此处只保留最新一行，而不使用队列，
 * 因为界面按固定频率绘制时只会显示最新内容。{@link BootConsole} 负责转发日志行。</p>
 */
public final class LoadingStatus {

    /** 游戏最近输出的一行日志。 */
    private static volatile String message = "";

    /** 引导流程报告的当前状态，例如 {@code boot [  1%] engine: assets}。 */
    private static volatile String progress = "";

    /** 引导流程是否已经结束，无论成功与否。 */
    private static volatile boolean finished;

    private LoadingStatus() {
    }

    /** 记录游戏控制台输出的一行日志。 */
    public static void line(String message) {
        LoadingStatus.message = message == null ? "" : message;
    }

    /** 记录引导流程自身的进度信息。 */
    public static void progress(String line) {
        progress = line == null ? "" : line;
    }

    /** @return 引导流程自身的进度信息；首次报告前为空 */
    public static String progress() {
        return progress;
    }

    /** 记录引导流程已经结束。 */
    public static void complete() {
        finished = true;
    }

    /** @return 游戏最近输出的一行日志，不会为 null */
    public static String message() {
        return message;
    }

    /** @return 引导流程是否已经结束 */
    public static boolean isFinished() {
        return finished;
    }

    /** 清除所有状态，避免第二次运行时显示上一次运行的最后一行日志。 */
    public static void reset() {
        message = "";
        progress = "";
        finished = false;
    }
}
