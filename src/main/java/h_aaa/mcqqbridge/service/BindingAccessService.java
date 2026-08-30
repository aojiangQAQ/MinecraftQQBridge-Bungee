package h_aaa.mcqqbridge.service;

import h_aaa.mcqqbridge.domain.MinecraftName;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class BindingAccessService implements AccessControlService {
    private final DatabaseService database;

    public BindingAccessService(DatabaseService database) {
        this.database = database;
    }

    @Override
    public CompletionStage<AccessDecision> checkAccess(String playerName, UUID playerUuid) {
        try {
            MinecraftName.parse(playerName);
        } catch (IllegalArgumentException error) {
            return CompletableFuture.completedFuture(
                    AccessDecision.deny(AccessDecision.Type.DENY_INVALID_NAME));
        }
        return database.findByPlayer(playerName).thenApply(binding -> binding.isPresent()
                ? AccessDecision.allowBound()
                : AccessDecision.deny(AccessDecision.Type.DENY_UNBOUND));
    }
}
