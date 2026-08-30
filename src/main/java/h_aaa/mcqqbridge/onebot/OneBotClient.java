package h_aaa.mcqqbridge.onebot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import h_aaa.mcqqbridge.config.PluginConfig;
import h_aaa.mcqqbridge.util.NamedThreadFactory;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OneBotClient implements AutoCloseable {
    private static final BigInteger MAX_SIGNED_LONG = BigInteger.valueOf(Long.MAX_VALUE);
    private static final OneBotEventListener NOOP_LISTENER = (rawJson, event) -> { };

    private final PluginConfig.OneBot config;
    private final URI endpoint;
    private final OneBotEventListener eventListener;
    private final Logger logger;
    private final Object lifecycleLock = new Object();
    private final ConcurrentMap<String, PendingRequest> pendingRequests =
            new ConcurrentHashMap<String, PendingRequest>();
    private final AtomicLong echoSequence = new AtomicLong();
    private final String echoPrefix = Long.toHexString(System.nanoTime());
    private final ReconnectPolicy reconnectPolicy;

    private volatile boolean running;
    private volatile long generation;
    private volatile OneBotConnectionState connectionState = OneBotConnectionState.STOPPED;
    private volatile long connectedAtMillis;
    private volatile long lastEventMillis;
    private volatile long lastHeartbeatMillis;
    private volatile String lastDisconnectReason = "";

    private ScheduledThreadPoolExecutor scheduler;
    private ClientSocket socket;
    private ScheduledFuture<?> reconnectTask;
    private ScheduledFuture<?> connectTimeoutTask;
    private ScheduledFuture<?> heartbeatTask;

    public OneBotClient(PluginConfig.OneBot config, OneBotEventListener eventListener, Logger logger) {
        this(config, eventListener, logger, new Random(),
                TimeUnit.SECONDS.toMillis(config.getReconnectMinSeconds()),
                TimeUnit.SECONDS.toMillis(config.getReconnectMaxSeconds()));
    }

    public OneBotClient(PluginConfig.OneBot config, OneBotEventListener eventListener) {
        this(config, eventListener, Logger.getLogger(OneBotClient.class.getName()));
    }

    OneBotClient(PluginConfig.OneBot config, OneBotEventListener eventListener, Logger logger,
                 Random random, long reconnectMinMillis, long reconnectMaxMillis) {
        this.config = Objects.requireNonNull(config, "config");
        this.eventListener = eventListener == null ? NOOP_LISTENER : eventListener;
        this.logger = logger == null ? Logger.getLogger(OneBotClient.class.getName()) : logger;
        this.endpoint = parseEndpoint(config.getUrl());
        this.reconnectPolicy = new ReconnectPolicy(reconnectMinMillis, reconnectMaxMillis,
                Objects.requireNonNull(random, "random"));
    }

    public void start() {
        long expectedGeneration;
        ScheduledThreadPoolExecutor executor;
        synchronized (lifecycleLock) {
            if (running || !config.isEnabled()) {
                return;
            }
            running = true;
            expectedGeneration = ++generation;
            reconnectPolicy.reset();
            lastDisconnectReason = "";
            connectionState = OneBotConnectionState.CONNECTING;
            executor = new ScheduledThreadPoolExecutor(1,
                    new NamedThreadFactory("mcqq-onebot-scheduler-"));
            executor.setRemoveOnCancelPolicy(true);
            scheduler = executor;
        }
        executor.execute(() -> openConnection(expectedGeneration));
    }

    public void stop() {
        ClientSocket socketToClose;
        ScheduledThreadPoolExecutor executorToStop;
        synchronized (lifecycleLock) {
            if (!running && scheduler == null) {
                connectionState = OneBotConnectionState.STOPPED;
                return;
            }
            running = false;
            generation++;
            connectionState = OneBotConnectionState.STOPPED;
            cancelTask(reconnectTask);
            cancelTask(connectTimeoutTask);
            cancelTask(heartbeatTask);
            reconnectTask = null;
            connectTimeoutTask = null;
            heartbeatTask = null;
            socketToClose = socket;
            socket = null;
            executorToStop = scheduler;
            scheduler = null;
        }

        failPendingRequests(new IllegalStateException("OneBot client stopped"));
        if (socketToClose != null) {
            try {
                socketToClose.close(1000, "client stopped");
                socketToClose.closeConnection(1000, "client stopped");
            } catch (RuntimeException e) {
                log(Level.FINE, "Failed to close OneBot WebSocket", e);
            }
        }
        if (executorToStop != null) {
            executorToStop.shutdownNow();
            try {
                executorToStop.awaitTermination(1L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isConnected() {
        return connectionState == OneBotConnectionState.CONNECTED;
    }

    public OneBotStatus snapshot() {
        long heartbeat = lastHeartbeatMillis;
        boolean heartbeatHealthy = isConnected()
                && heartbeat > 0L
                && System.currentTimeMillis() - heartbeat <= config.getHeartbeatTimeoutMillis();
        return new OneBotStatus(running, connectionState, connectedAtMillis, lastEventMillis,
                heartbeat, heartbeatHealthy, reconnectPolicy.getFailures(),
                pendingRequests.size(), lastDisconnectReason);
    }

    public CompletableFuture<JsonObject> callAction(String action, JsonObject params) {
        if (action == null || action.trim().isEmpty()) {
            throw new IllegalArgumentException("action must not be blank");
        }

        ClientSocket activeSocket;
        ScheduledThreadPoolExecutor activeScheduler;
        synchronized (lifecycleLock) {
            activeSocket = socket;
            activeScheduler = scheduler;
            if (!running || connectionState != OneBotConnectionState.CONNECTED
                    || activeSocket == null || !activeSocket.isOpen()
                    || activeScheduler == null || activeScheduler.isShutdown()) {
                return failedFuture(new IllegalStateException("OneBot WebSocket is not connected"));
            }
        }

        String echo = echoPrefix + '-' + echoSequence.incrementAndGet();
        JsonObject request = new JsonObject();
        request.addProperty("action", action);
        request.add("params", params == null ? new JsonObject() : params.deepCopy());
        request.addProperty("echo", echo);

        CompletableFuture<JsonObject> future = new CompletableFuture<JsonObject>();
        PendingRequest pending = new PendingRequest(action, future);
        pendingRequests.put(echo, pending);
        try {
            pending.timeoutTask = activeScheduler.schedule(
                    () -> timeoutRequest(echo, pending),
                    config.getRequestTimeoutMillis(), TimeUnit.MILLISECONDS);
            activeSocket.send(request.toString());
        } catch (RuntimeException e) {
            if (pendingRequests.remove(echo, pending)) {
                pending.cancelTimeout();
                future.completeExceptionally(e);
            }
        }
        return future;
    }

    public CompletableFuture<JsonObject> sendGroupMessage(String groupId, String message) {
        JsonObject params = new JsonObject();
        params.add("group_id", numericId(groupId, "groupId"));
        params.addProperty("message", Objects.requireNonNull(message, "message"));
        return callAction("send_group_msg", params);
    }

    public CompletableFuture<JsonObject> setGroupCard(String groupId, String userId, String card) {
        JsonObject params = new JsonObject();
        params.add("group_id", numericId(groupId, "groupId"));
        params.add("user_id", numericId(userId, "userId"));
        params.addProperty("card", Objects.requireNonNull(card, "card"));
        return callAction("set_group_card", params);
    }

    public CompletableFuture<JsonObject> kickGroupMember(String groupId, String userId) {
        JsonObject params = new JsonObject();
        params.add("group_id", numericId(groupId, "groupId"));
        params.add("user_id", numericId(userId, "userId"));
        params.addProperty("reject_add_request", false);
        return callAction("set_group_kick", params);
    }

    public CompletableFuture<JsonObject> getRemoteStatus() {
        return callAction("get_status", new JsonObject());
    }

    static Map<String, String> createHandshakeHeaders(String token) {
        String normalized = token == null ? "" : token.trim();
        if (normalized.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + normalized);
        return Collections.unmodifiableMap(headers);
    }

    private void openConnection(long expectedGeneration) {
        ClientSocket newSocket;
        synchronized (lifecycleLock) {
            if (!isActive(expectedGeneration) || socket != null) {
                return;
            }
            reconnectTask = null;
            connectionState = OneBotConnectionState.CONNECTING;
            newSocket = new ClientSocket(endpoint, createHandshakeHeaders(config.getToken()),
                    expectedGeneration);
            socket = newSocket;
        }

        try {
            newSocket.connect();
            scheduleConnectTimeout(newSocket, expectedGeneration);
        } catch (RuntimeException e) {
            log(Level.WARNING, "Unable to start OneBot WebSocket connection", e);
            handleDisconnect(newSocket, expectedGeneration, "connect failed");
        }
    }

    private void scheduleConnectTimeout(ClientSocket expectedSocket, long expectedGeneration) {
        synchronized (lifecycleLock) {
            if (!isActive(expectedGeneration) || socket != expectedSocket || scheduler == null) {
                return;
            }
            cancelTask(connectTimeoutTask);
            connectTimeoutTask = scheduler.schedule(() -> {
                if (!expectedSocket.isOpen()) {
                    handleDisconnect(expectedSocket, expectedGeneration, "connect timeout");
                    safeClose(expectedSocket);
                }
            }, config.getConnectTimeoutMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void handleOpen(ClientSocket openedSocket, long expectedGeneration) {
        synchronized (lifecycleLock) {
            if (!isActive(expectedGeneration) || socket != openedSocket) {
                safeClose(openedSocket);
                return;
            }
            cancelTask(connectTimeoutTask);
            connectTimeoutTask = null;
            connectionState = OneBotConnectionState.CONNECTED;
            connectedAtMillis = System.currentTimeMillis();
            lastEventMillis = connectedAtMillis;
            lastHeartbeatMillis = 0L;
            lastDisconnectReason = "";
            reconnectPolicy.reset();
            scheduleHeartbeatWatchdog(openedSocket, expectedGeneration);
        }
        logger.info("OneBot WebSocket connected to " + safeEndpoint());
    }

    private void handleMessage(ClientSocket source, long expectedGeneration, String rawJson) {
        if (!isCurrent(source, expectedGeneration)) {
            return;
        }
        long receivedAtMillis = System.currentTimeMillis();
        lastEventMillis = receivedAtMillis;

        JsonObject message;
        try {
            JsonElement parsed = JsonParser.parseString(rawJson);
            if (!parsed.isJsonObject()) {
                logger.warning("Ignoring non-object OneBot WebSocket message");
                return;
            }
            message = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            log(Level.WARNING, "Ignoring malformed OneBot WebSocket JSON", e);
            return;
        }

        JsonElement echoElement = message.get("echo");
        if (echoElement != null && !echoElement.isJsonNull()) {
            if (!echoElement.isJsonPrimitive()) {
                logger.warning("Ignoring OneBot response with non-primitive echo");
                return;
            }
            PendingRequest pending = pendingRequests.remove(echoElement.getAsString());
            if (pending != null) {
                pending.cancelTimeout();
                OneBotActionException failure = actionFailure(pending.action, message);
                if (failure == null) {
                    pending.future.complete(message);
                } else {
                    pending.future.completeExceptionally(failure);
                }
            }
            return;
        }

        if (isHeartbeat(message)) {
            lastHeartbeatMillis = receivedAtMillis;
        }
        try {
            eventListener.onEvent(rawJson, message);
        } catch (RuntimeException e) {
            log(Level.WARNING, "OneBot event listener failed", e);
        }
    }

    private void handleError(ClientSocket source, long expectedGeneration, Exception error) {
        if (!isCurrent(source, expectedGeneration)) {
            return;
        }
        log(Level.WARNING, "OneBot WebSocket error", error);
        if (!source.isOpen()) {
            handleDisconnect(source, expectedGeneration, "websocket error");
        }
    }

    private void handleDisconnect(ClientSocket disconnectedSocket, long expectedGeneration,
                                  String reason) {
        boolean reconnect;
        synchronized (lifecycleLock) {
            if (socket != disconnectedSocket) {
                return;
            }
            socket = null;
            cancelTask(connectTimeoutTask);
            cancelTask(heartbeatTask);
            connectTimeoutTask = null;
            heartbeatTask = null;
            lastDisconnectReason = sanitize(reason);
            reconnect = isActive(expectedGeneration);
            connectionState = reconnect
                    ? OneBotConnectionState.RECONNECT_WAIT
                    : OneBotConnectionState.STOPPED;
        }
        failPendingRequests(new IllegalStateException("OneBot WebSocket disconnected"));
        if (reconnect) {
            scheduleReconnect(expectedGeneration);
        }
    }

    private void scheduleReconnect(long expectedGeneration) {
        long delay;
        synchronized (lifecycleLock) {
            if (!isActive(expectedGeneration) || scheduler == null
                    || (reconnectTask != null && !reconnectTask.isDone())) {
                return;
            }
            delay = reconnectPolicy.nextDelayMillis();
            connectionState = OneBotConnectionState.RECONNECT_WAIT;
            try {
                reconnectTask = scheduler.schedule(
                        () -> openConnection(expectedGeneration), delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                return;
            }
        }
        logger.info("OneBot WebSocket reconnect scheduled in " + delay + " ms");
    }

    private void scheduleHeartbeatWatchdog(ClientSocket expectedSocket, long expectedGeneration) {
        if (scheduler == null) {
            return;
        }
        long timeout = config.getHeartbeatTimeoutMillis();
        long interval = Math.max(50L, Math.min(1000L, timeout / 3L));
        heartbeatTask = scheduler.scheduleWithFixedDelay(() -> {
            if (!isCurrent(expectedSocket, expectedGeneration)) {
                return;
            }
            long heartbeat = lastHeartbeatMillis;
            long reference = heartbeat > 0L ? heartbeat : connectedAtMillis;
            if (reference > 0L && System.currentTimeMillis() - reference > timeout) {
                logger.warning("OneBot heartbeat timed out; reconnecting");
                handleDisconnect(expectedSocket, expectedGeneration, "heartbeat timeout");
                safeClose(expectedSocket);
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void timeoutRequest(String echo, PendingRequest expected) {
        if (pendingRequests.remove(echo, expected)) {
            expected.future.completeExceptionally(
                    new TimeoutException("OneBot action timed out for echo " + echo));
        }
    }

    private void failPendingRequests(Throwable cause) {
        for (Map.Entry<String, PendingRequest> entry : pendingRequests.entrySet()) {
            PendingRequest pending = entry.getValue();
            if (pendingRequests.remove(entry.getKey(), pending)) {
                pending.cancelTimeout();
                pending.future.completeExceptionally(cause);
            }
        }
    }

    private boolean isCurrent(ClientSocket expectedSocket, long expectedGeneration) {
        synchronized (lifecycleLock) {
            return isActive(expectedGeneration) && socket == expectedSocket;
        }
    }

    private boolean isActive(long expectedGeneration) {
        return running && generation == expectedGeneration;
    }

    private static boolean isHeartbeat(JsonObject message) {
        return "meta_event".equals(stringValue(message, "post_type"))
                && "heartbeat".equals(stringValue(message, "meta_event_type"));
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return "";
        }
        try {
            return value.getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private OneBotActionException actionFailure(String action, JsonObject response) {
        String status = stringValue(response, "status");
        int retcode = intValue(response.get("retcode"));
        if (!"failed".equalsIgnoreCase(status) && retcode == 0) {
            return null;
        }
        String detail = firstNonBlank(
                stringValue(response, "wording"),
                stringValue(response, "message"),
                stringValue(response, "msg"));
        return new OneBotActionException(action, sanitize(status), retcode, sanitize(detail));
    }

    private static int intValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return 0;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static JsonPrimitive numericId(String raw, String fieldName) {
        if (raw == null || !raw.matches("[0-9]+")) {
            throw new IllegalArgumentException(fieldName + " must contain digits only");
        }
        BigInteger value = new BigInteger(raw);
        if (value.compareTo(MAX_SIGNED_LONG) > 0) {
            throw new IllegalArgumentException(fieldName + " exceeds OneBot int64 range");
        }
        return new JsonPrimitive(value);
    }

    private static URI parseEndpoint(String value) {
        try {
            URI uri = new URI(Objects.requireNonNull(value, "url"));
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null
                    || !("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("OneBot URL must use ws or wss");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid OneBot WebSocket URL", e);
        }
    }

    private String safeEndpoint() {
        StringBuilder value = new StringBuilder(endpoint.getScheme())
                .append("://").append(endpoint.getHost());
        if (endpoint.getPort() >= 0) {
            value.append(':').append(endpoint.getPort());
        }
        String path = endpoint.getPath();
        if (path != null && !path.isEmpty()) {
            value.append(path);
        }
        return value.toString();
    }

    private String sanitize(String value) {
        String result = value == null ? "" : value;
        String token = config.getToken();
        if (token != null && !token.isEmpty()) {
            result = result.replace(token, "<redacted>");
        }
        return result;
    }

    private void log(Level level, String message, Throwable error) {
        String detail = error == null ? "" : sanitize(error.getMessage());
        logger.log(level, detail.isEmpty() ? message : message + ": " + detail);
    }

    private static void cancelTask(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    private static void safeClose(ClientSocket client) {
        try {
            client.close(1000, "reconnect");
            client.closeConnection(1000, "reconnect");
        } catch (RuntimeException ignored) {
            // Closing is best-effort; lifecycle generation guards stale callbacks.
        }
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable cause) {
        CompletableFuture<T> result = new CompletableFuture<T>();
        result.completeExceptionally(cause);
        return result;
    }

    private final class ClientSocket extends WebSocketClient {
        private final long socketGeneration;

        private ClientSocket(URI serverUri, Map<String, String> headers, long socketGeneration) {
            super(serverUri, headers);
            this.socketGeneration = socketGeneration;
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            handleOpen(this, socketGeneration);
        }

        @Override
        public void onMessage(String message) {
            handleMessage(this, socketGeneration, message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            handleDisconnect(this, socketGeneration,
                    "closed (code=" + code + ", remote=" + remote + "): " + reason);
        }

        @Override
        public void onError(Exception error) {
            handleError(this, socketGeneration, error);
        }
    }

    private static final class PendingRequest {
        private final String action;
        private final CompletableFuture<JsonObject> future;
        private volatile ScheduledFuture<?> timeoutTask;

        private PendingRequest(String action, CompletableFuture<JsonObject> future) {
            this.action = action;
            this.future = future;
        }

        private void cancelTimeout() {
            cancelTask(timeoutTask);
        }
    }
}
