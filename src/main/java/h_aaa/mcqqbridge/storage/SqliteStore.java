package h_aaa.mcqqbridge.storage;

import h_aaa.mcqqbridge.domain.BindResult;
import h_aaa.mcqqbridge.domain.Binding;
import h_aaa.mcqqbridge.domain.MinecraftName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class SqliteStore implements AutoCloseable {
    private static final int DEFAULT_EXPIRATION_BATCH_SIZE = 1000;
    private static final int SCHEMA_VERSION = 3;
    private static final String SCHEMA_V1_CHECKSUM =
            "v1-bindings-audit-group-events-verification-legacy-migration-20260830";
    private static final String SCHEMA_V2_CHECKSUM =
            "v2-bindings-audit-group-events-verification-legacy-blocks-20260830";
    private static final String SCHEMA_CHECKSUM =
            "v3-bindings-immutable-audit-verification-strict-legacy-blocks-20260830";
    private static final Pattern NUMERIC_ID = Pattern.compile("[1-9][0-9]{0,19}");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Path databaseFile;
    private final int busyTimeoutMillis;
    private volatile DatabaseHealth health = new DatabaseHealth(
            DatabaseHealth.Status.NEW, "尚未初始化", null, System.currentTimeMillis());

    public SqliteStore(Path databaseFile, int busyTimeoutMillis) {
        if (databaseFile == null) {
            throw new IllegalArgumentException("databaseFile 不能为空");
        }
        if (busyTimeoutMillis < 1) {
            throw new IllegalArgumentException("busyTimeoutMillis 必须大于 0");
        }
        this.databaseFile = databaseFile.toAbsolutePath().normalize();
        this.busyTimeoutMillis = busyTimeoutMillis;
    }

    public synchronized void initialize() throws StorageException {
        if (health.getStatus() == DatabaseHealth.Status.CLOSED) {
            throw new StorageException(StorageException.Kind.CLOSED, "数据库服务已经关闭");
        }
        if (health.isHealthy()) {
            return;
        }
        health = new DatabaseHealth(DatabaseHealth.Status.INITIALIZING, "正在初始化", null,
                System.currentTimeMillis());
        try {
            Class.forName("org.sqlite.JDBC");
            Path parent = databaseFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Connection connection = openRawConnection()) {
                verifyIntegrity(connection);
                execute(connection, "PRAGMA journal_mode=WAL");
                execute(connection, "PRAGMA synchronous=FULL");
                beginImmediate(connection);
                try {
                    createSchema(connection);
                    verifySchema(connection);
                    commit(connection);
                } catch (Exception e) {
                    rollbackQuietly(connection);
                    throw e;
                }
                verifyIntegrity(connection);
            }
            markHealthy("数据库可用");
        } catch (ClassNotFoundException e) {
            StorageException failure = new StorageException(StorageException.Kind.UNAVAILABLE,
                    "缺少 SQLite JDBC 驱动", e);
            markFailure(failure);
            throw failure;
        } catch (IOException e) {
            StorageException failure = new StorageException(StorageException.Kind.IO,
                    "无法创建数据库目录: " + e.getMessage(), e);
            markFailure(failure);
            throw failure;
        } catch (StorageException e) {
            markFailure(e);
            throw e;
        } catch (SQLException e) {
            StorageException failure = translate(e, "初始化数据库失败");
            markFailure(failure);
            throw failure;
        }
    }

    public synchronized DatabaseHealth checkHealth() throws StorageException {
        if (health.getStatus() == DatabaseHealth.Status.CLOSED) {
            throw new StorageException(StorageException.Kind.CLOSED, "数据库服务已经关闭");
        }
        try (Connection connection = openRawConnection()) {
            verifyIntegrity(connection);
            verifySchema(connection);
            markHealthy("健康检查通过");
            return health;
        } catch (StorageException e) {
            markFailure(e);
            throw e;
        } catch (SQLException e) {
            StorageException failure = translate(e, "数据库健康检查失败");
            markFailure(failure);
            throw failure;
        }
    }

    public DatabaseHealth getHealth() {
        return health;
    }

    public Path getDatabaseFile() {
        return databaseFile;
    }

    public Optional<Binding> findByPlayer(String playerName) throws StorageException {
        String normalized = MinecraftName.normalize(playerName);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        try (Connection connection = openOperationConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT binding_id,qq_user_id,player_name,player_name_norm,created_at_ms,source "
                             + "FROM bindings WHERE player_name_norm=?")) {
            statement.setString(1, normalized);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readStoredBinding(result).binding)
                        : Optional.<Binding>empty();
            }
        } catch (SQLException e) {
            throw fail(e, "按玩家名查询绑定失败");
        }
    }

    public Optional<Binding> findByQq(String qqUserId) throws StorageException {
        String qq = requireNumericId(qqUserId, "qqUserId");
        try (Connection connection = openOperationConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT binding_id,qq_user_id,player_name,player_name_norm,created_at_ms,source "
                             + "FROM bindings WHERE qq_user_id=?")) {
            statement.setString(1, qq);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readStoredBinding(result).binding)
                        : Optional.<Binding>empty();
            }
        } catch (SQLException e) {
            throw fail(e, "按 QQ 查询绑定失败");
        }
    }

    public List<Binding> listBindings() throws StorageException {
        try (Connection connection = openOperationConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT binding_id,qq_user_id,player_name,player_name_norm,created_at_ms,source "
                             + "FROM bindings ORDER BY player_name_norm");
             ResultSet result = statement.executeQuery()) {
            List<Binding> bindings = new ArrayList<Binding>();
            while (result.next()) {
                bindings.add(readStoredBinding(result).binding);
            }
            return Collections.unmodifiableList(bindings);
        } catch (SQLException e) {
            throw fail(e, "列出绑定失败");
        }
    }

    public BindResult bind(String playerName, String qqUserId, String source,
                           long nowEpochMillis) throws StorageException {
        return bind(playerName, qqUserId, source, "SYSTEM", source, "", nowEpochMillis);
    }

    public BindResult bind(String playerName, String qqUserId, String source,
                           String actorType, String actorId, String reason,
                           long nowEpochMillis) throws StorageException {
        MinecraftName name = MinecraftName.parse(playerName);
        String qq = requireNumericId(qqUserId, "qqUserId");
        String bindingSource = requireText(source, "source", 64);
        try (Connection connection = openOperationConnection()) {
            beginImmediate(connection);
            try {
                StoredBinding byQq = selectByQq(connection, qq);
                StoredBinding byPlayer = selectByPlayer(connection, name.getNormalized());
                if (byQq != null && byQq.binding.getNormalizedPlayerName()
                        .equals(name.getNormalized())) {
                    commit(connection);
                    return new BindResult(BindResult.Status.SAME_BINDING, byQq.binding);
                }
                if (byQq != null) {
                    commit(connection);
                    return new BindResult(BindResult.Status.QQ_ALREADY_BOUND, byQq.binding);
                }
                if (byPlayer != null) {
                    commit(connection);
                    return new BindResult(BindResult.Status.PLAYER_ALREADY_BOUND, byPlayer.binding);
                }
                requireLegacyIdentitiesAvailable(connection, name.getNormalized(), qq);
                long id = insertBinding(connection, name.getValue(), name.getNormalized(), qq,
                        bindingSource, nowEpochMillis);
                Binding binding = new Binding(qq, name.getValue(), name.getNormalized(),
                        nowEpochMillis, bindingSource);
                insertAudit(connection, "BIND", id, binding, actorType, actorId, reason, null,
                        nowEpochMillis);
                commit(connection);
                return new BindResult(BindResult.Status.CREATED, binding);
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            }
        } catch (StorageException e) {
            markFailureIfOperational(e);
            throw e;
        } catch (SQLException e) {
            throw fail(e, "创建绑定失败");
        }
    }

    public UnbindResult unbindByPlayer(String playerName, String actorType, String actorId,
                                       String reason, long nowEpochMillis) throws StorageException {
        final String normalized = MinecraftName.normalize(playerName);
        if (normalized.isEmpty()) {
            return new UnbindResult(UnbindResult.Status.NOT_FOUND, null);
        }
        return unbind("player_name_norm", normalized, actorType, actorId, reason,
                nowEpochMillis);
    }

    public UnbindResult unbindByQq(String qqUserId, String actorType, String actorId,
                                   String reason, long nowEpochMillis) throws StorageException {
        return unbind("qq_user_id", requireNumericId(qqUserId, "qqUserId"), actorType,
                actorId, reason, nowEpochMillis);
    }

    public UnbindResult processExternalUnbind(String eventId, String eventType, String groupId,
                                              String qqUserId, String actorId, String reason,
                                              long nowEpochMillis) throws StorageException {
        String key = requireText(eventId, "eventId", 256);
        String type = requireText(eventType, "eventType", 64);
        String group = requireNumericId(groupId, "groupId");
        String qq = requireNumericId(qqUserId, "qqUserId");
        try (Connection connection = openOperationConnection()) {
            beginImmediate(connection);
            try {
                int inserted;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT OR IGNORE INTO processed_external_events"
                                + "(event_key,event_type,group_id,qq_user_id,outcome,processed_at_ms) "
                                + "VALUES(?,?,?,?,'PENDING',?)")) {
                    statement.setString(1, key);
                    statement.setString(2, type);
                    statement.setString(3, group);
                    statement.setString(4, qq);
                    statement.setLong(5, nowEpochMillis);
                    inserted = statement.executeUpdate();
                }
                if (inserted == 0) {
                    commit(connection);
                    return new UnbindResult(UnbindResult.Status.DUPLICATE_EVENT, null);
                }

                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE verification_challenges SET state='CANCELLED',"
                                + "completed_at_ms=?,state_reason='GROUP_DECREASE' "
                                + "WHERE group_id=? AND qq_user_id=? AND state='PENDING'")) {
                    statement.setLong(1, nowEpochMillis);
                    statement.setString(2, group);
                    statement.setString(3, qq);
                    statement.executeUpdate();
                }

                StoredBinding existing = selectByQq(connection, qq);
                UnbindResult.Status status;
                if (existing == null) {
                    status = UnbindResult.Status.NOT_FOUND;
                } else {
                    insertAudit(connection, "UNBIND_EXTERNAL", existing.id, existing.binding,
                            "EXTERNAL_EVENT", actorId, reason, key, nowEpochMillis);
                    deleteBinding(connection, existing.id);
                    status = UnbindResult.Status.REMOVED;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE processed_external_events SET outcome=? WHERE event_key=?")) {
                    statement.setString(1, status.name());
                    statement.setString(2, key);
                    statement.executeUpdate();
                }
                commit(connection);
                return new UnbindResult(status, existing == null ? null : existing.binding);
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            }
        } catch (StorageException e) {
            markFailureIfOperational(e);
            throw e;
        } catch (SQLException e) {
            throw fail(e, "处理外部解绑事件失败");
        }
    }

    public VerificationChallenge createOrReplaceVerification(
            String groupId, String qqUserId, String plainCode, long createdAtEpochMillis,
            long expiresAtEpochMillis, int maxAttempts) throws StorageException {
        String group = requireNumericId(groupId, "groupId");
        String qq = requireNumericId(qqUserId, "qqUserId");
        String code = requireText(plainCode, "plainCode", 256);
        if (expiresAtEpochMillis <= createdAtEpochMillis) {
            throw new IllegalArgumentException("expiresAtEpochMillis 必须晚于创建时间");
        }
        if (maxAttempts < 1 || maxAttempts > 1000) {
            throw new IllegalArgumentException("maxAttempts 必须在 1 到 1000 之间");
        }
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);
        byte[] digest = digest(salt, code);
        String challengeId = UUID.randomUUID().toString();

        try (Connection connection = openOperationConnection()) {
            beginImmediate(connection);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE verification_challenges SET state='CANCELLED',"
                                + "completed_at_ms=?,state_reason='REPLACED' "
                                + "WHERE group_id=? AND qq_user_id=? AND state='PENDING'")) {
                    statement.setLong(1, createdAtEpochMillis);
                    statement.setString(2, group);
                    statement.setString(3, qq);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO verification_challenges"
                                + "(challenge_id,group_id,qq_user_id,salt,code_digest,state,"
                                + "created_at_ms,expires_at_ms,attempts,max_attempts) "
                                + "VALUES(?,?,?,?,?,'PENDING',?,?,0,?)")) {
                    statement.setString(1, challengeId);
                    statement.setString(2, group);
                    statement.setString(3, qq);
                    statement.setBytes(4, salt);
                    statement.setBytes(5, digest);
                    statement.setLong(6, createdAtEpochMillis);
                    statement.setLong(7, expiresAtEpochMillis);
                    statement.setInt(8, maxAttempts);
                    statement.executeUpdate();
                }
                commit(connection);
                return new VerificationChallenge(challengeId, group, qq,
                        VerificationChallenge.State.PENDING, createdAtEpochMillis,
                        expiresAtEpochMillis, 0, maxAttempts);
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            }
        } catch (StorageException e) {
            markFailureIfOperational(e);
            throw e;
        } catch (SQLException e) {
            throw fail(e, "保存验证码失败");
        }
    }

    public Optional<VerificationChallenge> findPendingVerification(String groupId,
                                                                    String qqUserId)
            throws StorageException {
        String group = requireNumericId(groupId, "groupId");
        String qq = requireNumericId(qqUserId, "qqUserId");
        try (Connection connection = openOperationConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT challenge_id,group_id,qq_user_id,state,created_at_ms,expires_at_ms,"
                             + "attempts,max_attempts FROM verification_challenges "
                             + "WHERE group_id=? AND qq_user_id=? AND state='PENDING'")) {
            statement.setString(1, group);
            statement.setString(2, qq);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readVerification(result))
                        : Optional.<VerificationChallenge>empty();
            }
        } catch (SQLException e) {
            throw fail(e, "查询验证码失败");
        }
    }

    public VerificationAttemptResult verifyAttempt(String groupId, String qqUserId,
                                                     String plainCode, long nowEpochMillis)
            throws StorageException {
        String group = requireNumericId(groupId, "groupId");
        String qq = requireNumericId(qqUserId, "qqUserId");
        String code = requireText(plainCode, "plainCode", 256);
        try (Connection connection = openOperationConnection()) {
            beginImmediate(connection);
            try {
                StoredVerification stored = selectPendingVerification(connection, group, qq);
                if (stored == null) {
                    commit(connection);
                    return new VerificationAttemptResult(
                            VerificationAttemptResult.Status.NOT_FOUND, null);
                }
                if (nowEpochMillis >= stored.challenge.getExpiresAtEpochMillis()) {
                    VerificationChallenge updated = updateVerificationState(connection, stored,
                            VerificationChallenge.State.EXPIRED, stored.challenge.getAttempts(),
                            nowEpochMillis, "EXPIRED");
                    commit(connection);
                    return new VerificationAttemptResult(
                            VerificationAttemptResult.Status.EXPIRED, updated);
                }
                if (stored.challenge.getAttempts() >= stored.challenge.getMaxAttempts()) {
                    VerificationChallenge updated = updateVerificationState(connection, stored,
                            VerificationChallenge.State.EXHAUSTED,
                            stored.challenge.getAttempts(), nowEpochMillis, "MAX_ATTEMPTS");
                    commit(connection);
                    return new VerificationAttemptResult(
                            VerificationAttemptResult.Status.MAX_ATTEMPTS, updated);
                }
                if (MessageDigest.isEqual(stored.digest, digest(stored.salt, code))) {
                    VerificationChallenge updated = updateVerificationState(connection, stored,
                            VerificationChallenge.State.VERIFIED,
                            stored.challenge.getAttempts(), nowEpochMillis, "VERIFIED");
                    commit(connection);
                    return new VerificationAttemptResult(
                            VerificationAttemptResult.Status.VERIFIED, updated);
                }

                int attempts = stored.challenge.getAttempts() + 1;
                boolean exhausted = attempts >= stored.challenge.getMaxAttempts();
                VerificationChallenge updated = updateVerificationState(connection, stored,
                        exhausted ? VerificationChallenge.State.EXHAUSTED
                                : VerificationChallenge.State.PENDING,
                        attempts, exhausted ? nowEpochMillis : null,
                        exhausted ? "MAX_ATTEMPTS" : "INVALID_CODE");
                commit(connection);
                return new VerificationAttemptResult(
                        exhausted ? VerificationAttemptResult.Status.MAX_ATTEMPTS
                                : VerificationAttemptResult.Status.INVALID_CODE,
                        updated);
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            }
        } catch (StorageException e) {
            markFailureIfOperational(e);
            throw e;
        } catch (SQLException e) {
            throw fail(e, "验证验证码失败");
        }
    }

    public int expireVerifications(long nowEpochMillis) throws StorageException {
        int total = 0;
        int expired;
        do {
            expired = expireAndListVerifications(
                    nowEpochMillis, DEFAULT_EXPIRATION_BATCH_SIZE).size();
            total += expired;
        } while (expired == DEFAULT_EXPIRATION_BATCH_SIZE);
        return total;
    }

    public List<VerificationChallenge> expireAndListVerifications(long nowEpochMillis)
            throws StorageException {
        return expireAndListVerifications(nowEpochMillis, DEFAULT_EXPIRATION_BATCH_SIZE);
    }

    public List<VerificationChallenge> expireAndListVerifications(long nowEpochMillis,
                                                                  int limit)
            throws StorageException {
        if (limit < 1 || limit > 10000) {
            throw new IllegalArgumentException("limit 必须在 1 到 10000 之间");
        }
        try (Connection connection = openOperationConnection()) {
            beginImmediate(connection);
            try {
                List<VerificationChallenge> pending = new ArrayList<VerificationChallenge>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT challenge_id,group_id,qq_user_id,state,created_at_ms,"
                                + "expires_at_ms,attempts,max_attempts "
                                + "FROM verification_challenges WHERE state='PENDING' "
                                + "AND expires_at_ms<=? ORDER BY expires_at_ms,challenge_id "
                                + "LIMIT ?")) {
                    statement.setLong(1, nowEpochMillis);
                    statement.setInt(2, limit);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            VerificationChallenge value = readVerification(result);
                            pending.add(new VerificationChallenge(value.getChallengeId(),
                                    value.getGroupId(), value.getQqUserId(),
                                    VerificationChallenge.State.EXPIRED,
                                    value.getCreatedAtEpochMillis(),
                                    value.getExpiresAtEpochMillis(), value.getAttempts(),
                                    value.getMaxAttempts()));
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE verification_challenges SET state='EXPIRED',completed_at_ms=?,"
                                + "state_reason='EXPIRED' WHERE challenge_id=? "
                                + "AND state='PENDING'")) {
                    for (VerificationChallenge challenge : pending) {
                        statement.setLong(1, nowEpochMillis);
                        statement.setString(2, challenge.getChallengeId());
                        if (statement.executeUpdate() != 1) {
                            throw new SQLException("到期验证码状态在事务内发生变化");
                        }
                    }
                }
                commit(connection);
                return Collections.unmodifiableList(pending);
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            }
        } catch (StorageException e) {
            markFailureIfOperational(e);
            throw e;
        } catch (SQLException e) {
            throw fail(e, "清理过期验证码失败");
        }
    }

    public boolean cancelVerification(String groupId, String qqUserId, String reason,
                                      long nowEpochMillis) throws StorageException {
        String group = requireNumericId(groupId, "groupId");
        String qq = requireNumericId(qqUserId, "qqUserId");
        try (Connection connection = openOperationConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE verification_challenges SET state='CANCELLED',completed_at_ms=?,"
                             + "state_reason=? WHERE group_id=? AND qq_user_id=? "
                             + "AND state='PENDING'")) {
            statement.setLong(1, nowEpochMillis);
            statement.setString(2, trimToLength(reason, 256));
            statement.setString(3, group);
            statement.setString(4, qq);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail(e, "取消验证码失败");
        }
    }

    public LegacyMigrationReport migrateFirstLegacy(List<Path> candidates, Path backupDirectory)
            throws StorageException {
        ensureHealthy();
        if (candidates == null) {
            return LegacyMigrationReport.noSource();
        }
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate.toAbsolutePath().normalize())) {
                return migrateLegacy(candidate, backupDirectory);
            }
        }
        return LegacyMigrationReport.noSource();
    }

    public LegacyMigrationReport migrateLegacy(Path source, Path backupDirectory)
            throws StorageException {
        ensureHealthy();
        return new LegacyWhitelistMigrator(this).migrate(source, backupDirectory);
    }

    @Override
    public synchronized void close() {
        health = new DatabaseHealth(DatabaseHealth.Status.CLOSED, "数据库服务已经关闭",
                StorageException.Kind.CLOSED, System.currentTimeMillis());
    }

    Connection openOperationConnection() throws StorageException {
        ensureHealthy();
        try {
            return openRawConnection();
        } catch (SQLException e) {
            throw fail(e, "打开数据库连接失败");
        }
    }

    void markFailureIfOperational(StorageException failure) {
        if (failure.getKind() == StorageException.Kind.CORRUPT
                || failure.getKind() == StorageException.Kind.SCHEMA
                || failure.getKind() == StorageException.Kind.UNAVAILABLE
                || failure.getKind() == StorageException.Kind.IO) {
            markFailure(failure);
        }
    }

    static void beginImmediate(Connection connection) throws SQLException {
        execute(connection, "BEGIN IMMEDIATE");
    }

    static void commit(Connection connection) throws SQLException {
        execute(connection, "COMMIT");
    }

    static void rollbackQuietly(Connection connection) {
        try {
            execute(connection, "ROLLBACK");
        } catch (SQLException ignored) {
        }
    }

    static StorageException translate(SQLException error, String context) {
        int code = error.getErrorCode();
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        StorageException.Kind kind;
        if (code == 5 || code == 6 || message.contains("database is locked")
                || message.contains("database table is locked")) {
            kind = StorageException.Kind.BUSY;
        } else if (code == 11 || code == 26 || message.contains("malformed")
                || message.contains("not a database")) {
            kind = StorageException.Kind.CORRUPT;
        } else if (code == 19 || message.contains("constraint failed")) {
            kind = StorageException.Kind.CONSTRAINT;
        } else if (message.contains("no such table") || message.contains("no such column")) {
            kind = StorageException.Kind.SCHEMA;
        } else {
            kind = StorageException.Kind.UNAVAILABLE;
        }
        return new StorageException(kind, context + ": " + error.getMessage(), error);
    }

    long insertBinding(Connection connection, String playerName, String normalized,
                       String qqUserId, String source, long nowEpochMillis) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO bindings(qq_user_id,player_name,player_name_norm,created_at_ms,source) "
                        + "VALUES(?,?,?,?,?)")) {
            statement.setString(1, qqUserId);
            statement.setString(2, playerName);
            statement.setString(3, normalized);
            statement.setLong(4, nowEpochMillis);
            statement.setString(5, source);
            statement.executeUpdate();
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT last_insert_rowid()")) {
            if (!result.next()) {
                throw new SQLException("无法取得新绑定 ID");
            }
            return result.getLong(1);
        }
    }

    void insertAudit(Connection connection, String action, long bindingId, Binding binding,
                     String actorType, String actorId, String reason, String externalEventKey,
                     long nowEpochMillis) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO binding_audit(action,binding_id,qq_user_id,player_name,"
                        + "player_name_norm,source,actor_type,actor_id,reason,external_event_key,"
                        + "created_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, action);
            statement.setLong(2, bindingId);
            statement.setString(3, binding.getQqUserId());
            statement.setString(4, binding.getPlayerName());
            statement.setString(5, binding.getNormalizedPlayerName());
            statement.setString(6, binding.getSource());
            statement.setString(7, trimToLength(actorType, 64));
            statement.setString(8, trimToLength(actorId, 256));
            statement.setString(9, trimToLength(reason, 512));
            statement.setString(10, externalEventKey);
            statement.setLong(11, nowEpochMillis);
            statement.executeUpdate();
        }
    }

    private UnbindResult unbind(String column, String value, String actorType, String actorId,
                                String reason, long nowEpochMillis) throws StorageException {
        try (Connection connection = openOperationConnection()) {
            beginImmediate(connection);
            try {
                StoredBinding existing = "qq_user_id".equals(column)
                        ? selectByQq(connection, value) : selectByPlayer(connection, value);
                if (existing == null) {
                    commit(connection);
                    return new UnbindResult(UnbindResult.Status.NOT_FOUND, null);
                }
                insertAudit(connection, "UNBIND", existing.id, existing.binding, actorType,
                        actorId, reason, null, nowEpochMillis);
                deleteBinding(connection, existing.id);
                commit(connection);
                return new UnbindResult(UnbindResult.Status.REMOVED, existing.binding);
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            }
        } catch (StorageException e) {
            markFailureIfOperational(e);
            throw e;
        } catch (SQLException e) {
            throw fail(e, "解除绑定失败");
        }
    }

    private void deleteBinding(Connection connection, long bindingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM bindings WHERE binding_id=?")) {
            statement.setLong(1, bindingId);
            statement.executeUpdate();
        }
    }

    private StoredBinding selectByPlayer(Connection connection, String normalized)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT binding_id,qq_user_id,player_name,player_name_norm,created_at_ms,source "
                        + "FROM bindings WHERE player_name_norm=?")) {
            statement.setString(1, normalized);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readStoredBinding(result) : null;
            }
        }
    }

    private StoredBinding selectByQq(Connection connection, String qqUserId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT binding_id,qq_user_id,player_name,player_name_norm,created_at_ms,source "
                        + "FROM bindings WHERE qq_user_id=?")) {
            statement.setString(1, qqUserId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readStoredBinding(result) : null;
            }
        }
    }

    private static StoredBinding readStoredBinding(ResultSet result) throws SQLException {
        return new StoredBinding(result.getLong("binding_id"), new Binding(
                result.getString("qq_user_id"), result.getString("player_name"),
                result.getString("player_name_norm"), result.getLong("created_at_ms"),
                result.getString("source")));
    }

    private StoredVerification selectPendingVerification(Connection connection, String group,
                                                          String qq) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT challenge_id,group_id,qq_user_id,salt,code_digest,state,created_at_ms,"
                        + "expires_at_ms,attempts,max_attempts FROM verification_challenges "
                        + "WHERE group_id=? AND qq_user_id=? AND state='PENDING'")) {
            statement.setString(1, group);
            statement.setString(2, qq);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new StoredVerification(readVerification(result), result.getBytes("salt"),
                        result.getBytes("code_digest"));
            }
        }
    }

    private VerificationChallenge updateVerificationState(
            Connection connection, StoredVerification stored, VerificationChallenge.State state,
            int attempts, Long completedAtEpochMillis, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE verification_challenges SET state=?,attempts=?,completed_at_ms=?,"
                        + "state_reason=? WHERE challenge_id=? AND state='PENDING'")) {
            statement.setString(1, state.name());
            statement.setInt(2, attempts);
            if (completedAtEpochMillis == null) {
                statement.setNull(3, java.sql.Types.BIGINT);
            } else {
                statement.setLong(3, completedAtEpochMillis.longValue());
            }
            statement.setString(4, reason);
            statement.setString(5, stored.challenge.getChallengeId());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("验证码状态并发更新失败");
            }
        }
        return new VerificationChallenge(stored.challenge.getChallengeId(),
                stored.challenge.getGroupId(), stored.challenge.getQqUserId(), state,
                stored.challenge.getCreatedAtEpochMillis(),
                stored.challenge.getExpiresAtEpochMillis(), attempts,
                stored.challenge.getMaxAttempts());
    }

    private static VerificationChallenge readVerification(ResultSet result) throws SQLException {
        return new VerificationChallenge(result.getString("challenge_id"),
                result.getString("group_id"), result.getString("qq_user_id"),
                VerificationChallenge.State.valueOf(result.getString("state")),
                result.getLong("created_at_ms"), result.getLong("expires_at_ms"),
                result.getInt("attempts"), result.getInt("max_attempts"));
    }

    private Connection openRawConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        try {
            execute(connection, "PRAGMA foreign_keys=ON");
            execute(connection, "PRAGMA busy_timeout=" + busyTimeoutMillis);
            execute(connection, "PRAGMA synchronous=FULL");
            return connection;
        } catch (SQLException e) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            throw e;
        }
    }

    private void createSchema(Connection connection) throws SQLException {
        execute(connection, "CREATE TABLE IF NOT EXISTS schema_migrations("
                + "version INTEGER PRIMARY KEY,checksum TEXT NOT NULL,applied_at_ms INTEGER NOT NULL)");
        execute(connection, "CREATE TABLE IF NOT EXISTS bindings("
                + "binding_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "qq_user_id TEXT NOT NULL UNIQUE,"
                + "player_name TEXT NOT NULL,"
                + "player_name_norm TEXT NOT NULL UNIQUE,"
                + "created_at_ms INTEGER NOT NULL,"
                + "source TEXT NOT NULL,"
                + "CHECK(length(qq_user_id) BETWEEN 1 AND 20),"
                + "CHECK(qq_user_id NOT GLOB '*[^0-9]*'),"
                + "CHECK(substr(qq_user_id,1,1) <> '0'),"
                + "CHECK(length(player_name_norm) BETWEEN 1 AND 16),"
                + "CHECK(player_name_norm NOT GLOB '*[^a-z0-9_]*'))");
        execute(connection, "CREATE TABLE IF NOT EXISTS binding_audit("
                + "audit_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "action TEXT NOT NULL,binding_id INTEGER NOT NULL,qq_user_id TEXT NOT NULL,"
                + "player_name TEXT NOT NULL,player_name_norm TEXT NOT NULL,source TEXT NOT NULL,"
                + "actor_type TEXT,actor_id TEXT,reason TEXT,external_event_key TEXT,"
                + "created_at_ms INTEGER NOT NULL)");
        execute(connection, "CREATE INDEX IF NOT EXISTS idx_binding_audit_qq "
                + "ON binding_audit(qq_user_id,created_at_ms)");
        execute(connection, "CREATE TRIGGER IF NOT EXISTS trg_binding_audit_no_update "
                + "BEFORE UPDATE ON binding_audit BEGIN "
                + "SELECT RAISE(ABORT,'binding_audit is immutable'); END");
        execute(connection, "CREATE TRIGGER IF NOT EXISTS trg_binding_audit_no_delete "
                + "BEFORE DELETE ON binding_audit BEGIN "
                + "SELECT RAISE(ABORT,'binding_audit is immutable'); END");
        execute(connection, "CREATE TABLE IF NOT EXISTS processed_external_events("
                + "event_key TEXT PRIMARY KEY,event_type TEXT NOT NULL,group_id TEXT NOT NULL,"
                + "qq_user_id TEXT NOT NULL,outcome TEXT NOT NULL,processed_at_ms INTEGER NOT NULL)");
        execute(connection, "CREATE TABLE IF NOT EXISTS verification_challenges("
                + "challenge_id TEXT PRIMARY KEY,group_id TEXT NOT NULL,qq_user_id TEXT NOT NULL,"
                + "salt BLOB NOT NULL,code_digest BLOB NOT NULL,state TEXT NOT NULL,"
                + "created_at_ms INTEGER NOT NULL,expires_at_ms INTEGER NOT NULL,"
                + "attempts INTEGER NOT NULL DEFAULT 0,max_attempts INTEGER NOT NULL,"
                + "completed_at_ms INTEGER,state_reason TEXT,"
                + "CHECK(state IN ('PENDING','VERIFIED','EXPIRED','CANCELLED','EXHAUSTED')),"
                + "CHECK(length(salt)>=16),CHECK(length(code_digest)=32),"
                + "CHECK(attempts>=0 AND attempts<=max_attempts),CHECK(max_attempts>0),"
                + "CHECK(expires_at_ms>created_at_ms))");
        execute(connection, "CREATE UNIQUE INDEX IF NOT EXISTS uq_pending_verification "
                + "ON verification_challenges(group_id,qq_user_id) WHERE state='PENDING'");
        execute(connection, "CREATE INDEX IF NOT EXISTS idx_verification_expiry "
                + "ON verification_challenges(state,expires_at_ms)");
        execute(connection, "CREATE TABLE IF NOT EXISTS migration_runs("
                + "run_id TEXT PRIMARY KEY,source_path TEXT NOT NULL,source_sha256 TEXT NOT NULL,"
                + "source_fingerprint TEXT NOT NULL UNIQUE,backup_path TEXT NOT NULL,"
                + "status TEXT NOT NULL,total_rows INTEGER NOT NULL,imported_rows INTEGER NOT NULL,"
                + "issue_rows INTEGER NOT NULL,completed_at_ms INTEGER NOT NULL)");
        execute(connection, "CREATE TABLE IF NOT EXISTS legacy_import_rows("
                + "run_id TEXT NOT NULL,legacy_row_id INTEGER NOT NULL,"
                + "raw_player_type TEXT,raw_player_value TEXT,raw_user_type TEXT,"
                + "raw_user_value TEXT,normalized_player_name TEXT,parsed_qq_user_id TEXT,"
                + "disposition TEXT NOT NULL,detail TEXT,binding_id INTEGER,"
                + "PRIMARY KEY(run_id,legacy_row_id),"
                + "FOREIGN KEY(run_id) REFERENCES migration_runs(run_id) ON DELETE RESTRICT)");
        execute(connection, "CREATE TABLE IF NOT EXISTS legacy_blocked_identities("
                + "identity_type TEXT NOT NULL,identity_value TEXT NOT NULL,"
                + "run_id TEXT NOT NULL,detail TEXT NOT NULL,created_at_ms INTEGER NOT NULL,"
                + "PRIMARY KEY(identity_type,identity_value),"
                + "CHECK(identity_type IN ('PLAYER_NAME','QQ_USER_ID')),"
                + "FOREIGN KEY(run_id) REFERENCES migration_runs(run_id) ON DELETE RESTRICT)");
        execute(connection, "CREATE INDEX IF NOT EXISTS idx_legacy_blocks_run "
                + "ON legacy_blocked_identities(run_id)");
        backfillLegacyBlockedIdentities(connection);
    }

    private void verifySchema(Connection connection) throws SQLException, StorageException {
        int version = 0;
        String checksum = null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT version,checksum FROM schema_migrations ORDER BY version DESC LIMIT 1");
             ResultSet result = statement.executeQuery()) {
            if (result.next()) {
                version = result.getInt(1);
                checksum = result.getString(2);
            }
        }
        if (version == 0) {
            insertSchemaVersion(connection);
        } else if ((version == 1 && SCHEMA_V1_CHECKSUM.equals(checksum))
                || (version == 2 && SCHEMA_V2_CHECKSUM.equals(checksum))) {
            insertSchemaVersion(connection);
        } else if (version != SCHEMA_VERSION || !SCHEMA_CHECKSUM.equals(checksum)) {
            throw new StorageException(StorageException.Kind.SCHEMA,
                    "不支持的数据库 schema 版本或校验值: " + version);
        }
        requireColumns(connection, "schema_migrations", "version", "checksum",
                "applied_at_ms");
        requireColumns(connection, "bindings", "binding_id", "qq_user_id", "player_name",
                "player_name_norm", "created_at_ms", "source");
        requireColumns(connection, "binding_audit", "audit_id", "action", "binding_id",
                "qq_user_id", "player_name", "player_name_norm", "source", "actor_type",
                "actor_id", "reason", "external_event_key", "created_at_ms");
        requireColumns(connection, "processed_external_events", "event_key", "event_type",
                "group_id", "qq_user_id", "outcome", "processed_at_ms");
        requireColumns(connection, "verification_challenges", "challenge_id", "group_id",
                "qq_user_id", "salt", "code_digest", "state", "created_at_ms",
                "expires_at_ms", "attempts", "max_attempts", "completed_at_ms",
                "state_reason");
        requireColumns(connection, "migration_runs", "run_id", "source_path",
                "source_sha256", "source_fingerprint", "backup_path", "status",
                "total_rows", "imported_rows", "issue_rows", "completed_at_ms");
        requireColumns(connection, "legacy_import_rows", "run_id", "legacy_row_id",
                "raw_player_type", "raw_player_value", "raw_user_type", "raw_user_value",
                "normalized_player_name", "parsed_qq_user_id", "disposition", "detail",
                "binding_id");
        requireColumns(connection, "legacy_blocked_identities", "identity_type",
                "identity_value", "run_id", "detail", "created_at_ms");
        requireUniqueIndex(connection, "bindings", null, false, "qq_user_id");
        requireUniqueIndex(connection, "bindings", null, false, "player_name_norm");
        requireUniqueIndex(connection, "verification_challenges",
                "uq_pending_verification", true, "group_id", "qq_user_id");
        requireTrigger(connection, "trg_binding_audit_no_update");
        requireTrigger(connection, "trg_binding_audit_no_delete");
    }

    private static void insertSchemaVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_migrations(version,checksum,applied_at_ms) VALUES(?,?,?)")) {
            statement.setInt(1, SCHEMA_VERSION);
            statement.setString(2, SCHEMA_CHECKSUM);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private static void backfillLegacyBlockedIdentities(Connection connection)
            throws SQLException {
        String conflicts = "('CONFLICT_PLAYER','CONFLICT_QQ','CONFLICT_BOTH')";
        execute(connection, "INSERT OR IGNORE INTO legacy_blocked_identities"
                + "(identity_type,identity_value,run_id,detail,created_at_ms) "
                + "SELECT 'PLAYER_NAME',r.normalized_player_name,r.run_id,"
                + "COALESCE(r.detail,'legacy migration conflict'),m.completed_at_ms "
                + "FROM legacy_import_rows r JOIN migration_runs m ON m.run_id=r.run_id "
                + "WHERE r.disposition IN " + conflicts
                + " AND r.normalized_player_name IS NOT NULL");
        execute(connection, "INSERT OR IGNORE INTO legacy_blocked_identities"
                + "(identity_type,identity_value,run_id,detail,created_at_ms) "
                + "SELECT 'QQ_USER_ID',r.parsed_qq_user_id,r.run_id,"
                + "COALESCE(r.detail,'legacy migration conflict'),m.completed_at_ms "
                + "FROM legacy_import_rows r JOIN migration_runs m ON m.run_id=r.run_id "
                + "WHERE r.disposition IN " + conflicts
                + " AND r.parsed_qq_user_id IS NOT NULL");
    }

    private static void requireLegacyIdentitiesAvailable(Connection connection,
                                                         String normalizedPlayerName,
                                                         String qqUserId)
            throws SQLException, StorageException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT identity_type,identity_value,detail FROM legacy_blocked_identities "
                        + "WHERE (identity_type='PLAYER_NAME' AND identity_value=?) "
                        + "OR (identity_type='QQ_USER_ID' AND identity_value=?) "
                        + "ORDER BY CASE identity_type WHEN 'PLAYER_NAME' THEN 0 ELSE 1 END "
                        + "LIMIT 1")) {
            statement.setString(1, normalizedPlayerName);
            statement.setString(2, qqUserId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return;
                }
                boolean player = "PLAYER_NAME".equals(result.getString("identity_type"));
                String label = player ? "玩家名" : "QQ";
                throw new StorageException(StorageException.Kind.CONSTRAINT,
                        "旧库迁移冲突尚未处理，" + label + " 已被隔离: "
                                + result.getString("identity_value") + " ("
                                + result.getString("detail") + ")");
            }
        }
    }

    private static void requireColumns(Connection connection, String table, String... required)
            throws SQLException, StorageException {
        List<String> columns = new ArrayList<String>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
            while (result.next()) {
                columns.add(result.getString("name").toLowerCase(Locale.ROOT));
            }
        }
        for (String column : required) {
            if (!columns.contains(column.toLowerCase(Locale.ROOT))) {
                throw new StorageException(StorageException.Kind.SCHEMA,
                        "数据库表 " + table + " 缺少列 " + column);
            }
        }
    }

    private static void requireUniqueIndex(Connection connection, String table,
                                           String requiredName, boolean requirePartial,
                                           String... requiredColumns)
            throws SQLException, StorageException {
        boolean found = false;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "PRAGMA index_list(" + quoteIdentifier(table) + ")")) {
            while (result.next()) {
                String indexName = result.getString("name");
                if (result.getInt("unique") != 1
                        || (requiredName != null && !requiredName.equals(indexName))
                        || (requirePartial && result.getInt("partial") != 1)) {
                    continue;
                }
                List<String> columns = readIndexColumns(connection, indexName);
                if (columns.size() != requiredColumns.length) {
                    continue;
                }
                boolean matches = true;
                for (int index = 0; index < requiredColumns.length; index++) {
                    if (!requiredColumns[index].equalsIgnoreCase(columns.get(index))) {
                        matches = false;
                        break;
                    }
                }
                if (matches && (!requirePartial || hasPendingPredicate(connection, indexName))) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            throw new StorageException(StorageException.Kind.SCHEMA,
                    "数据库表 " + table + " 缺少唯一索引 "
                            + java.util.Arrays.toString(requiredColumns));
        }
    }

    private static List<String> readIndexColumns(Connection connection, String indexName)
            throws SQLException {
        List<String> columns = new ArrayList<String>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "PRAGMA index_info(" + quoteIdentifier(indexName) + ")")) {
            while (result.next()) {
                columns.add(result.getString("name"));
            }
        }
        return columns;
    }

    private static boolean hasPendingPredicate(Connection connection, String indexName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type='index' AND name=?")) {
            statement.setString(1, indexName);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return false;
                }
                String sql = result.getString(1);
                String normalized = sql == null ? ""
                        : sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
                return normalized.contains("wherestate='pending'");
            }
        }
    }

    private static void requireTrigger(Connection connection, String triggerName)
            throws SQLException, StorageException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=?")) {
            statement.setString(1, triggerName);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new StorageException(StorageException.Kind.SCHEMA,
                            "数据库缺少触发器 " + triggerName);
                }
            }
        }
    }

    private static String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void verifyIntegrity(Connection connection)
            throws SQLException, StorageException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new StorageException(StorageException.Kind.CORRUPT,
                        "SQLite quick_check 未通过");
            }
        }
    }

    private void ensureHealthy() throws StorageException {
        DatabaseHealth snapshot = health;
        if (snapshot.getStatus() == DatabaseHealth.Status.CLOSED) {
            throw new StorageException(StorageException.Kind.CLOSED, snapshot.getDetail());
        }
        if (!snapshot.isHealthy()) {
            StorageException.Kind kind = snapshot.getFailureKind() == null
                    ? StorageException.Kind.UNAVAILABLE : snapshot.getFailureKind();
            throw new StorageException(kind, "数据库未处于可用状态: " + snapshot.getDetail());
        }
    }

    private void markHealthy(String detail) {
        health = new DatabaseHealth(DatabaseHealth.Status.HEALTHY, detail, null,
                System.currentTimeMillis());
    }

    private void markFailure(StorageException failure) {
        health = new DatabaseHealth(DatabaseHealth.Status.UNHEALTHY, failure.getMessage(),
                failure.getKind(), System.currentTimeMillis());
    }

    private StorageException fail(SQLException error, String context) {
        StorageException failure = translate(error, context);
        markFailureIfOperational(failure);
        return failure;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String requireNumericId(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (!NUMERIC_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " 必须是 1 到 20 位正十进制数字");
        }
        return normalized;
    }

    private static String requireText(String value, String name, int maximumLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " 不能为空且长度不能超过 " + maximumLength);
        }
        return normalized;
    }

    private static String trimToLength(String value, int maximumLength) {
        String text = value == null ? "" : value.trim();
        return text.length() <= maximumLength ? text : text.substring(0, maximumLength);
    }

    private static byte[] digest(byte[] salt, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return digest.digest(code.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static final class StoredBinding {
        private final long id;
        private final Binding binding;

        private StoredBinding(long id, Binding binding) {
            this.id = id;
            this.binding = binding;
        }
    }

    private static final class StoredVerification {
        private final VerificationChallenge challenge;
        private final byte[] salt;
        private final byte[] digest;

        private StoredVerification(VerificationChallenge challenge, byte[] salt, byte[] digest) {
            this.challenge = challenge;
            this.salt = salt;
            this.digest = digest;
        }
    }
}
