package h_aaa.mcqqbridge.bungee;

import h_aaa.mcqqbridge.service.AccessControlService;
import h_aaa.mcqqbridge.service.AccessDecision;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ProxyLoginListener implements Listener, AutoCloseable {
    private static final long ERROR_LOG_INTERVAL_MILLIS = 10_000L;

    private final Plugin plugin;
    private final AccessControlService accessControl;
    private final ScheduledExecutorService timeoutScheduler;
    private final int timeoutMillis;
    private final String unboundMessage;
    private final String storageErrorMessage;
    private final Logger logger;
    private final AtomicLong lastErrorLog = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentMap<LoginAttempt, Boolean> outstanding =
            new ConcurrentHashMap<LoginAttempt, Boolean>();

    public ProxyLoginListener(Plugin plugin, AccessControlService accessControl,
                              ScheduledExecutorService timeoutScheduler, int timeoutMillis,
                              String unboundMessage, String storageErrorMessage, Logger logger) {
        this.plugin = plugin;
        this.accessControl = accessControl;
        this.timeoutScheduler = timeoutScheduler;
        this.timeoutMillis = timeoutMillis;
        this.unboundMessage = unboundMessage;
        this.storageErrorMessage = storageErrorMessage;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(LoginEvent event) {
        event.registerIntent(plugin);
        LoginAttempt attempt = new LoginAttempt(event);
        outstanding.put(attempt, Boolean.TRUE);
        if (closed.get()) {
            finish(attempt, AccessDecision.deny(
                    AccessDecision.Type.DENY_STORAGE_ERROR), null);
            return;
        }

        ScheduledFuture<?> timeout;
        try {
            timeout = timeoutScheduler.schedule(
                    () -> finish(attempt,
                            AccessDecision.deny(AccessDecision.Type.DENY_STORAGE_ERROR),
                            new java.util.concurrent.TimeoutException("binding check timed out")),
                    timeoutMillis, TimeUnit.MILLISECONDS);
            attempt.setTimeout(timeout);
        } catch (RuntimeException error) {
            finish(attempt,
                    AccessDecision.deny(AccessDecision.Type.DENY_STORAGE_ERROR), error);
            return;
        }
        if (closed.get()) {
            finish(attempt, AccessDecision.deny(
                    AccessDecision.Type.DENY_STORAGE_ERROR), null);
            return;
        }

        try {
            CompletionStage<AccessDecision> check = accessControl.checkAccess(
                    event.getConnection().getName(), event.getConnection().getUniqueId());
            if (check == null) {
                throw new IllegalStateException("Access service returned null");
            }
            check.whenComplete((decision, error) -> {
                AccessDecision resolved = error == null && decision != null
                        ? decision
                        : AccessDecision.deny(AccessDecision.Type.DENY_STORAGE_ERROR);
                finish(attempt, resolved, error);
            });
        } catch (Throwable error) {
            finish(attempt,
                    AccessDecision.deny(AccessDecision.Type.DENY_STORAGE_ERROR), error);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (LoginAttempt attempt : outstanding.keySet()) {
            finish(attempt, AccessDecision.deny(
                    AccessDecision.Type.DENY_STORAGE_ERROR), null);
        }
    }

    private void finish(LoginAttempt attempt, AccessDecision decision, Throwable error) {
        if (!attempt.completed.compareAndSet(false, true)) {
            return;
        }
        attempt.cancelTimeout();
        outstanding.remove(attempt);
        LoginEvent event = attempt.event;
        try {
            if (error != null) {
                logAccessError(error);
            }
            if (!decision.isAllowed() && !event.isCancelled()) {
                event.setCancelReason(messageFor(decision));
                event.setCancelled(true);
            }
        } finally {
            event.completeIntent(plugin);
        }
    }

    private String messageFor(AccessDecision decision) {
        if (decision.getType() == AccessDecision.Type.DENY_UNBOUND
                || decision.getType() == AccessDecision.Type.DENY_INVALID_NAME) {
            return unboundMessage;
        }
        return storageErrorMessage;
    }

    private void logAccessError(Throwable error) {
        long now = System.currentTimeMillis();
        long previous = lastErrorLog.get();
        if (now - previous >= ERROR_LOG_INTERVAL_MILLIS
                && lastErrorLog.compareAndSet(previous, now)) {
            logger.log(Level.WARNING, "QQ 绑定登录检查失败，已默认拒绝本次登录", error);
        }
    }

    private static final class LoginAttempt {
        private final LoginEvent event;
        private final AtomicBoolean completed = new AtomicBoolean();
        private volatile ScheduledFuture<?> timeout;

        private LoginAttempt(LoginEvent event) {
            this.event = event;
        }

        private void setTimeout(ScheduledFuture<?> timeout) {
            this.timeout = timeout;
            if (completed.get()) {
                timeout.cancel(false);
            }
        }

        private void cancelTimeout() {
            ScheduledFuture<?> current = timeout;
            if (current != null) {
                current.cancel(false);
            }
        }
    }
}
