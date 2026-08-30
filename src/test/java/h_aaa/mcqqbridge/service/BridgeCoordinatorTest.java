package h_aaa.mcqqbridge.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import h_aaa.mcqqbridge.config.PluginConfig;
import h_aaa.mcqqbridge.domain.Binding;
import h_aaa.mcqqbridge.domain.VerificationMode;
import h_aaa.mcqqbridge.testing.TestPluginConfig;
import h_aaa.mcqqbridge.util.NamedThreadFactory;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BridgeCoordinatorTest {
    @TempDir
    Path temporaryDirectory;

    private DatabaseService database;
    private ScheduledExecutorService scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (database != null) {
            database.close();
        }
    }

    @Test
    void bindsFromAllowedGroupAndPersistsOneToOneMapping() throws Exception {
        Fixture fixture = fixture(Collections.<String>emptySet(), VerificationMode.OFF);

        fixture.coordinator.onEvent(message("234567", "member", "绑定 Steve_01"),
                json(message("234567", "member", "绑定 Steve_01")));

        String reply = fixture.oneBot.messages.poll(2, TimeUnit.SECONDS);
        assertNotNull(reply);
        assertTrue(reply.contains("BOUND Steve_01"));
        Optional<Binding> binding = database.findByQq("234567").get(2, TimeUnit.SECONDS);
        assertTrue(binding.isPresent());
        assertTrue("steve_01".equals(binding.get().getNormalizedPlayerName()));
    }

    @Test
    void groupDecreaseUnbindsUserIdNotOperatorIdThenDisconnects() throws Exception {
        Fixture fixture = fixture(Collections.<String>emptySet(), VerificationMode.OFF);
        database.bind("Steve_01", "234567", "TEST", "TEST", "test", "")
                .get(2, TimeUnit.SECONDS);
        database.bind("Moderator", "345678", "TEST", "TEST", "test", "")
                .get(2, TimeUnit.SECONDS);
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        when(player.isConnected()).thenReturn(true);
        when(fixture.proxy.getPlayer("Steve_01")).thenReturn(player);

        String raw = "{\"post_type\":\"notice\",\"notice_type\":\"group_decrease\","
                + "\"sub_type\":\"kick\",\"group_id\":123456,\"self_id\":777777,"
                + "\"user_id\":234567,\"operator_id\":345678}";
        fixture.coordinator.onEvent(raw, json(raw));

        assertFalse(database.findByQq("234567").get(2, TimeUnit.SECONDS).isPresent());
        assertTrue(database.findByQq("345678").get(2, TimeUnit.SECONDS).isPresent());
        verify(player).disconnect("REVOKED");
    }

    @Test
    void groupOwnerCannotUseAdminCommandWithoutConfiguredQqId() throws Exception {
        Fixture fixture = fixture(Collections.singleton("999999"), VerificationMode.OFF);
        database.bind("Steve_01", "234567", "TEST", "TEST", "test", "")
                .get(2, TimeUnit.SECONDS);
        String raw = message("345678", "owner", "删除QQ 234567");

        fixture.coordinator.onEvent(raw, json(raw));

        assertTrue(fixture.oneBot.messages.poll(2, TimeUnit.SECONDS).contains("NO_PERMISSION"));
        assertTrue(database.findByQq("234567").get(2, TimeUnit.SECONDS).isPresent());
    }

    @Test
    void persistsAndConsumesRandomJoinVerification() throws Exception {
        Fixture fixture = fixture(Collections.<String>emptySet(), VerificationMode.RANDOM);
        String increase = "{\"post_type\":\"notice\",\"notice_type\":\"group_increase\","
                + "\"sub_type\":\"approve\",\"group_id\":123456,\"self_id\":777777,"
                + "\"user_id\":234567}";
        fixture.coordinator.onEvent(increase, json(increase));

        String prompt = fixture.oneBot.messages.poll(2, TimeUnit.SECONDS);
        assertNotNull(prompt);
        int codeIndex = prompt.indexOf("VERIFY ");
        assertTrue(codeIndex >= 0);
        String code = prompt.substring(codeIndex + "VERIFY ".length()).trim();
        String verifyMessage = message("234567", "member", "验证 " + code);
        fixture.coordinator.onEvent(verifyMessage, json(verifyMessage));

        assertTrue(fixture.oneBot.messages.poll(2, TimeUnit.SECONDS).contains("VERIFIED"));
        assertFalse(database.findPendingVerification("123456", "234567")
                .get(2, TimeUnit.SECONDS).isPresent());
    }

    private Fixture fixture(java.util.Set<String> administrators,
                            VerificationMode verificationMode) throws Exception {
        PluginConfig config = TestPluginConfig.create(
                temporaryDirectory, administrators, verificationMode);
        database = new DatabaseService(config.getDatabase(), config.getMigration(),
                Logger.getLogger("BridgeCoordinatorTest"));
        database.start();
        scheduler = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("bridge-test-"));
        ProxyServer proxy = mock(ProxyServer.class);
        FakeOneBot oneBot = new FakeOneBot();
        BridgeCoordinator coordinator = new BridgeCoordinator(
                config, database, oneBot, proxy, scheduler,
                Logger.getLogger("BridgeCoordinatorTest"));
        return new Fixture(proxy, oneBot, coordinator);
    }

    private static String message(String userId, String role, String text) {
        return "{\"post_type\":\"message\",\"message_type\":\"group\","
                + "\"group_id\":123456,\"user_id\":" + userId + ","
                + "\"raw_message\":\"" + text + "\","
                + "\"sender\":{\"role\":\"" + role + "\"}}";
    }

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private static final class Fixture {
        private final ProxyServer proxy;
        private final FakeOneBot oneBot;
        private final BridgeCoordinator coordinator;

        private Fixture(ProxyServer proxy, FakeOneBot oneBot,
                        BridgeCoordinator coordinator) {
            this.proxy = proxy;
            this.oneBot = oneBot;
            this.coordinator = coordinator;
        }
    }

    private static final class FakeOneBot implements OneBotGateway {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<String>();

        @Override
        public CompletableFuture<JsonObject> sendGroupMessage(String groupId, String message) {
            messages.add(message);
            return CompletableFuture.completedFuture(new JsonObject());
        }

        @Override
        public CompletableFuture<JsonObject> setGroupCard(
                String groupId, String userId, String card) {
            return CompletableFuture.completedFuture(new JsonObject());
        }

        @Override
        public CompletableFuture<JsonObject> kickGroupMember(String groupId, String userId) {
            return CompletableFuture.completedFuture(new JsonObject());
        }

        @Override
        public String statusSummary() {
            return "CONNECTED/HEALTHY";
        }
    }
}
