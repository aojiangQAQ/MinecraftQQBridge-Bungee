package h_aaa.mcqqbridge.storage;

import h_aaa.mcqqbridge.domain.BindResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyWhitelistMigratorTest {
    @TempDir
    Path tempDirectory;

    @Test
    void migratesValidRowsWithoutChangingSourceAndIsContentIdempotent() throws Exception {
        Path source = tempDirectory.resolve("legacy/whitelist.db");
        createLegacy(source, new String[][]{
                {"Steve", "10001"},
                {"AlexOne", "10002"}
        });
        String sourceShaBefore = sha256(source);
        Path backupRoot = tempDirectory.resolve("backups");

        try (SqliteStore store = initialized(tempDirectory.resolve("bridge.db"))) {
            LegacyMigrationReport first = store.migrateLegacy(source, backupRoot);
            assertEquals(LegacyMigrationReport.Status.MIGRATED, first.getStatus());
            assertEquals(2, first.getImportedRows());
            assertEquals(0, first.getIssueRows());
            assertEquals(sourceShaBefore, first.getSourceSha256());
            assertEquals(sourceShaBefore, sha256(source));
            assertEquals("10001", store.findByPlayer("STEVE").get().getQqUserId());
            assertEquals("AlexOne", store.findByQq("10002").get().getPlayerName());
            assertTrue(Files.isRegularFile(
                    first.getBackupDirectory().resolve("whitelist.db")));
            assertTrue(Files.isRegularFile(
                    first.getBackupDirectory().resolve("SHA256.txt")));
            assertTrue(Files.isRegularFile(first.getBackupDirectory()
                    .resolve("inspection-copy").resolve("whitelist.db")));

            long backupCount = countDirectories(backupRoot);
            LegacyMigrationReport second = store.migrateLegacy(source, backupRoot);
            assertEquals(LegacyMigrationReport.Status.ALREADY_MIGRATED,
                    second.getStatus());
            assertEquals(first.getRunId(), second.getRunId());
            assertEquals(first.getSourceFingerprint(), second.getSourceFingerprint());
            assertEquals(backupCount, countDirectories(backupRoot));
            assertEquals(2, store.listBindings().size());
            assertEquals(sourceShaBefore, sha256(source));
        }
    }

    @Test
    void recordsAllRowOutcomesAndQuarantinesEveryIdentityInAConflict()
            throws Exception {
        Path source = tempDirectory.resolve("conflicts/whitelist.db");
        createLegacy(source, new String[][]{
                {"Steve", "100"},
                {"STEVE", "101"},
                {"Alex", "200"},
                {"Bobby", "200"},
                {"bad-name", "300"},
                {"ValidName", "0"},
                {"CleanName", "400"}
        });
        String sourceShaBefore = sha256(source);
        Path database = tempDirectory.resolve("conflict-bridge.db");

        try (SqliteStore store = initialized(database)) {
            LegacyMigrationReport report = store.migrateLegacy(
                    source, tempDirectory.resolve("conflict-backups"));
            assertEquals(LegacyMigrationReport.Status.MIGRATED_WITH_ISSUES,
                    report.getStatus());
            assertEquals(7, report.getTotalRows());
            assertEquals(1, report.getImportedRows());
            assertEquals(6, report.getIssueRows());
            assertEquals(2, report.count(LegacyRowDisposition.Outcome.CONFLICT_PLAYER));
            assertEquals(2, report.count(LegacyRowDisposition.Outcome.CONFLICT_QQ));
            assertEquals(1, report.count(
                    LegacyRowDisposition.Outcome.INVALID_PLAYER_NAME));
            assertEquals(1, report.count(LegacyRowDisposition.Outcome.INVALID_QQ_ID));
            assertEquals(6, queryInt(database,
                    "SELECT count(*) FROM legacy_blocked_identities"));

            StorageException blockedPlayer = assertThrows(StorageException.class,
                    () -> store.bind("STEVE", "999", "SELF_SERVICE", 2000L));
            assertEquals(StorageException.Kind.CONSTRAINT, blockedPlayer.getKind());
            assertTrue(blockedPlayer.getMessage().contains("玩家名"));

            StorageException blockedQq = assertThrows(StorageException.class,
                    () -> store.bind("FreshName", "100", "SELF_SERVICE", 2001L));
            assertEquals(StorageException.Kind.CONSTRAINT, blockedQq.getKind());
            assertTrue(blockedQq.getMessage().contains("QQ"));

            assertEquals(BindResult.Status.SAME_BINDING,
                    store.bind("CLEANNAME", "400", "SELF_SERVICE", 2002L).getStatus());
            assertEquals(sourceShaBefore, sha256(source));
        }
    }

    @Test
    void upgradesVersionOneSchemaAndBackfillsPreviouslyRecordedConflicts()
            throws Exception {
        Path database = tempDirectory.resolve("upgrade.db");
        try (SqliteStore ignored = initialized(database)) {
            // Create the current schema first, then emulate data written by the version-one build.
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM schema_migrations");
            statement.executeUpdate("DELETE FROM legacy_blocked_identities");
            statement.executeUpdate("INSERT INTO schema_migrations"
                    + "(version,checksum,applied_at_ms) VALUES(1,"
                    + "'v1-bindings-audit-group-events-verification-legacy-migration-20260830',1)");
            statement.executeUpdate("INSERT INTO migration_runs"
                    + "(run_id,source_path,source_sha256,source_fingerprint,backup_path,status,"
                    + "total_rows,imported_rows,issue_rows,completed_at_ms) VALUES"
                    + "('old-run','old.db','sha','fingerprint','backup',"
                    + "'MIGRATED_WITH_ISSUES',1,0,1,10)");
            statement.executeUpdate("INSERT INTO legacy_import_rows"
                    + "(run_id,legacy_row_id,normalized_player_name,parsed_qq_user_id,"
                    + "disposition,detail) VALUES"
                    + "('old-run',1,'steve','123','CONFLICT_PLAYER','old conflict')");
        }

        try (SqliteStore upgraded = initialized(database)) {
            assertEquals(3, queryInt(database,
                    "SELECT max(version) FROM schema_migrations"));
            assertEquals(2, queryInt(database,
                    "SELECT count(*) FROM legacy_blocked_identities"));
            StorageException failure = assertThrows(StorageException.class,
                    () -> upgraded.bind("Steve", "999", "TEST", 20L));
            assertEquals(StorageException.Kind.CONSTRAINT, failure.getKind());
        }
    }

    @Test
    void reportsNoSourceWhenCandidateListHasNoRegularFile() throws Exception {
        try (SqliteStore store = initialized(tempDirectory.resolve("none.db"))) {
            LegacyMigrationReport report = store.migrateFirstLegacy(
                    java.util.Collections.singletonList(tempDirectory.resolve("missing.db")),
                    tempDirectory.resolve("backups"));
            assertEquals(LegacyMigrationReport.Status.NO_SOURCE, report.getStatus());
        }
    }

    @Test
    void invalidLegacySourceDoesNotPoisonTheHealthyTargetStore() throws Exception {
        Path source = tempDirectory.resolve("invalid/whitelist.db");
        Files.createDirectories(source.getParent());
        try (Connection ignored = DriverManager.getConnection("jdbc:sqlite:" + source)) {
            // Intentionally leave the database without the whitelist table.
        }

        try (SqliteStore store = initialized(tempDirectory.resolve("healthy-target.db"))) {
            StorageException failure = assertThrows(StorageException.class,
                    () -> store.migrateLegacy(source,
                            tempDirectory.resolve("invalid-backups")));
            assertEquals(StorageException.Kind.SCHEMA, failure.getKind());
            assertTrue(store.getHealth().isHealthy());
            assertEquals(BindResult.Status.CREATED,
                    store.bind("StillHealthy", "88001", "TEST", 100L).getStatus());
        }
    }

    @Test
    void recoversAHotRollbackJournalOnlyInsideTheInspectionCopy() throws Exception {
        Path source = tempDirectory.resolve("hot/whitelist.db");
        createLegacy(source, new String[0][0]);
        createHotLegacyDatabase(source);
        Path journal = Paths.get(source.toString() + "-journal");
        String sourceShaBefore = sha256(source);
        String journalShaBefore = sha256(journal);

        try (SqliteStore store = initialized(tempDirectory.resolve("hot-target.db"))) {
            LegacyMigrationReport report = store.migrateLegacy(
                    source, tempDirectory.resolve("hot-backups"));
            assertEquals(LegacyMigrationReport.Status.MIGRATED, report.getStatus());
            assertEquals(0, report.getTotalRows());
            assertEquals(sourceShaBefore, sha256(source));
            assertEquals(journalShaBefore, sha256(journal));
            assertTrue(Files.isRegularFile(report.getBackupDirectory()
                    .resolve("whitelist.db-journal")));
        }
    }

    private static void createLegacy(Path database, String[][] rows) throws Exception {
        Files.createDirectories(database.getParent());
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE whitelist(playerName TEXT PRIMARY KEY, userId TEXT)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO whitelist(playerName,userId) VALUES(?,?)")) {
                for (String[] row : rows) {
                    insert.setString(1, row[0]);
                    insert.setString(2, row[1]);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    private static void createHotLegacyDatabase(Path database) throws Exception {
        String javaName = System.getProperty("os.name").toLowerCase(Locale.ROOT)
                .contains("windows") ? "java.exe" : "java";
        Path javaExecutable = Paths.get(System.getProperty("java.home"), "bin", javaName);
        String classPath = System.getProperty("surefire.test.class.path");
        if (classPath == null || classPath.isEmpty()) {
            classPath = codeSource(HotJournalWriter.class)
                    + java.io.File.pathSeparator + codeSource(org.sqlite.JDBC.class)
                    + java.io.File.pathSeparator + codeSource(org.slf4j.LoggerFactory.class);
        }

        Process process = new ProcessBuilder(javaExecutable.toString(), "-cp", classPath,
                HotJournalWriter.class.getName(), database.toString())
                .inheritIO()
                .start();
        if (!process.waitFor(20L, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("hot-journal helper timed out");
        }
        if (process.exitValue() != 0) {
            throw new AssertionError("hot-journal helper exit code=" + process.exitValue());
        }
        Path journal = Paths.get(database.toString() + "-journal");
        if (!Files.isRegularFile(journal) || Files.size(journal) == 0L) {
            throw new AssertionError("rollback journal was not retained");
        }
    }

    private static String codeSource(Class<?> type) throws Exception {
        return Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
    }

    private static SqliteStore initialized(Path database) throws StorageException {
        SqliteStore store = new SqliteStore(database, 2000);
        store.initialize();
        return store;
    }

    private static long countDirectories(Path directory) throws Exception {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(Files::isDirectory).count();
        }
    }

    private static int queryInt(Path database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    public static final class HotJournalWriter {
        private HotJournalWriter() {
        }

        public static void main(String[] args) throws Exception {
            Class.forName("org.sqlite.JDBC");
            Path database = Paths.get(args[0]);
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + database);
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=DELETE");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA cache_size=1");
                statement.execute("PRAGMA cache_spill=ON");
                statement.execute("BEGIN IMMEDIATE");
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO whitelist(playerName,userId) VALUES(?,?)")) {
                    for (int index = 0; index < 5000; index++) {
                        insert.setString(1, String.format(Locale.ROOT,
                                "P%015d", index));
                        insert.setString(2, Integer.toString(index + 1));
                        insert.executeUpdate();
                    }
                }
                Runtime.getRuntime().halt(0);
            }
        }
    }
}
