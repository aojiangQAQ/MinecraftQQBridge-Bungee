package h_aaa.mcqqbridge.service;

import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

public interface OneBotGateway {
    CompletableFuture<JsonObject> sendGroupMessage(String groupId, String message);

    CompletableFuture<JsonObject> setGroupCard(String groupId, String userId, String card);

    CompletableFuture<JsonObject> kickGroupMember(String groupId, String userId);

    String statusSummary();
}
