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
 * Where the Android host says what it is doing, and where the game's own console ends up.
 *
 * <p>Every line is written twice: logcat under one tag, {@value #TAG}, and the standard output
 * stream, because a reader who filters logcat by tag or by process can miss the first and will still
 * see the second. Both are written on the thread that produced the line, so the two accounts agree.</p>
 *
 * <p>The standard streams are teed rather than replaced: a host that prints to {@code System.err}
 * writes somewhere no console is attached to, so the line is written and never seen. The tee forwards
 * every byte and takes a copy.</p>
 *
 * <p>A failure is never reduced to its message. The throwable goes to logcat, which prints the whole
 * stack, and the same stack goes to the standard error stream; the default uncaught handler is
 * replaced with one that does the same, so a thread that dies is said to have died and where.</p>
 *
 * <p>A screen that wants the game's console registers a {@link Sink}, and only the lines the game
 * printed are offered to it: a warning from the host over the game's own account of the load is noise.
 * Every line goes to logcat, to the standard stream and into a bounded buffer regardless.</p>
 */
public final class HostLog {

    /** The tag every line carries in logcat. */
    public static final String TAG = "RWML";

    /** How many lines the buffer keeps; the oldest is dropped past that. */
    private static final int MAX_LINES = 500;

    private static final int INFO = 0;
    private static final int WARNING = 1;
    private static final int CONSOLE = 2;
    private static final int ERROR = 3;

    private static final Object LOCK = new Object();
    private static final List<String> lines = new ArrayList<>();

    /**
     * Guards against a stream that leads back here: a host may replace {@code System.out} with
     * something of its own, and an echo into this log would be logged again, without end.
     */
    private static final ThreadLocal<boolean[]> ECHOING = new ThreadLocal<boolean[]>() {
        @Override
        protected boolean[] initialValue() {
            return new boolean[1];
        }
    };

    private static volatile Sink sink;
    private static volatile boolean installed;

    /** The stream that was {@code System.out} before the tee replaced it. */
    private static volatile PrintStream out;

    /** The stream that was {@code System.err} before the tee replaced it. */
    private static volatile PrintStream err;

    /** Who handled an uncaught throwable before this class did. */
    private static volatile Thread.UncaughtExceptionHandler previousHandler;

    private HostLog() {
    }

    /** Takes one line while a screen wants to show them. */
    public interface Sink {

        /**
         * @param line one completed line, never empty and never null
         */
        void accept(String line);
    }

    /**
     * Tee the standard streams into this log, once, and take over uncaught failures.
     *
     * <p>It has to be up before anything prints, which is why the bootstrapper calls it first.</p>
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
     * Sets the screen that shows the game's console, replacing whatever showed it before.
     *
     * <p>It stays for the life of the process: the game keeps printing after the splash is gone.</p>
     */
    public static void sink(Sink listener) {
        sink = listener;
    }

    /** Records one line of the host's own account of what it did. */
    public static void add(String line) {
        publish(line, INFO, null);
    }

    /** Records one line that is not the happy path. */
    public static void warn(String line) {
        publish(line, WARNING, null);
    }

    /** Records a line and the failure behind it, with the stack the failure carries. */
    public static void warn(String line, Throwable failure) {
        publish(line, WARNING, failure);
    }

    /** Records a failure, with the stack the failure carries. */
    public static void error(String line, Throwable failure) {
        publish(line, ERROR, failure);
    }

    /** @return the lines recorded so far, oldest first */
    public static List<String> lines() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(lines));
        }
    }

    /** Reports a thread that died of something it did not catch. */
    private static void uncaught(Thread thread, Throwable failure) {
        error("unhandled on thread '" + thread.getName() + "'", failure);
        Thread.UncaughtExceptionHandler previous = previousHandler;
        if (previous != null) {
            previous.uncaughtException(thread, failure);
            return;
        }
        // Taking over the default handler means the thread group's own is not reached any more, and
        // that one is what ends a process for a failure on its main thread. Handing the failure back
        // to it keeps the run's fate what the platform made it.
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
                // A screen that cannot take a line does not stop the run it was only describing.
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
     * Writes one line to the stream it has to appear on, which is the standard output or error stream
     * as it was before the tee replaced it.
     *
     * <p>Writing to the stream as it is now would write into the tee, which would log the line again
     * and echo it again, without end.</p>
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
                // Not installed yet, so the streams are still their own; neither leads back here.
                stream = toErr ? System.err : System.out;
            }
            stream.println(text);
            stream.flush();
        } catch (Throwable ignored) {
            // A stream that will not take the line does not stop the run that printed it.
        } finally {
            guard[0] = false;
        }
    }

    /** Writes the whole of a failure's stack to the standard error stream. */
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
            // Nothing to do: the stack has already reached logcat through Log.
        } finally {
            guard[0] = false;
        }
    }

    /** Writes every byte through to the stream it replaced, and whole lines into this log. */
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
