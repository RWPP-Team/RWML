/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import org.junit.jupiter.api.Test;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class LobbyRequestsTest {
    @Test void closeDisconnectsSocketsInterruptsWorkersAndRefusesNewRequests() throws Exception {
        RequestScope scope = new RequestScope();
        AtomicBoolean disconnected = new AtomicBoolean();
        HttpURLConnection connection = new HttpURLConnection(new URL("http://localhost/")) {
            public void connect() { }
            public boolean usingProxy() { return false; }
            public void disconnect() { disconnected.set(true); }
        };
        CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        assertTrue(scope.register(connection));
        scope.execute(() -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.countDown(); }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS)); scope.close();
        assertFalse(scope.isActive()); assertTrue(disconnected.get());
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertFalse(scope.register(connection));
        AtomicBoolean late = new AtomicBoolean(); scope.execute(() -> late.set(true));
        assertFalse(late.get()); scope.close();
    }
    @Test void leavingClosesARealResponseThatIsStillBeingRead() throws Exception {
        try (ServerSocket server = new ServerSocket(0); RequestScope scope = new RequestScope()) {
            CountDownLatch headers = new CountDownLatch(1), done = new CountDownLatch(1);
            AtomicBoolean disconnected = new AtomicBoolean();
            Thread peer = new Thread(() -> {
                try (Socket client = server.accept()) {
                    java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream()));
                    while (!in.readLine().isEmpty()) { }
                    client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\npartial\n".getBytes(StandardCharsets.UTF_8));
                    client.getOutputStream().flush(); headers.countDown();
                    disconnected.set(client.getInputStream().read() == -1);
                } catch (Exception ignored) { }
            });
            peer.setDaemon(true); peer.start();
            scope.execute(() -> {
                try { scope.get(new URL("http://127.0.0.1:" + server.getLocalPort()), "test", "en"); }
                catch (Exception expected) { }
                finally { done.countDown(); }
            });
            assertTrue(headers.await(2, TimeUnit.SECONDS));
            long start = System.nanoTime(); scope.close();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500);
            assertTrue(done.await(2, TimeUnit.SECONDS)); peer.join(2000);
            assertTrue(disconnected.get(), "Closing the lobby must close its live socket");
        }
    }
    @Test void parserPreservesColumnsAndSkipsMalformedRows() throws Exception {
        String[] row = new String[22]; java.util.Arrays.fill(row, "");
        row[0] = "fallback"; row[5] = "5123"; row[7] = "玩家"; row[17] = "true";
        String wire = "CORRODINGGAMES\r\nshort,row\n" + String.join(",", row) + "\n";
        assertEquals("fallback", MasterServerRows.parse(wire).get(0)[18]);
        assertEquals("玩家", MasterServerRows.parse(wire).get(0)[7]);
        row[5] = "invalid";
        assertTrue(MasterServerRows.parse("CORRODINGGAMES\n" + String.join(",", row)).isEmpty());
        assertThrows(java.io.IOException.class, () -> MasterServerRows.parse("CORRODINGGAMES [FAILED]\n"));
        assertThrows(java.io.IOException.class, () -> MasterServerRows.parse("html error"));
    }
}
