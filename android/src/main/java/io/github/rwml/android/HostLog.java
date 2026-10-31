/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Android 宿主在此报告运行状态，游戏控制台输出也会转发到此处。
 *
 * <p>每行内容都会写入两处：使用 {@value #TAG} 标签写入 logcat，并写入标准输出流。按标签或进程过滤 logcat
 * 的读者可能看不到第一处内容，但仍可看到第二处。两处内容都由产生该行的线程写入，因此记录保持一致。</p>
 *
 * <p>标准流采用分流而非替换方式：如果宿主向 {@code System.err} 输出内容，替换后的流可能没有连接任何控制台，
 * 导致内容被写入却无人可见。分流器会转发每个字节，同时保留一份副本。</p>
 *
 * <p>失败不会被简化为一条消息。异常会写入 logcat，由其输出完整堆栈；相同堆栈也会写入标准错误流。默认的
 * 未捕获异常处理器也会被替换为执行相同操作的处理器，以便记录线程在哪里终止及其原因。</p>
 *
 * <p>需要显示游戏控制台的界面可以注册 {@link Sink}，且只会收到游戏输出的内容：若将宿主警告混入游戏自身的
 * 加载信息，只会造成干扰。无论是否注册接收器，每行内容都会写入 logcat、标准流和有界缓冲区。</p>
 */
public final class HostLog {

    /** 每条 logcat 日志使用的标签。 */
    public static final String TAG = "RWML";

    /** 缓冲区保留的日志行数；超出后会丢弃最旧的日志。 */
    private static final int MAX_LINES = 500;

    private static final int INFO = 0;
    private static final int WARNING = 1;
    private static final int CONSOLE = 2;
    private static final int ERROR = 3;

    private static final Object LOCK = new Object();
    private static final List<String> lines = new ArrayList<>();

    /**
     * 防止输出流回指到此处：宿主可能会将 {@code System.out} 替换为自己的流，若再将内容回写到此日志，
     * 就会不断重复记录。
     */
    private static final ThreadLocal<boolean[]> ECHOING = new ThreadLocal<boolean[]>() {
        @Override
        protected boolean[] initialValue() {
            return new boolean[1];
        }
    };

    private static volatile Sink sink;
    private static volatile boolean installed;

    /** 分流器替换 {@code System.out} 前使用的输出流。 */
    private static volatile PrintStream out;

    /** 分流器替换 {@code System.err} 前使用的输出流。 */
    private static volatile PrintStream err;

    /** 此类接管前负责处理未捕获异常的处理器。 */
    private static volatile Thread.UncaughtExceptionHandler previousHandler;

    private HostLog() {
    }

    /** 在界面需要显示日志时接收一行内容。 */
    public interface Sink {

        /**
     * @param line 一条完整日志行，不为空且不为 null
         */
        void accept(String line);
    }

    /**
     * 将标准流分流到此日志，并接管未捕获异常；只执行一次。
     *
     * <p>必须先完成初始化才能开始输出，因此启动器会最先调用此方法。</p>
     */
    public static void install() {
        if (installed) {
            return;
        }
        synchronized (HostLog.class) {
            if (installed) {
                return;
            }
            out = System.out;
            err = System.err;
            System.setOut(new PrintStream(new Tee(out, CONSOLE), true));
            System.setErr(new PrintStream(new Tee(err, WARNING), true));
            previousHandler = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable failure) {
                    HostLog.uncaught(thread, failure);
                }
            });
            installed = true;
        }
    }

    /**
     * 设置显示游戏控制台的界面，并替换之前设置的界面。
     *
     * <p>该界面会在进程存续期间一直有效：启动画面关闭后，游戏仍会继续输出内容。</p>
     */
    public static void sink(Sink listener) {
        sink = listener;
    }

    /** 记录一条宿主自身的操作信息。 */
    public static void add(String line) {
        publish(line, INFO, null);
    }

    /** 记录一条异常路径信息。 */
    public static void warn(String line) {
        publish(line, WARNING, null);
    }

    /** 记录一条日志和对应异常，并附上异常堆栈。 */
    public static void warn(String line, Throwable failure) {
        publish(line, WARNING, failure);
    }

    /** 记录异常及其堆栈。 */
    public static void error(String line, Throwable failure) {
        publish(line, ERROR, failure);
    }

    /** @return 目前记录的日志行，从最旧到最新排列 */
    public static List<String> lines() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(lines));
        }
    }

    /** 报告线程因未捕获异常而终止。 */
    private static void uncaught(Thread thread, Throwable failure) {
        error("unhandled on thread '" + thread.getName() + "'", failure);
        Thread.UncaughtExceptionHandler previous = previousHandler;
        if (previous != null) {
            previous.uncaughtException(thread, failure);
            return;
        }
        // 接管默认处理器后，线程组自己的处理器将不再被调用，而主线程发生未捕获异常时正是由它结束进程。
        // 将异常交还给该处理器，可保留平台原有的进程处理方式。
        ThreadGroup group = thread.getThreadGroup();
        if (group != null) {
            group.uncaughtException(thread, failure);
        }
    }

    private static void publish(String line, int kind, Throwable failure) {
        String text = line == null ? "" : line.trim();
        if (text.isEmpty() && failure == null) {
            return;
        }
        if (text.isEmpty()) {
            text = failure.toString();
        }
        if (failure != null) {
            if (kind == WARNING) {
                Log.w(TAG, text, failure);
            } else {
                Log.e(TAG, text, failure);
            }
        } else if (kind == WARNING) {
            Log.w(TAG, text);
        } else if (kind == ERROR) {
            Log.e(TAG, text);
        } else {
            Log.i(TAG, text);
        }
        keep(text);
        echo(text, kind);
        if (failure != null) {
            echoTrace(failure);
        }
        if (kind != CONSOLE) {
            return;
        }
        Sink current = sink;
        if (current != null) {
            try {
                current.accept(text);
            } catch (Throwable ignored) {
                // 界面无法接收日志行时，不应影响其原本只负责描述的运行流程。
            }
        }
    }

    private static void keep(String line) {
        synchronized (LOCK) {
            lines.add(line);
            while (lines.size() > MAX_LINES) {
                lines.remove(0);
            }
        }
    }

    /**
     * 将一行内容写入对应输出流，也就是分流器替换前的标准输出流或标准错误流。
     *
     * <p>若写入当前输出流，内容会进入分流器并再次记录、转发，最终形成无限循环。</p>
     */
    private static void echo(String text, int kind) {
        boolean[] guard = ECHOING.get();
        if (guard[0]) {
            return;
        }
        guard[0] = true;
        try {
            boolean toErr = kind == WARNING || kind == ERROR;
            PrintStream stream = toErr ? err : out;
            if (stream == null) {
                // 分流器尚未安装，因此此时的流仍为原始流，不会回指到此处。
                stream = toErr ? System.err : System.out;
            }
            stream.println(text);
            stream.flush();
        } catch (Throwable ignored) {
            // 输出流无法写入日志时，不应中断产生该日志的运行流程。
        } finally {
            guard[0] = false;
        }
    }

    /** 将完整异常堆栈写入标准错误流。 */
    private static void echoTrace(Throwable failure) {
        boolean[] guard = ECHOING.get();
        if (guard[0]) {
            return;
        }
        guard[0] = true;
        try {
            PrintStream stream = err == null ? System.err : err;
            failure.printStackTrace(stream);
            stream.flush();
        } catch (Throwable ignored) {
            // 无需额外处理：堆栈已通过 Log 写入 logcat。
        } finally {
            guard[0] = false;
        }
    }

    /** 将每个字节转发到被替换的流，并将完整日志行写入此日志。 */
    private static final class Tee extends OutputStream {

        private final PrintStream original;
        private final int kind;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        Tee(PrintStream original, int kind) {
            this.original = original;
            this.kind = kind;
        }

        @Override
        public void write(int value) {
            original.write(value);
            append(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            original.write(bytes, offset, length);
            for (int i = offset; i < offset + length; i++) {
                append(bytes[i]);
            }
        }

        @Override
        public void flush() {
            original.flush();
            emit();
        }

        private void append(int value) {
            if (value == '\n') {
                emit();
            } else {
                line.write(value);
            }
        }

        private void emit() {
            if (line.size() == 0) {
                return;
            }
            byte[] bytes = line.toByteArray();
            line.reset();
            publish(new String(bytes, StandardCharsets.UTF_8), kind, null);
        }
    }
}
