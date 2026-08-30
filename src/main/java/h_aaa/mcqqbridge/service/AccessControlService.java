package h_aaa.mcqqbridge.service;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface AccessControlService {
    CompletionStage<AccessDecision> checkAccess(String playerName, UUID playerUuid);
}
