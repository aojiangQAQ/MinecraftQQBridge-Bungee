package h_aaa.mcqqbridge.service;

import com.google.gson.JsonObject;
import h_aaa.mcqqbridge.onebot.OneBotClient;
import h_aaa.mcqqbridge.onebot.OneBotStatus;

import java.util.concurrent.CompletableFuture;

public final class OneBotClientGateway implements OneBotGateway {
    private final OneBotClient client;

    public OneBotClientGateway(OneBotClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<JsonObject> sendGroupMessage(String groupId, String message) {
        return client.sendGroupMessage(groupId, message);
    }

    @Override
    public CompletableFuture<JsonObject> setGroupCard(
            String groupId, String userId, String card) {
        return client.setGroupCard(groupId, userId, card);
    }

    @Override
    public CompletableFuture<JsonObject> kickGroupMember(String groupId, String userId) {
        return client.kickGroupMember(groupId, userId);
    }

    @Override
    public String statusSummary() {
        OneBotStatus status = client.snapshot();
        if (!status.isRunning()) {
            return "STOPPED";
        }
        if (!status.isConnected()) {
            return status.getConnectionState().name();
        }
        return status.isHeartbeatHealthy() ? "CONNECTED/HEALTHY" : "CONNECTED/NO_HEARTBEAT";
    }
}
