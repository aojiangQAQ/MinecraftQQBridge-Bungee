package h_aaa.mcqqbridge.service;

import h_aaa.mcqqbridge.config.PluginConfig;
import h_aaa.mcqqbridge.domain.BindResult;
import h_aaa.mcqqbridge.domain.Binding;
import h_aaa.mcqqbridge.storage.DatabaseHealth;
import h_aaa.mcqqbridge.storage.LegacyMigrationReport;
import h_aaa.mcqqbridge.storage.SqliteStore;
import h_aaa.mcqqbridge.storage.StorageException;
import h_aaa.mcqqbridge.storage.UnbindResult;
import h_aaa.mcqqbridge.storage.VerificationAttemptResult;
import h_aaa.mcqqbridge.storage.VerificationChallenge;
import h_aaa.mcqqbridge.util.NamedThreadFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class DatabaseService implements AutoCloseable {
    private final SqliteStore store;
    private final PluginConfig.Migration migration;
    private final ThreadPoolExecutor executor;
    private final Logger logger;
    private final Set<DatabaseTask<?>> outstanding =
            java.util.Collections.newSetFromMap(
                    new ConcurrentHashMap<DatabaseTask<?>, Boolean>());
    private volatile LegacyMigrationReport migrationReport = LegacyMigrationReport.noSource();
    private volatile boolean started;

    public DatabaseService(PluginConfig.Database database, PluginConfig.Migration migration,
                           Logger logger) {
        this.store = new SqliteStore(database.getFile(), database.getBusyTimeoutMillis());
        this.migration = migration;
        this.logger = logger;
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(database.getQueueCapacity()),
                new NamedThreadFactory("mcqqbridge-db-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized LegacyMigrationReport start() throws StorageException {
        if (started) {
            return migrationReport;
        }
        store.initialize();
        if (migration.isEnabled()) {
            migrationReport = store.migrateFirstLegacy(
                    migration.getLegacyCandidates(), migration.getBackupDirectory());
        }
        started = true;
        return migrationReport;
    }

    public CompletableFuture<Optional<Binding>> findByPlayer(String playerName) {
        return submit(() -> store.findByPlayer(playerName));
    }

    public CompletableFuture<Optional<Binding>> findByQq(String qqUserId) {
        return submit(() -> store.findByQq(qqUserId));
    }

    public CompletableFuture<BindResult> bind(String playerName, String qqUserId,
                                               String source, String actorType,
                                               String actorId, String reason) {
        return submit(() -> store.bind(playerName, qqUserId, source, actorType, actorId,
                reason, System.currentTimeMillis()));
    }

    public CompletableFuture<UnbindResult> unbindByPlayer(
            String playerName, String actorType, String actorId, String reason) {
        return submit(() -> store.unbindByPlayer(playerName, actorType, actorId, reason,
                System.currentTimeMillis()));
    }

    public CompletableFuture<UnbindResult> unbindByQq(
            String qqUserId, String actorType, String actorId, String reason) {
        return submit(() -> store.unbindByQq(qqUserId, actorType, actorId, reason,
                System.currentTimeMillis()));
    }

    public CompletableFuture<UnbindResult> processExternalUnbind(
            String eventId, String eventType, String groupId, String qqUserId,
            String actorId, String reason) {
        return submit(() -> store.processExternalUnbind(eventId, eventType, groupId, qqUserId,
                actorId, reason, System.currentTimeMillis()));
    }

    public CompletableFuture<VerificationChallenge> createVerification(
            String groupId, String qqUserId, String plainCode, long expiresAtEpochMillis,
            int maxAttempts) {
        long now = System.currentTimeMillis();
        return submit(() -> store.createOrReplaceVerification(
                groupId, qqUserId, plainCode, now, expiresAtEpochMillis, maxAttempts));
    }

    public CompletableFuture<VerificationAttemptResult> verify(
            String groupId, String qqUserId, String plainCode) {
        return submit(() -> store.verifyAttempt(
                groupId, qqUserId, plainCode, System.currentTimeMillis()));
    }

    public CompletableFuture<Optional<VerificationChallenge>> findPendingVerification(
            String groupId, String qqUserId) {
        return submit(() -> store.findPendingVerification(groupId, qqUserId));
    }

    public CompletableFuture<Boolean> cancelVerification(
            String groupId, String qqUserId, String reason) {
        return submit(() -> store.cancelVerification(
                groupId, qqUserId, reason, System.currentTimeMillis()));
    }

    public CompletableFuture<Integer> expireVerifications() {
        return submit(() -> store.expireVerifications(System.currentTimeMillis()));
    }

    public CompletableFuture<List<VerificationChallenge>> expireAndListVerifications() {
        return submit(() -> store.expireAndListVerifications(System.currentTimeMillis()));
    }

    public CompletableFuture<DatabaseHealth> checkHealth() {
        return submit(store::checkHealth);
    }

    public DatabaseHealth getHealth() {
        return store.getHealth();
    }

    public LegacyMigrationReport getMigrationReport() {
        return migrationReport;
    }

    public Path getDatabaseFile() {
        return store.getDatabaseFile();
    }

    public int getQueueSize() {
        return executor.getQueue().size();
    }

    public int getQueueRemainingCapacity() {
        return executor.getQueue().remainingCapacity();
    }

    private <T> CompletableFuture<T> submit(StorageOperation<T> operation) {
        return submit(operation, true);
    }

    private <T> CompletableFuture<T> submit(StorageOperation<T> operation,
                                             boolean requireStarted) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        if ((requireStarted && !started) || executor.isShutdown()) {
            future.completeExceptionally(new StorageException(
                    StorageException.Kind.CLOSED, "数据库服务尚未启动或已经关闭"));
            return future;
        }
        DatabaseTask<T> task = new DatabaseTask<T>(operation, future);
        outstanding.add(task);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException error) {
            outstanding.remove(task);
            future.completeExceptionally(new StorageException(
                    StorageException.Kind.QUEUE_FULL, "数据库任务队列已满", error));
        }
        return future;
    }

    @Override
    public synchronized void close() {
        started = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                List<Runnable> abandoned = executor.shutdownNow();
                for (Runnable task : abandoned) {
                    if (task instanceof DatabaseService.DatabaseTask) {
                        ((DatabaseTask<?>) task).failClosed();
                    }
                }
                if (!abandoned.isEmpty()) {
                    logger.warning("关闭时取消了 " + abandoned.size() + " 个数据库任务");
                }
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    int incomplete = 0;
                    for (DatabaseTask<?> task : outstanding) {
                        if (task.failClosed()) {
                            incomplete++;
                        }
                    }
                    if (incomplete > 0) {
                        logger.warning("关闭时终止等待了 " + incomplete + " 个数据库任务回调");
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        } finally {
            store.close();
        }
    }

    @FunctionalInterface
    private interface StorageOperation<T> {
        T run() throws Exception;
    }

    private final class DatabaseTask<T> implements Runnable {
        private final StorageOperation<T> operation;
        private final CompletableFuture<T> future;

        private DatabaseTask(StorageOperation<T> operation, CompletableFuture<T> future) {
            this.operation = operation;
            this.future = future;
        }

        @Override
        public void run() {
            try {
                if (!future.isDone()) {
                    future.complete(operation.run());
                }
            } catch (Throwable error) {
                future.completeExceptionally(error);
            } finally {
                outstanding.remove(this);
            }
        }

        private boolean failClosed() {
            outstanding.remove(this);
            return future.completeExceptionally(new StorageException(
                    StorageException.Kind.CLOSED, "数据库服务关闭时取消了未完成任务"));
        }
    }
}
