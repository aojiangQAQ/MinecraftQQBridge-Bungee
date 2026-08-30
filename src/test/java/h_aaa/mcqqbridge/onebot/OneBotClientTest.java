package h_aaa.mcqqbridge.onebot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import h_aaa.mcqqbridge.config.PluginConfig;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class OneBotClientTest {
    private final CopyOnWriteArrayList<OneBotClient> clients =
            new CopyOnWriteArrayList<OneBotClient>();
    private final CopyOnWriteArrayList<MockOneBotServer> servers =
            new CopyOnWriteArrayList<MockOneBotServer>();

    @AfterEach
    void tearDown() throws InterruptedException {
        for (OneBotClient client : clients) {
            client.stop();
        }
        for (MockOneBotServer server : servers) {
            server.stop(1000);
        }
    }

    @Test
    void buildsExactBearerHeader() {
        Map<String, String> headers = OneBotClient.createHandshakeHeaders("test-token");
        assertEquals(1, headers.size());
        assertEquals("Bearer test-token", headers.get("Authorization"));
        assertTrue(OneBotClient.createHandshakeHeaders("  ").isEmpty());
    }

    @Test
    void correlatesEchoAndCleansUpTimedOutRequest() throws Exception {
        MockOneBotServer server = startServer(0);
        server.ignoredActions.add("never_reply");
        OneBotClient client = startClient(server.getPort(), 150, 1500, (raw, event) -> { });

        JsonObject response = client.sendGroupMessage("123456", "hello").get(2, TimeUnit.SECONDS);
        assertEquals("ok", response.get("status").getAsString());
        assertNotNull(response.get("echo"));
        assertEquals("Bearer test-token", server.authorization.get());

        JsonObject sent = server.requests.poll(1, TimeUnit.SECONDS);
        assertNotNull(sent);
        assertEquals("send_group_msg", sent.get("action").getAsString());
        assertTrue(sent.getAsJsonObject("params").get("group_id").getAsJsonPrimitive().isNumber());

        ExecutionException timeout = assertThrows(ExecutionException.class,
                () -> client.callAction("never_reply", new JsonObject()).get(2, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, timeout.getCause());
        await(() -> client.snapshot().getPendingRequests() == 0, 1000);

        server.failedStatusActions.add("status_denied");
        ExecutionException statusDenied = assertThrows(ExecutionException.class,
                () -> client.callAction("status_denied", new JsonObject())
                        .get(2, TimeUnit.SECONDS));
        OneBotActionException statusFailure = assertInstanceOf(
                OneBotActionException.class, statusDenied.getCause());
        assertEquals("status_denied", statusFailure.getAction());
        assertEquals("failed", statusFailure.getStatus());
        assertEquals(0, statusFailure.getRetcode());
        assertFalse(statusFailure.getMessage().contains("test-token"));

        server.nonZeroRetcodeActions.add("retcode_denied");
        ExecutionException retcodeDenied = assertThrows(ExecutionException.class,
                () -> client.callAction("retcode_denied", new JsonObject())
                        .get(2, TimeUnit.SECONDS));
        OneBotActionException retcodeFailure = assertInstanceOf(
                OneBotActionException.class, retcodeDenied.getCause());
        assertEquals("retcode_denied", retcodeFailure.getAction());
        assertEquals("ok", retcodeFailure.getStatus());
        assertEquals(1400, retcodeFailure.getRetcode());
        assertFalse(retcodeFailure.getMessage().contains("test-token"));
    }

    @Test
    void recordsHeartbeatAndForwardsRawAndStructuredEvent() throws Exception {
        MockOneBotServer server = startServer(0);
        BlockingQueue<String> rawEvents = new LinkedBlockingQueue<String>();
        BlockingQueue<JsonObject> parsedEvents = new LinkedBlockingQueue<JsonObject>();
        OneBotClient client = startClient(server.getPort(), 500, 1200, (raw, event) -> {
            rawEvents.add(raw);
            parsedEvents.add(event);
        });

        String heartbeat = "{\"time\":1,\"self_id\":2,\"post_type\":\"meta_event\","
                + "\"meta_event_type\":\"heartbeat\",\"interval\":5000,"
                + "\"status\":{\"online\":true,\"good\":true}}";
        server.sendToCurrent(heartbeat);

        assertEquals(heartbeat, rawEvents.poll(1, TimeUnit.SECONDS));
        JsonObject parsed = parsedEvents.poll(1, TimeUnit.SECONDS);
        assertNotNull(parsed);
        assertEquals("heartbeat", parsed.get("meta_event_type").getAsString());
        await(() -> client.snapshot().getLastHeartbeatMillis() > 0L, 1000);
        OneBotStatus status = client.snapshot();
        assertTrue(status.isHeartbeatHealthy());
        assertTrue(status.getLastEventMillis() >= status.getLastHeartbeatMillis());
    }

    @Test
    void reconnectsWithBackoffAndDoesNotReconnectAfterStop() throws Exception {
        int port = reserveUnusedPort();
        OneBotClient client = newClient(port, 250, 2000, (raw, event) -> { }, 60, 120);
        client.start();
        clients.add(client);

        await(() -> client.snapshot().getConnectionState() == OneBotConnectionState.RECONNECT_WAIT
                && client.snapshot().getConsecutiveFailures() > 0, 3000);

        MockOneBotServer server = startServer(port);
        await(client::isConnected, 3000);
        assertEquals(0, client.snapshot().getConsecutiveFailures());
        int firstConnections = server.connectionCount.get();

        server.closeCurrent();
        await(() -> server.connectionCount.get() > firstConnections && client.isConnected(), 3000);

        Set<Long> clientIoThreads = webSocketClientThreadIds();
        assertFalse(clientIoThreads.isEmpty(), "expected Java-WebSocket client I/O threads");
        client.stop();
        await(() -> clientIoThreads.stream().noneMatch(OneBotClientTest::isThreadAlive), 3000);
        int stoppedConnections = server.connectionCount.get();
        Thread.sleep(350L);
        assertEquals(stoppedConnections, server.connectionCount.get());
        assertEquals(OneBotConnectionState.STOPPED, client.snapshot().getConnectionState());
        assertFalse(client.isRunning());
    }

    @Test
    void repeatedStartStopAndRestartAreIdempotent() throws Exception {
        MockOneBotServer server = startServer(0);
        OneBotClient client = newClient(server.getPort(), 500, 2000,
                (raw, event) -> { }, 50, 100);
        clients.add(client);

        client.start();
        client.start();
        await(client::isConnected, 3000);
        Thread.sleep(100L);
        assertEquals(1, server.connectionCount.get());

        client.stop();
        client.stop();
        assertEquals(OneBotConnectionState.STOPPED, client.snapshot().getConnectionState());

        client.start();
        client.start();
        await(() -> client.isConnected() && server.connectionCount.get() == 2, 3000);
        client.stop();
    }

    private MockOneBotServer startServer(int port) throws InterruptedException {
        MockOneBotServer server = new MockOneBotServer(port);
        servers.add(server);
        server.start();
        assertTrue(server.started.await(3, TimeUnit.SECONDS), "mock server did not start");
        return server;
    }

    private OneBotClient startClient(int port, int requestTimeoutMillis,
                                     int heartbeatTimeoutMillis, OneBotEventListener listener)
            throws InterruptedException {
        OneBotClient client = newClient(port, requestTimeoutMillis, heartbeatTimeoutMillis,
                listener, 50, 100);
        clients.add(client);
        client.start();
        await(client::isConnected, 3000);
        return client;
    }

    private static OneBotClient newClient(int port, int requestTimeoutMillis,
                                          int heartbeatTimeoutMillis,
                                          OneBotEventListener listener,
                                          long reconnectMinMillis, long reconnectMaxMillis) {
        PluginConfig.OneBot config = new PluginConfig.OneBot(
                true,
                "ws://127.0.0.1:" + port + '/',
                "test-token",
                500,
                requestTimeoutMillis,
                heartbeatTimeoutMillis,
                1,
                1);
        Logger logger = Logger.getLogger("onebot-test-" + port + '-' + System.nanoTime());
        logger.setLevel(Level.OFF);
        logger.setUseParentHandlers(false);
        return new OneBotClient(config, listener, logger, new Random(7L),
                reconnectMinMillis, reconnectMaxMillis);
    }

    private static int reserveUnusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static Set<Long> webSocketClientThreadIds() {
        Set<Long> ids = new HashSet<Long>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            String name = thread.getName();
            if (thread.isAlive() && (name.startsWith("WebSocketConnectReadThread-")
                    || name.startsWith("WebSocketWriteThread-"))) {
                ids.add(thread.getId());
            }
        }
        return ids;
    }

    private static boolean isThreadAlive(long threadId) {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getId() == threadId) {
                return thread.isAlive();
            }
        }
        return false;
    }

    private static void await(BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10L);
        }
        fail("condition was not met within " + timeoutMillis + " ms");
    }

    private static final class MockOneBotServer extends WebSocketServer {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicInteger connectionCount = new AtomicInteger();
        private final AtomicReference<String> authorization = new AtomicReference<String>();
        private final AtomicReference<WebSocket> current = new AtomicReference<WebSocket>();
        private final AtomicReference<Exception> error = new AtomicReference<Exception>();
        private final BlockingQueue<JsonObject> requests = new LinkedBlockingQueue<JsonObject>();
        private final Set<String> ignoredActions = ConcurrentHashMap.newKeySet();
        private final Set<String> failedStatusActions = ConcurrentHashMap.newKeySet();
        private final Set<String> nonZeroRetcodeActions = ConcurrentHashMap.newKeySet();

        private MockOneBotServer(int port) {
            super(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            setDaemon(true);
        }

        @Override
        public void onOpen(WebSocket connection, ClientHandshake handshake) {
            current.set(connection);
            authorization.set(handshake.getFieldValue("Authorization"));
            connectionCount.incrementAndGet();
        }

        @Override
        public void onClose(WebSocket connection, int code, String reason, boolean remote) {
            current.compareAndSet(connection, null);
        }

        @Override
        public void onMessage(WebSocket connection, String message) {
            JsonObject request = JsonParser.parseString(message).getAsJsonObject();
            requests.add(request);
            String action = request.get("action").getAsString();
            if (ignoredActions.contains(action)) {
                return;
            }
            JsonObject response = new JsonObject();
            boolean failedStatus = failedStatusActions.contains(action);
            boolean nonZeroRetcode = nonZeroRetcodeActions.contains(action);
            response.addProperty("status", failedStatus ? "failed" : "ok");
            response.addProperty("retcode", nonZeroRetcode ? 1400 : 0);
            if (failedStatus || nonZeroRetcode) {
                response.addProperty("wording", "denied test-token");
            }
            response.add("data", new JsonObject());
            JsonElement echo = request.get("echo");
            response.add("echo", echo == null ? null : echo.deepCopy());
            connection.send(response.toString());
        }

        @Override
        public void onError(WebSocket connection, Exception exception) {
            error.compareAndSet(null, exception);
        }

        @Override
        public void onStart() {
            started.countDown();
        }

        private void sendToCurrent(String message) {
            WebSocket connection = current.get();
            assertNotNull(connection, "mock server has no connected client");
            connection.send(message);
        }

        private void closeCurrent() {
            WebSocket connection = current.get();
            assertNotNull(connection, "mock server has no connected client");
            connection.close(1012, "test restart");
        }
    }
}
