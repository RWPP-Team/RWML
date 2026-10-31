/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

/**
 * 在线程仍在运行时，报告它当前所处的位置。
 *
 * <p>游戏启动工作在主线程之外执行，耗时较长时，读者无法区分运行缓慢和进程停止，因为两者看起来都没有输出。
 * 在某项工作期间启动的 watchdog 会每隔几秒将执行线程的堆栈写入 {@link HostLog}。堆栈发生变化说明流程仍在
 * 继续；堆栈不变且线程处于 {@code WAITING} 或 {@code BLOCKED} 状态则说明流程卡住了。</p>
 *
 * <p>它不会中断任何操作：只有启动流程本身能完成缓慢的启动。</p>
 */
public final class Watchdog {

    /** 工作开始后，首次写入堆栈前的等待时长。 */
    private static final long GRACE_MILLIS = 10_000L;

    /** 首次之后，相邻两次堆栈输出之间的间隔。 */
    private static final long PERIOD_MILLIS = 10_000L;

    /** 观察线程检查工作是否结束的频率。 */
    private static final long SLICE_MILLIS = 500L;

    /** 堆栈中值得写出的栈帧数量；其余部分属于线程自身的调用历史。 */
    private static final int MAX_FRAMES = 24;

    private final Thread watched;
    private final String what;
    private final long startedAt;

    private volatile boolean stopped;

    private Watchdog(String what, Thread watched) {
        this.what = what;
        this.watched = watched;
        this.startedAt = System.currentTimeMillis();
    }

    /**
     * 开始监视当前调用线程。
     *
     * @param what 工作名称，会显示在报告开头
     * @return watchdog 实例；调用 {@link #stop()} 可结束监视
     */
    public static Watchdog start(String what) {
        return start(what, Thread.currentThread());
    }

    /**
     * 开始监视指定线程。
     *
     * @param what    工作名称，会显示在报告开头
     * @param watched 执行该工作的线程
     * @return watchdog 实例；调用 {@link #stop()} 可结束监视
     */
    public static Watchdog start(String what, Thread watched) {
        Watchdog watchdog = new Watchdog(what, watched);
        Thread observer = new Thread(watchdog::observe, "rwml-watchdog");
        observer.setDaemon(true);
        observer.start();
        return watchdog;
    }

    /** 结束监视。观察线程会在一个检查周期内发现并退出。 */
    public void stop() {
        stopped = true;
    }

    private void observe() {
        long waited = 0L;
        long nextReport = GRACE_MILLIS;
        while (!stopped) {
            sleep(SLICE_MILLIS);
            if (stopped) {
                return;
            }
            waited += SLICE_MILLIS;
            if (waited < nextReport) {
                continue;
            }
            nextReport = waited + PERIOD_MILLIS;
            report(waited);
        }
    }

    private void report(long waited) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
        if (!watched.isAlive()) {
            HostLog.warn("watchdog   '" + what + "' is over after " + seconds
                + "s; the thread has left it");
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append("watchdog   '").append(what).append("' has run for ").append(seconds)
            .append("s on thread '").append(watched.getName()).append("', state ")
            .append(watched.getState());
        StackTraceElement[] frames = watched.getStackTrace();
        int count = Math.min(frames.length, MAX_FRAMES);
        for (int i = 0; i < count; i++) {
            text.append("\n             at ").append(frames[i]);
        }
        HostLog.warn(text.toString());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
