package h_aaa.mcqqbridge.bungee;

import h_aaa.mcqqbridge.service.AccessControlService;
import h_aaa.mcqqbridge.service.AccessDecision;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProxyLoginListenerTest {
    private ScheduledExecutorService scheduler;
    private Plugin plugin;
    private LoginEvent event;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        plugin = mock(Plugin.class);
        event = mock(LoginEvent.class);
        PendingConnection connection = mock(PendingConnection.class);
        when(connection.getName()).thenReturn("Steve_01");
        when(connection.getUniqueId()).thenReturn(UUID.randomUUID());
        when(event.getConnection()).thenReturn(connection);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void asynchronouslyAllowsBoundPlayer() {
        AccessControlService service = (name, uuid) -> CompletableFuture.completedFuture(
                AccessDecision.allowBound());
        listener(service, 500).onLogin(event);

        verify(event).registerIntent(plugin);
        verify(event, timeout(500)).completeIntent(plugin);
        verify(event, never()).setCancelled(true);
    }

    @Test
    void deniesUnboundPlayer() {
        AccessControlService service = (name, uuid) -> CompletableFuture.completedFuture(
                AccessDecision.deny(AccessDecision.Type.DENY_UNBOUND));
        listener(service, 500).onLogin(event);

        verify(event, timeout(500)).setCancelReason("UNBOUND");
        verify(event).setCancelled(true);
        verify(event).completeIntent(plugin);
    }

    @Test
    void timeoutFailsClosedAndCompletesIntentOnce() {
        AccessControlService service = (name, uuid) -> new CompletableFuture<AccessDecision>();
        listener(service, 25).onLogin(event);

        verify(event, timeout(1000)).setCancelReason("STORAGE_ERROR");
        verify(event).setCancelled(true);
        verify(event, times(1)).completeIntent(plugin);
    }

    @Test
    void closingListenerFailsClosedAndCompletesOutstandingIntentOnce() {
        CompletableFuture<AccessDecision> pending = new CompletableFuture<AccessDecision>();
        AccessControlService service = (name, uuid) -> pending;
        ProxyLoginListener listener = listener(service, 10_000);
        listener.onLogin(event);

        listener.close();

        verify(event).setCancelReason("STORAGE_ERROR");
        verify(event).setCancelled(true);
        verify(event, times(1)).completeIntent(plugin);

        pending.complete(AccessDecision.allowBound());
        verify(event, times(1)).completeIntent(plugin);
    }

    private ProxyLoginListener listener(AccessControlService service, int timeoutMillis) {
        return new ProxyLoginListener(
                plugin, service, scheduler, timeoutMillis, "UNBOUND", "STORAGE_ERROR",
                Logger.getLogger("ProxyLoginListenerTest"));
    }
}
