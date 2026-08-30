package h_aaa.mcqqbridge.onebot;

public final class OneBotStatus {
    private final boolean running;
    private final OneBotConnectionState connectionState;
    private final long connectedAtMillis;
    private final long lastEventMillis;
    private final long lastHeartbeatMillis;
    private final boolean heartbeatHealthy;
    private final int consecutiveFailures;
    private final int pendingRequests;
    private final String lastDisconnectReason;

    OneBotStatus(boolean running, OneBotConnectionState connectionState,
                 long connectedAtMillis, long lastEventMillis, long lastHeartbeatMillis,
                 boolean heartbeatHealthy, int consecutiveFailures, int pendingRequests,
                 String lastDisconnectReason) {
        this.running = running;
        this.connectionState = connectionState;
        this.connectedAtMillis = connectedAtMillis;
        this.lastEventMillis = lastEventMillis;
        this.lastHeartbeatMillis = lastHeartbeatMillis;
        this.heartbeatHealthy = heartbeatHealthy;
        this.consecutiveFailures = consecutiveFailures;
        this.pendingRequests = pendingRequests;
        this.lastDisconnectReason = lastDisconnectReason;
    }

    public boolean isRunning() {
        return running;
    }

    public OneBotConnectionState getConnectionState() {
        return connectionState;
    }

    public boolean isConnected() {
        return connectionState == OneBotConnectionState.CONNECTED;
    }

    public long getConnectedAtMillis() {
        return connectedAtMillis;
    }

    public long getLastEventMillis() {
        return lastEventMillis;
    }

    public long getLastHeartbeatMillis() {
        return lastHeartbeatMillis;
    }

    public boolean isHeartbeatHealthy() {
        return heartbeatHealthy;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public int getPendingRequests() {
        return pendingRequests;
    }

    public String getLastDisconnectReason() {
        return lastDisconnectReason;
    }
}
