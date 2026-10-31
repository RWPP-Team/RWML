package io.github.rwml.desktop;

import org.junit.jupiter.api.Test;

import java.awt.EventQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DesktopUiSurfaceTest {
    @Test
    void closeIsIdempotentAndNotifiesOnTheUiThreadIncludingLateSubscribers() throws Exception {
        var surface = new DesktopUiSurface();
        var notifications = new AtomicInteger();
        var removedNotifications = new AtomicInteger();
        var lateNotifications = new AtomicInteger();
        surface.onClose(() -> {
            assertTrue(EventQueue.isDispatchThread());
            notifications.incrementAndGet();
        });
        surface.onClose(removedNotifications::incrementAndGet).close();

        surface.close();
        surface.close();
        EventQueue.invokeAndWait(() -> { });
        assertTrue(surface.isClosed());
        assertEquals(1, notifications.get());
        assertEquals(0, removedNotifications.get());

        surface.onClose(() -> {
            assertTrue(EventQueue.isDispatchThread());
            lateNotifications.incrementAndGet();
        });
        EventQueue.invokeAndWait(() -> { });
        assertEquals(1, lateNotifications.get());
    }

    @Test
    void executorRunsOnAwtThreadAndRejectsForeignNativeContent() throws Exception {
        var surface = new DesktopUiSurface();
        var observed = new AtomicInteger();
        surface.uiExecutor().execute(() -> observed.set(EventQueue.isDispatchThread() ? 1 : -1));
        EventQueue.invokeAndWait(() -> { });
        assertEquals(1, observed.get());
        assertThrows(IllegalArgumentException.class, () -> surface.attach("not an AWT window"));
        surface.close();
        EventQueue.invokeAndWait(() -> { });
    }
}
