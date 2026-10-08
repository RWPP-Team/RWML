/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns both workers and sockets; closing a page cancels the actual network I/O. */
public final class RequestScope implements AutoCloseable {
    private volatile boolean closed;
    private final Set<HttpURLConnection> connections = new HashSet<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "rwml-lobby-list"); thread.setDaemon(true); return thread;
    });
    public boolean isActive() { return !closed; }
    public synchronized void execute(Runnable action) {
        if (!closed) { workers.submit(action); }
    }
    public synchronized boolean register(HttpURLConnection connection) {
        if (closed) { connection.disconnect(); return false; }
        connections.add(connection); return true;
    }
    public String get(URL url, String userAgent, String language) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Language", language);
        if (!register(connection)) { throw new IOException("Lobby request canceled"); }
        try {
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (closed) { throw new IOException("Lobby request canceled"); }
                    response.append(line).append('\n');
                }
            }
            return response.toString();
        } finally {
            synchronized (this) { connections.remove(connection); }
            connection.disconnect();
        }
    }
    @Override public void close() {
        Set<HttpURLConnection> active;
        synchronized (this) {
            if (closed) { return; }
            closed = true;
            workers.shutdownNow();
            active = new HashSet<>(connections); connections.clear();
        }
        for (HttpURLConnection connection : active) { connection.disconnect(); }
    }
}
