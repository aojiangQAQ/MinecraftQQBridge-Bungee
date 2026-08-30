package h_aaa.mcqqbridge.storage;

import h_aaa.mcqqbridge.domain.BindResult;
import h_aaa.mcqqbridge.domain.Binding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteStoreTest {
    @TempDir
    Path tempDirectory;

    @Test
    void enforcesBothUniqueKeysAndQueriesPlayerNamesCaseInsensitively() throws Exception {
        try (SqliteStore store = initialized("bindings.db")) {
            BindResult created = store.bind("Steve_One", "10001", "TEST", 1000L);
            assertEquals(BindResult.Status.CREATED, created.getStatus());
            assertThrows(SQLException.class, () -> executeUpdate(store.getDatabaseFile(),
                    "UPDATE binding_audit SET reason='tampered'"));
            assertThrows(SQLException.class, () -> executeUpdate(store.getDatabaseFile(),
                    "DELETE FROM binding_audit"));

            Binding byPlayer = store.findByPlayer("sTEvE_oNE").get();
            assertEquals("Steve_One", byPlayer.getPlayerName());
            assertEquals("10001", store.findByQq("10001").get().getQqUserId());

            assertEquals(BindResult.Status.SAME_BINDING,
                    store.bind("STEVE_ONE", "10001", "TEST", 1001L).getStatus());
            assertEquals(BindResult.Status.QQ_ALREADY_BOUND,
                    store.bind("Alex_One", "10001", "TEST", 1002L).getStatus());
            assertEquals(BindResult.Status.PLAYER_ALREADY_BOUND,
                    store.bind("STEVE_ONE", "10002", "TEST", 1003L).getStatus());
            assertEquals(1, store.listBindings().size());

            UnbindResult removed = store.unbindByPlayer("steve_one", "ADMIN", "operator",
                    "test", 1100L);
            assertEquals(UnbindResult.Status.REMOVED, removed.getStatus());
            assertFalse(store.findByPlayer("Steve_One").isPresent());
            assertEquals(UnbindResult.Status.NOT_FOUND,
                    store.unbindByQq("10001", "ADMIN", "operator", "test", 1101L)
                            .getStatus());
        }
    }

    @Test
    void externalUnbindIsIdempotentAndCancelsOnlyTheMatchingPendingChallenge()
            throws Exception {
        Path database = tempDirectory.resolve("external.db");
        try (SqliteStore store = initialized(database)) {
            store.bind("LeaveUser", "20001", "TEST", 1000L);
            store.createOrReplaceVerification("30001", "20001", "123456",
                    1000L, 5000L, 3);

            UnbindResult first = store.processExternalUnbind("notice-1", "group_decrease",
                    "30001", "20001", "bot", "left group", 1500L);
            assertEquals(UnbindResult.Status.REMOVED, first.getStatus());
            assertFalse(store.findByQq("20001").isPresent());
            assertFalse(store.findPendingVerification("30001", "20001").isPresent());
            assertEquals("CANCELLED", queryString(database,
                    "SELECT state FROM verification_challenges LIMIT 1"));
            assertEquals("GROUP_DECREASE", queryString(database,
                    "SELECT state_reason FROM verification_challenges LIMIT 1"));
            assertEquals(1, queryInt(database,
                    "SELECT count(*) FROM binding_audit WHERE action='UNBIND_EXTERNAL'"));

            UnbindResult duplicate = store.processExternalUnbind("notice-1", "group_decrease",
                    "30001", "20001", "bot", "duplicate", 1600L);
            assertEquals(UnbindResult.Status.DUPLICATE_EVENT, duplicate.getStatus());
            assertNull(duplicate.getBinding());
            assertEquals(1, queryInt(database,
                    "SELECT count(*) FROM binding_audit WHERE action='UNBIND_EXTERNAL'"));

            store.createOrReplaceVerification("30001", "20001", "654321",
                    2000L, 6000L, 3);
            assertEquals(UnbindResult.Status.DUPLICATE_EVENT,
                    store.processExternalUnbind("notice-1", "group_decrease", "30001",
                            "20001", "bot", "duplicate", 2100L).getStatus());
            assertTrue(store.findPendingVerification("30001", "20001").isPresent());

            assertEquals(UnbindResult.Status.NOT_FOUND,
                    store.processExternalUnbind("notice-2", "group_decrease", "30001",
                            "20001", "bot", "left again", 2200L).getStatus());
            assertFalse(store.findPendingVerification("30001", "20001").isPresent());
            assertEquals(2, queryInt(database,
                    "SELECT count(*) FROM processed_external_events"));
        }
    }

    @Test
    void verificationStateMachineHandlesSuccessFailureReplacementExpiryAndCancellation()
            throws Exception {
        Path database = tempDirectory.resolve("verification.db");
        try (SqliteStore store = initialized(database)) {
            VerificationChallenge challenge = store.createOrReplaceVerification(
                    "40001", "50001", "123456", 1000L, 2000L, 2);
            VerificationAttemptResult wrong = store.verifyAttempt(
                    "40001", "50001", "000000", 1100L);
            assertEquals(VerificationAttemptResult.Status.INVALID_CODE, wrong.getStatus());
            assertEquals(1, wrong.getChallenge().getAttempts());

            VerificationAttemptResult verified = store.verifyAttempt(
                    "40001", "50001", "123456", 1200L);
            assertEquals(VerificationAttemptResult.Status.VERIFIED, verified.getStatus());
            assertEquals(VerificationChallenge.State.VERIFIED,
                    verified.getChallenge().getState());
            assertEquals(1, verified.getChallenge().getAttempts());
            assertFalse(store.findPendingVerification("40001", "50001").isPresent());
            assertEquals(VerificationAttemptResult.Status.NOT_FOUND,
                    store.verifyAttempt("40001", "50001", "123456", 1300L).getStatus());

            VerificationChallenge replaced = store.createOrReplaceVerification(
                    "40002", "50002", "first", 2000L, 4000L, 3);
            VerificationChallenge replacement = store.createOrReplaceVerification(
                    "40002", "50002", "second", 2100L, 4100L, 3);
            assertNotEquals(replaced.getChallengeId(), replacement.getChallengeId());
            assertEquals("CANCELLED", queryString(database,
                    "SELECT state FROM verification_challenges WHERE challenge_id=?",
                    replaced.getChallengeId()));
            assertEquals("REPLACED", queryString(database,
                    "SELECT state_reason FROM verification_challenges WHERE challenge_id=?",
                    replaced.getChallengeId()));

            store.createOrReplaceVerification("40003", "50003", "secret",
                    2200L, 5000L, 2);
            assertEquals(VerificationAttemptResult.Status.INVALID_CODE,
                    store.verifyAttempt("40003", "50003", "bad-1", 2300L).getStatus());
            VerificationAttemptResult exhausted = store.verifyAttempt(
                    "40003", "50003", "bad-2", 2400L);
            assertEquals(VerificationAttemptResult.Status.MAX_ATTEMPTS,
                    exhausted.getStatus());
            assertEquals(VerificationChallenge.State.EXHAUSTED,
                    exhausted.getChallenge().getState());
            assertEquals(0, exhausted.getChallenge().getRemainingAttempts());

            store.createOrReplaceVerification("40004", "50004", "late",
                    2500L, 3000L, 2);
            VerificationAttemptResult expired = store.verifyAttempt(
                    "40004", "50004", "late", 3000L);
            assertEquals(VerificationAttemptResult.Status.EXPIRED, expired.getStatus());
            assertEquals(VerificationChallenge.State.EXPIRED,
                    expired.getChallenge().getState());

            store.createOrReplaceVerification("40005", "50005", "cancel",
                    2600L, 5000L, 2);
            assertTrue(store.cancelVerification("40005", "50005", "manual", 2700L));
            assertFalse(store.cancelVerification("40005", "50005", "manual", 2800L));
            assertEquals("manual", queryString(database,
                    "SELECT state_reason FROM verification_challenges "
                            + "WHERE group_id='40005' AND qq_user_id='50005'"));
            assertNotNull(challenge.getChallengeId());
        }
    }

    @Test
    void pendingChallengesSurviveRestartAndBulkExpiryReturnsAffectedMembers()
            throws Exception {
        Path database = tempDirectory.resolve("restart.db");
        try (SqliteStore first = initialized(database)) {
            first.createOrReplaceVerification("60001", "70001", "one",
                    100L, 1000L, 3);
            first.createOrReplaceVerification("60002", "70002", "two",
                    100L, 1200L, 3);
            first.createOrReplaceVerification("60003", "70003", "three",
                    100L, 2000L, 3);
        }

        try (SqliteStore reopened = initialized(database)) {
            assertTrue(reopened.findPendingVerification("60003", "70003").isPresent());
            List<VerificationChallenge> expired = reopened.expireAndListVerifications(1500L);
            assertEquals(2, expired.size());
            assertEquals("60001", expired.get(0).getGroupId());
            assertEquals("70001", expired.get(0).getQqUserId());
            assertEquals(VerificationChallenge.State.EXPIRED, expired.get(0).getState());
            assertEquals("60002", expired.get(1).getGroupId());
            assertTrue(reopened.expireAndListVerifications(1500L).isEmpty());
            assertTrue(reopened.findPendingVerification("60003", "70003").isPresent());
            assertEquals(VerificationAttemptResult.Status.VERIFIED,
                    reopened.verifyAttempt("60003", "70003", "three", 1600L).getStatus());
        }
    }

    @Test
    void closedStoreRejectsOperations() throws Exception {
        SqliteStore store = initialized("closed.db");
        store.close();
        StorageException failure = assertThrows(StorageException.class, store::checkHealth);
        assertEquals(StorageException.Kind.CLOSED, failure.getKind());
    }

    @Test
    void transientBusyFailureDoesNotPoisonTheStore() throws Exception {
        Path database = tempDirectory.resolve("busy.db");
        SqliteStore store = new SqliteStore(database, 20);
        store.initialize();
        try (Connection blocker = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = blocker.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            StorageException failure = assertThrows(StorageException.class,
                    () -> store.bind("BusyUser", "80001", "TEST", 1000L));
            assertEquals(StorageException.Kind.BUSY, failure.getKind());
            assertTrue(store.getHealth().isHealthy());
            statement.execute("ROLLBACK");
        }

        assertEquals(BindResult.Status.CREATED,
                store.bind("BusyUser", "80001", "TEST", 1100L).getStatus());
        store.close();
    }

    @Test
    void persistentUnavailableFailureIsVisibleAndHealthCheckCanRecover() throws Exception {
        try (SqliteStore store = initialized("health-recovery.db")) {
            store.markFailureIfOperational(new StorageException(
                    StorageException.Kind.UNAVAILABLE, "simulated unavailable database"));

            assertFalse(store.getHealth().isHealthy());
            StorageException blocked = assertThrows(StorageException.class,
                    () -> store.findByPlayer("Steve"));
            assertEquals(StorageException.Kind.UNAVAILABLE, blocked.getKind());

            assertTrue(store.checkHealth().isHealthy());
            assertFalse(store.findByPlayer("Steve").isPresent());
        }
    }

    @Test
    void schemaCheckRejectsANameCollisionHidingThePendingUniqueIndex()
            throws Exception {
        Path database = tempDirectory.resolve("bad-index.db");
        try (SqliteStore ignored = initialized(database)) {
            // Build a valid database before replacing the critical index.
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX uq_pending_verification");
            statement.execute("CREATE INDEX uq_pending_verification "
                    + "ON verification_challenges(group_id,qq_user_id)");
        }

        SqliteStore reopened = new SqliteStore(database, 2000);
        StorageException failure = assertThrows(StorageException.class, reopened::initialize);
        assertEquals(StorageException.Kind.SCHEMA, failure.getKind());
        assertFalse(reopened.getHealth().isHealthy());
    }

    @Test
    void expirationCanBeProcessedInBoundedBatches() throws Exception {
        try (SqliteStore store = initialized("bounded-expiry.db")) {
            store.createOrReplaceVerification("90001", "91001", "one", 1L, 10L, 2);
            store.createOrReplaceVerification("90002", "91002", "two", 1L, 11L, 2);
            store.createOrReplaceVerification("90003", "91003", "three", 1L, 12L, 2);

            assertEquals(2, store.expireAndListVerifications(20L, 2).size());
            assertEquals(1, store.expireAndListVerifications(20L, 2).size());
            assertTrue(store.expireAndListVerifications(20L, 2).isEmpty());
        }
    }

    private SqliteStore initialized(String fileName) throws StorageException {
        return initialized(tempDirectory.resolve(fileName));
    }

    private static SqliteStore initialized(Path database) throws StorageException {
        SqliteStore store = new SqliteStore(database, 2000);
        store.initialize();
        return store;
    }

    private static int queryInt(Path database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static String queryString(Path database, String sql, String... parameters)
            throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static int executeUpdate(Path database, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }
}
