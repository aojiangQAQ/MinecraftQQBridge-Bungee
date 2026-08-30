package h_aaa.mcqqbridge.storage;

import h_aaa.mcqqbridge.domain.Binding;
import h_aaa.mcqqbridge.domain.MinecraftName;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class LegacyWhitelistMigrator {
    private static final Pattern QQ_ID = Pattern.compile("[1-9][0-9]{0,19}");
    private static final List<String> SIDE_CAR_SUFFIXES =
            Arrays.asList("", "-wal", "-shm", "-journal");

    private final SqliteStore store;

    LegacyWhitelistMigrator(SqliteStore store) {
        this.store = store;
    }

    LegacyMigrationReport migrate(Path sourcePath, Path backupRootPath)
            throws StorageException {
        if (sourcePath == null || backupRootPath == null) {
            throw new IllegalArgumentException("旧库路径和备份目录不能为空");
        }
        Path source = sourcePath.toAbsolutePath().normalize();
        Path backupRoot = backupRootPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new StorageException(StorageException.Kind.IO,
                    "旧 whitelist.db 不存在或不是普通文件: " + source);
        }
        if (source.equals(store.getDatabaseFile())) {
            throw new StorageException(StorageException.Kind.SCHEMA,
                    "旧 whitelist.db 与新 bridge.db 不能是同一个文件");
        }

        SourceState before = captureSourceState(source);
        LegacyMigrationReport prior = findPrior(before.fingerprint);
        if (prior != null) {
            return prior;
        }

        Path backupDirectory = createBackup(before, backupRoot);
        SourceState afterBackup = captureSourceState(source);
        requireUnchanged(before, afterBackup, "备份期间旧数据库发生变化");

        Path inspectionDirectory = backupDirectory.resolve("inspection-copy");
        Path inspectionDatabase = createInspectionCopy(before, backupDirectory,
                inspectionDirectory);
        List<LegacyRow> rows = readLegacyRows(inspectionDatabase);
        SourceState beforeImport = captureSourceState(source);
        requireUnchanged(before, beforeImport, "读取备份期间旧数据库发生变化");
        return importRows(source, backupDirectory, beforeImport, rows);
    }

    private LegacyMigrationReport importRows(Path source, Path backupDirectory,
                                             SourceState sourceState, List<LegacyRow> rows)
            throws StorageException {
        String runId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try (Connection connection = store.openOperationConnection()) {
            SqliteStore.beginImmediate(connection);
            try {
                LegacyMigrationReport prior = findPrior(connection, sourceState.fingerprint);
                if (prior != null) {
                    SqliteStore.commit(connection);
                    return prior;
                }

                ExistingBindings existing = readExistingBindings(connection);
                classify(rows, existing);

                Map<String, Long> resolvedPairIds = new LinkedHashMap<String, Long>(
                        existing.pairToBindingId);
                for (LegacyRow row : rows) {
                    if (row.outcome != LegacyRowDisposition.Outcome.IMPORTED) {
                        continue;
                    }
                    long bindingId = store.insertBinding(connection, row.playerName,
                            row.normalizedPlayerName, row.qqUserId, "LEGACY", now);
                    row.bindingId = bindingId;
                    resolvedPairIds.put(pair(row.normalizedPlayerName, row.qqUserId), bindingId);
                    Binding binding = new Binding(row.qqUserId, row.playerName,
                            row.normalizedPlayerName, now, "LEGACY");
                    store.insertAudit(connection, "MIGRATE_BIND", bindingId, binding,
                            "MIGRATION", source.toString(), "legacy run " + runId, null, now);
                }
                for (LegacyRow row : rows) {
                    if (row.bindingId == null && row.normalizedPlayerName != null
                            && row.qqUserId != null
                            && (row.outcome == LegacyRowDisposition.Outcome.ALREADY_PRESENT
                            || row.outcome == LegacyRowDisposition.Outcome.DUPLICATE_IDENTICAL)) {
                        row.bindingId = resolvedPairIds.get(pair(row.normalizedPlayerName,
                                row.qqUserId));
                    }
                }

                List<LegacyRowDisposition> dispositions = toDispositions(rows);
                int imported = count(dispositions, LegacyRowDisposition.Outcome.IMPORTED);
                int issues = issueCount(dispositions);
                LegacyMigrationReport.Status reportStatus = issues == 0
                        ? LegacyMigrationReport.Status.MIGRATED
                        : LegacyMigrationReport.Status.MIGRATED_WITH_ISSUES;

                insertRun(connection, runId, source, backupDirectory, sourceState,
                        reportStatus, rows.size(), imported, issues, now);
                insertRows(connection, runId, rows);
                insertBlockedIdentities(connection, runId, rows, now);

                LinkedHashMap<Path, FileMetadata> beforeCommit =
                        captureSourceMetadata(source);
                requireMetadataUnchanged(sourceState, beforeCommit,
                        "迁移期间旧数据库发生变化，已回滚新库写入");
                SqliteStore.commit(connection);
                return new LegacyMigrationReport(reportStatus, runId, source, backupDirectory,
                        sourceState.mainSha256, sourceState.fingerprint, dispositions);
            } catch (Exception e) {
                SqliteStore.rollbackQuietly(connection);
                throw e;
            }
        } catch (StorageException e) {
            throw e;
        } catch (SQLException e) {
            throw SqliteStore.translate(e, "导入旧 whitelist.db 失败");
        }
    }

    private LegacyMigrationReport findPrior(String fingerprint) throws StorageException {
        try (Connection connection = store.openOperationConnection()) {
            return findPrior(connection, fingerprint);
        } catch (SQLException e) {
            throw SqliteStore.translate(e, "检查旧库迁移记录失败");
        }
    }

    private LegacyMigrationReport findPrior(Connection connection, String fingerprint)
            throws SQLException {
        String runId;
        Path source;
        Path backup;
        String sourceSha;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT run_id,source_path,backup_path,source_sha256 FROM migration_runs "
                        + "WHERE source_fingerprint=?")) {
            statement.setString(1, fingerprint);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                runId = result.getString("run_id");
                source = java.nio.file.Paths.get(result.getString("source_path"));
                backup = java.nio.file.Paths.get(result.getString("backup_path"));
                sourceSha = result.getString("source_sha256");
            }
        }
        List<LegacyRowDisposition> dispositions = new ArrayList<LegacyRowDisposition>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT legacy_row_id,raw_player_value,raw_user_value,"
                        + "normalized_player_name,disposition,detail FROM legacy_import_rows "
                        + "WHERE run_id=? ORDER BY legacy_row_id")) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    dispositions.add(new LegacyRowDisposition(
                            result.getLong("legacy_row_id"),
                            result.getString("raw_player_value"),
                            result.getString("raw_user_value"),
                            result.getString("normalized_player_name"),
                            LegacyRowDisposition.Outcome.valueOf(
                                    result.getString("disposition")),
                            result.getString("detail")));
                }
            }
        }
        return new LegacyMigrationReport(LegacyMigrationReport.Status.ALREADY_MIGRATED,
                runId, source, backup, sourceSha, fingerprint, dispositions);
    }

    private static void classify(List<LegacyRow> rows, ExistingBindings existing) {
        Map<String, Set<String>> playerToQq = new LinkedHashMap<String, Set<String>>();
        Map<String, Set<String>> qqToPlayer = new LinkedHashMap<String, Set<String>>();

        for (Map.Entry<String, String> entry : existing.playerToQq.entrySet()) {
            add(playerToQq, entry.getKey(), entry.getValue());
            add(qqToPlayer, entry.getValue(), entry.getKey());
        }
        for (LegacyRow row : rows) {
            row.validate();
            if (!row.isValid()) {
                continue;
            }
            add(playerToQq, row.normalizedPlayerName, row.qqUserId);
            add(qqToPlayer, row.qqUserId, row.normalizedPlayerName);
        }

        Set<String> firstNewPairs = new LinkedHashSet<String>();
        for (LegacyRow row : rows) {
            if (!row.isValid()) {
                continue;
            }
            boolean playerConflict = playerToQq.get(row.normalizedPlayerName).size() > 1;
            boolean qqConflict = qqToPlayer.get(row.qqUserId).size() > 1;
            if (playerConflict && qqConflict) {
                row.outcome = LegacyRowDisposition.Outcome.CONFLICT_BOTH;
                row.detail = "规范化玩家名和 QQ 均与其他记录冲突";
                continue;
            }
            if (playerConflict) {
                row.outcome = LegacyRowDisposition.Outcome.CONFLICT_PLAYER;
                row.detail = "同一规范化玩家名对应多个 QQ";
                continue;
            }
            if (qqConflict) {
                row.outcome = LegacyRowDisposition.Outcome.CONFLICT_QQ;
                row.detail = "同一 QQ 对应多个规范化玩家名";
                continue;
            }

            String pair = pair(row.normalizedPlayerName, row.qqUserId);
            if (existing.pairToBindingId.containsKey(pair)) {
                row.outcome = LegacyRowDisposition.Outcome.ALREADY_PRESENT;
                row.bindingId = existing.pairToBindingId.get(pair);
                row.detail = "新库中已存在相同绑定";
            } else if (!firstNewPairs.add(pair)) {
                row.outcome = LegacyRowDisposition.Outcome.DUPLICATE_IDENTICAL;
                row.detail = "旧库中存在大小写或内容等价的重复绑定";
            } else {
                row.outcome = LegacyRowDisposition.Outcome.IMPORTED;
                row.detail = "已导入";
            }
        }
    }

    private static ExistingBindings readExistingBindings(Connection connection)
            throws SQLException {
        ExistingBindings existing = new ExistingBindings();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT binding_id,player_name_norm,qq_user_id FROM bindings");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String player = result.getString("player_name_norm");
                String qq = result.getString("qq_user_id");
                long id = result.getLong("binding_id");
                existing.playerToQq.put(player, qq);
                existing.qqToPlayer.put(qq, player);
                existing.pairToBindingId.put(pair(player, qq), id);
            }
        }
        return existing;
    }

    private static void insertRun(Connection connection, String runId, Path source,
                                  Path backupDirectory, SourceState sourceState,
                                  LegacyMigrationReport.Status status, int total, int imported,
                                  int issues, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO migration_runs(run_id,source_path,source_sha256,source_fingerprint,"
                        + "backup_path,status,total_rows,imported_rows,issue_rows,completed_at_ms) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, runId);
            statement.setString(2, source.toString());
            statement.setString(3, sourceState.mainSha256);
            statement.setString(4, sourceState.fingerprint);
            statement.setString(5, backupDirectory.toString());
            statement.setString(6, status.name());
            statement.setInt(7, total);
            statement.setInt(8, imported);
            statement.setInt(9, issues);
            statement.setLong(10, now);
            statement.executeUpdate();
        }
    }

    private static void insertRows(Connection connection, String runId, List<LegacyRow> rows)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO legacy_import_rows(run_id,legacy_row_id,raw_player_type,"
                        + "raw_player_value,raw_user_type,raw_user_value,normalized_player_name,"
                        + "parsed_qq_user_id,disposition,detail,binding_id) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            for (LegacyRow row : rows) {
                statement.setString(1, runId);
                statement.setLong(2, row.rowId);
                statement.setString(3, row.rawPlayerType);
                statement.setString(4, row.rawPlayerName);
                statement.setString(5, row.rawUserType);
                statement.setString(6, row.rawQqUserId);
                statement.setString(7, row.normalizedPlayerName);
                statement.setString(8, row.qqUserId);
                statement.setString(9, row.outcome.name());
                statement.setString(10, row.detail);
                if (row.bindingId == null) {
                    statement.setNull(11, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(11, row.bindingId.longValue());
                }
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertBlockedIdentities(Connection connection, String runId,
                                                List<LegacyRow> rows, long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO legacy_blocked_identities"
                        + "(identity_type,identity_value,run_id,detail,created_at_ms) "
                        + "VALUES(?,?,?,?,?)")) {
            for (LegacyRow row : rows) {
                if (!isConflict(row.outcome)) {
                    continue;
                }
                addBlockedIdentity(statement, "PLAYER_NAME", row.normalizedPlayerName,
                        runId, row.detail, now);
                addBlockedIdentity(statement, "QQ_USER_ID", row.qqUserId,
                        runId, row.detail, now);
            }
            statement.executeBatch();
        }
    }

    private static void addBlockedIdentity(PreparedStatement statement, String type,
                                           String value, String runId, String detail, long now)
            throws SQLException {
        statement.setString(1, type);
        statement.setString(2, value);
        statement.setString(3, runId);
        statement.setString(4, detail == null ? "legacy migration conflict" : detail);
        statement.setLong(5, now);
        statement.addBatch();
    }

    private static boolean isConflict(LegacyRowDisposition.Outcome outcome) {
        return outcome == LegacyRowDisposition.Outcome.CONFLICT_PLAYER
                || outcome == LegacyRowDisposition.Outcome.CONFLICT_QQ
                || outcome == LegacyRowDisposition.Outcome.CONFLICT_BOTH;
    }

    private static List<LegacyRow> readLegacyRows(Path database) throws StorageException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=2000");
                try (ResultSet result = statement.executeQuery("PRAGMA schema_version")) {
                    if (!result.next()) {
                        throw new SQLException("无法读取旧数据库 schema_version");
                    }
                }
                statement.execute("PRAGMA query_only=ON");
            }
            verifyLegacyIntegrity(connection);
            verifyLegacySchema(connection);
            try {
                return queryLegacyRows(connection, true);
            } catch (SQLException e) {
                String message = e.getMessage() == null ? ""
                        : e.getMessage().toLowerCase(Locale.ROOT);
                if (!message.contains("rowid")) {
                    throw e;
                }
                return queryLegacyRows(connection, false);
            }
        } catch (StorageException e) {
            throw e;
        } catch (SQLException e) {
            throw SqliteStore.translate(e, "读取旧 whitelist.db 备份失败");
        }
    }

    private static List<LegacyRow> queryLegacyRows(Connection connection, boolean hasRowId)
            throws SQLException {
        String prefix = hasRowId ? "rowid," : "";
        String order = hasRowId ? " ORDER BY rowid" : " ORDER BY \"playerName\"";
        String sql = "SELECT " + prefix
                + "typeof(\"playerName\") AS player_type,"
                + "CAST(\"playerName\" AS TEXT) AS player_value,"
                + "typeof(\"userId\") AS user_type,"
                + "CAST(\"userId\" AS TEXT) AS user_value FROM \"whitelist\"" + order;
        List<LegacyRow> rows = new ArrayList<LegacyRow>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            long syntheticId = 0L;
            while (result.next()) {
                long rowId = hasRowId ? result.getLong("rowid") : ++syntheticId;
                rows.add(new LegacyRow(rowId, result.getString("player_type"),
                        result.getString("player_value"), result.getString("user_type"),
                        result.getString("user_value")));
            }
        }
        return rows;
    }

    private static void verifyLegacyIntegrity(Connection connection)
            throws SQLException, StorageException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new StorageException(StorageException.Kind.CORRUPT,
                        "旧 whitelist.db 完整性检查失败");
            }
        }
    }

    private static void verifyLegacySchema(Connection connection)
            throws SQLException, StorageException {
        boolean tableExists;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND lower(name)='whitelist'");
             ResultSet result = statement.executeQuery()) {
            tableExists = result.next();
        }
        if (!tableExists) {
            throw new StorageException(StorageException.Kind.SCHEMA,
                    "旧数据库缺少 whitelist 表");
        }

        Column player = null;
        Column user = null;
        int columnCount = 0;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(\"whitelist\")")) {
            while (result.next()) {
                columnCount++;
                String name = result.getString("name");
                Column column = new Column(result.getString("type"), result.getInt("pk"));
                if ("playername".equalsIgnoreCase(name)) {
                    player = column;
                } else if ("userid".equalsIgnoreCase(name)) {
                    user = column;
                }
            }
        }
        if (player == null || user == null) {
            throw new StorageException(StorageException.Kind.SCHEMA,
                    "旧 whitelist 表必须包含 playerName 和 userId 列");
        }
        if (columnCount != 2 || !player.isText() || !user.isText()
                || player.primaryKeyPosition != 1 || user.primaryKeyPosition != 0) {
            throw new StorageException(StorageException.Kind.SCHEMA,
                    "旧表应为 whitelist(playerName TEXT PRIMARY KEY,userId TEXT)");
        }
    }

    private static Path createBackup(SourceState state, Path backupRoot)
            throws StorageException {
        try {
            Files.createDirectories(backupRoot);
            String directoryName = "whitelist-" + System.currentTimeMillis() + "-"
                    + UUID.randomUUID().toString();
            Path backupDirectory = Files.createDirectory(backupRoot.resolve(directoryName));
            List<String> lines = new ArrayList<String>();
            for (Path sourceFile : state.files.keySet()) {
                Path backupFile = backupDirectory.resolve(sourceFile.getFileName());
                Files.copy(sourceFile, backupFile,
                        StandardCopyOption.COPY_ATTRIBUTES);
                String expected = state.files.get(sourceFile);
                String actual = sha256(backupFile);
                if (!expected.equals(actual)) {
                    throw new StorageException(StorageException.Kind.IO,
                            "旧数据库备份哈希不一致: " + sourceFile.getFileName());
                }
                lines.add(expected + "  " + sourceFile.getFileName());
            }
            Path manifest = backupDirectory.resolve("SHA256.txt");
            Files.write(manifest, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return backupDirectory;
        } catch (IOException e) {
            throw new StorageException(StorageException.Kind.IO,
                    "备份旧 whitelist.db 失败: " + e.getMessage(), e);
        }
    }

    private static Path createInspectionCopy(SourceState state, Path backupDirectory,
                                             Path inspectionDirectory)
            throws StorageException {
        try {
            Files.createDirectory(inspectionDirectory);
            for (Path sourceFile : state.files.keySet()) {
                Path backupFile = backupDirectory.resolve(sourceFile.getFileName());
                Path inspectionFile = inspectionDirectory.resolve(sourceFile.getFileName());
                Files.copy(backupFile, inspectionFile,
                        StandardCopyOption.COPY_ATTRIBUTES);
                if (!state.files.get(sourceFile).equals(sha256(inspectionFile))) {
                    throw new StorageException(StorageException.Kind.IO,
                            "旧数据库检查副本哈希不一致: " + sourceFile.getFileName());
                }
            }
            return inspectionDirectory.resolve(state.mainFileName);
        } catch (IOException e) {
            throw new StorageException(StorageException.Kind.IO,
                    "创建旧库只读检查副本失败: " + e.getMessage(), e);
        }
    }

    private static SourceState captureSourceState(Path source) throws StorageException {
        LinkedHashMap<Path, String> files = new LinkedHashMap<Path, String>();
        LinkedHashMap<Path, FileMetadata> metadata = new LinkedHashMap<Path, FileMetadata>();
        for (String suffix : SIDE_CAR_SUFFIXES) {
            Path file = java.nio.file.Paths.get(source.toString() + suffix);
            if (Files.exists(file)) {
                if (!Files.isRegularFile(file)) {
                    throw new StorageException(StorageException.Kind.IO,
                            "旧数据库文件不是普通文件: " + file);
                }
                files.put(file, sha256(file));
                metadata.put(file, captureMetadata(file));
            }
        }
        if (!files.containsKey(source)) {
            throw new StorageException(StorageException.Kind.IO,
                    "旧 whitelist.db 不存在: " + source);
        }
        String fingerprint = fingerprint(files);
        return new SourceState(files, metadata, files.get(source), fingerprint,
                source.getFileName().toString());
    }

    private static LinkedHashMap<Path, FileMetadata> captureSourceMetadata(Path source)
            throws StorageException {
        LinkedHashMap<Path, FileMetadata> metadata = new LinkedHashMap<Path, FileMetadata>();
        for (String suffix : SIDE_CAR_SUFFIXES) {
            Path file = java.nio.file.Paths.get(source.toString() + suffix);
            if (Files.exists(file)) {
                if (!Files.isRegularFile(file)) {
                    throw new StorageException(StorageException.Kind.IO,
                            "旧数据库文件不是普通文件: " + file);
                }
                metadata.put(file, captureMetadata(file));
            }
        }
        return metadata;
    }

    private static FileMetadata captureMetadata(Path file) throws StorageException {
        try {
            return new FileMetadata(Files.size(file),
                    Files.getLastModifiedTime(file).toMillis());
        } catch (IOException e) {
            throw new StorageException(StorageException.Kind.IO,
                    "读取旧数据库文件属性失败: " + file, e);
        }
    }

    private static String sha256(Path file) throws StorageException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return hex(digest.digest());
        } catch (IOException e) {
            throw new StorageException(StorageException.Kind.IO,
                    "计算旧数据库 SHA-256 失败: " + file, e);
        }
    }

    private static String fingerprint(LinkedHashMap<Path, String> files) {
        MessageDigest digest = sha256Digest();
        for (Map.Entry<Path, String> entry : files.entrySet()) {
            digest.update(entry.getKey().getFileName().toString()
                    .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.getValue().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) '\n');
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void requireUnchanged(SourceState before, SourceState after, String message)
            throws StorageException {
        if (!before.files.equals(after.files)) {
            throw new StorageException(StorageException.Kind.IO, message);
        }
    }

    private static void requireMetadataUnchanged(
            SourceState before, LinkedHashMap<Path, FileMetadata> after, String message)
            throws StorageException {
        if (!before.metadata.equals(after)) {
            throw new StorageException(StorageException.Kind.IO, message);
        }
    }

    private static List<LegacyRowDisposition> toDispositions(List<LegacyRow> rows) {
        List<LegacyRowDisposition> result = new ArrayList<LegacyRowDisposition>();
        for (LegacyRow row : rows) {
            result.add(new LegacyRowDisposition(row.rowId, row.rawPlayerName,
                    row.rawQqUserId, row.normalizedPlayerName, row.outcome, row.detail));
        }
        return result;
    }

    private static int count(List<LegacyRowDisposition> rows,
                             LegacyRowDisposition.Outcome outcome) {
        int count = 0;
        for (LegacyRowDisposition row : rows) {
            if (row.getOutcome() == outcome) {
                count++;
            }
        }
        return count;
    }

    private static int issueCount(List<LegacyRowDisposition> rows) {
        int count = 0;
        for (LegacyRowDisposition row : rows) {
            LegacyRowDisposition.Outcome outcome = row.getOutcome();
            if (outcome != LegacyRowDisposition.Outcome.IMPORTED
                    && outcome != LegacyRowDisposition.Outcome.ALREADY_PRESENT
                    && outcome != LegacyRowDisposition.Outcome.DUPLICATE_IDENTICAL) {
                count++;
            }
        }
        return count;
    }

    private static void add(Map<String, Set<String>> map, String key, String value) {
        Set<String> values = map.get(key);
        if (values == null) {
            values = new LinkedHashSet<String>();
            map.put(key, values);
        }
        values.add(value);
    }

    private static String pair(String player, String qq) {
        return player + "\u0000" + qq;
    }

    private static final class ExistingBindings {
        private final Map<String, String> playerToQq = new LinkedHashMap<String, String>();
        private final Map<String, String> qqToPlayer = new LinkedHashMap<String, String>();
        private final Map<String, Long> pairToBindingId = new LinkedHashMap<String, Long>();
    }

    private static final class LegacyRow {
        private final long rowId;
        private final String rawPlayerType;
        private final String rawPlayerName;
        private final String rawUserType;
        private final String rawQqUserId;
        private String playerName;
        private String normalizedPlayerName;
        private String qqUserId;
        private LegacyRowDisposition.Outcome outcome;
        private String detail;
        private Long bindingId;

        private LegacyRow(long rowId, String rawPlayerType, String rawPlayerName,
                          String rawUserType, String rawQqUserId) {
            this.rowId = rowId;
            this.rawPlayerType = rawPlayerType;
            this.rawPlayerName = rawPlayerName;
            this.rawUserType = rawUserType;
            this.rawQqUserId = rawQqUserId;
        }

        private void validate() {
            if (outcome != null) {
                return;
            }
            if (!"text".equalsIgnoreCase(rawPlayerType)) {
                outcome = LegacyRowDisposition.Outcome.INVALID_PLAYER_NAME;
                detail = "playerName 的 SQLite 存储类型必须是 text";
                return;
            }
            try {
                MinecraftName name = MinecraftName.parseLegacy(rawPlayerName);
                playerName = name.getValue();
                normalizedPlayerName = name.getNormalized();
            } catch (IllegalArgumentException e) {
                outcome = LegacyRowDisposition.Outcome.INVALID_PLAYER_NAME;
                detail = "玩家名为空或不符合 [A-Za-z0-9_]{1,16}";
                return;
            }
            if (!"text".equalsIgnoreCase(rawUserType)) {
                outcome = LegacyRowDisposition.Outcome.INVALID_QQ_ID;
                detail = "userId 的 SQLite 存储类型必须是 text";
                return;
            }
            String normalizedQq = rawQqUserId == null ? "" : rawQqUserId.trim();
            if (!QQ_ID.matcher(normalizedQq).matches()) {
                outcome = LegacyRowDisposition.Outcome.INVALID_QQ_ID;
                detail = "QQ 必须是 1 到 20 位正十进制数字";
                return;
            }
            qqUserId = normalizedQq;
        }

        private boolean isValid() {
            return outcome == null;
        }
    }

    private static final class SourceState {
        private final LinkedHashMap<Path, String> files;
        private final LinkedHashMap<Path, FileMetadata> metadata;
        private final String mainSha256;
        private final String fingerprint;
        private final String mainFileName;

        private SourceState(LinkedHashMap<Path, String> files,
                            LinkedHashMap<Path, FileMetadata> metadata,
                            String mainSha256, String fingerprint, String mainFileName) {
            this.files = files;
            this.metadata = metadata;
            this.mainSha256 = mainSha256;
            this.fingerprint = fingerprint;
            this.mainFileName = mainFileName;
        }
    }

    private static final class FileMetadata {
        private final long size;
        private final long modifiedAtMillis;

        private FileMetadata(long size, long modifiedAtMillis) {
            this.size = size;
            this.modifiedAtMillis = modifiedAtMillis;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FileMetadata)) {
                return false;
            }
            FileMetadata that = (FileMetadata) other;
            return size == that.size && modifiedAtMillis == that.modifiedAtMillis;
        }

        @Override
        public int hashCode() {
            int result = (int) (size ^ (size >>> 32));
            result = 31 * result + (int) (modifiedAtMillis ^ (modifiedAtMillis >>> 32));
            return result;
        }
    }

    private static final class Column {
        private final String type;
        private final int primaryKeyPosition;

        private Column(String type, int primaryKeyPosition) {
            this.type = type == null ? "" : type;
            this.primaryKeyPosition = primaryKeyPosition;
        }

        private boolean isText() {
            return "TEXT".equals(type.trim().toUpperCase(Locale.ROOT));
        }
    }
}
