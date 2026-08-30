package h_aaa.mcqqbridge.service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class DenyAllAccessService implements AccessControlService {
    @Override
    public CompletionStage<AccessDecision> checkAccess(String playerName, UUID playerUuid) {
        return CompletableFuture.completedFuture(
                AccessDecision.deny(AccessDecision.Type.DENY_STORAGE_ERROR));
    }
}
