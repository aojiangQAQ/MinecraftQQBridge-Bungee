package h_aaa.mcqqbridge;

import h_aaa.mcqqbridge.bungee.MinecraftQQBridgeCommand;
import h_aaa.mcqqbridge.bungee.ProxyLoginListener;
import h_aaa.mcqqbridge.config.ConfigException;
import h_aaa.mcqqbridge.config.ConfigLoader;
import h_aaa.mcqqbridge.config.PluginConfig;
import h_aaa.mcqqbridge.domain.VerificationMode;
import h_aaa.mcqqbridge.onebot.OneBotClient;
import h_aaa.mcqqbridge.onebot.OneBotStatus;
import h_aaa.mcqqbridge.service.AccessControlService;
import h_aaa.mcqqbridge.service.BindingAccessService;
import h_aaa.mcqqbridge.service.BridgeCoordinator;
import h_aaa.mcqqbridge.service.DatabaseService;
import h_aaa.mcqqbridge.service.DenyAllAccessService;
import h_aaa.mcqqbridge.service.OneBotClientGateway;
import h_aaa.mcqqbridge.storage.DatabaseHealth;
import h_aaa.mcqqbridge.storage.LegacyMigrationReport;
import h_aaa.mcqqbridge.storage.StorageException;
import h_aaa.mcqqbridge.util.NamedThreadFactory;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class MinecraftQQBridgePlugin extends Plugin {
    private static final String LOCKED_DENIED_MESSAGE =
            "QQ 绑定登录验证尚未正确配置，服务器已安全拒绝本次登录。";
    private static final String LOCKED_DATABASE_MESSAGE =
            "QQ 绑定数据库暂时不可用，服务器已安全拒绝本次登录。";

    private volatile RuntimeState runtimeState = RuntimeState.STARTING;
    private volatile String initializationError = "";
    private PluginConfig config;
    private DatabaseService database;
    private OneBotClient oneBot;
    private BridgeCoordinator coordinator;
    private ScheduledThreadPoolExecutor scheduler;
    private ProxyLoginListener loginListener;

    @Override
    public void onEnable() {
        scheduler = new ScheduledThreadPoolExecutor(
                2, new NamedThreadFactory("mcqqbridge-runtime-"));
        scheduler.setRemoveOnCancelPolicy(true);

        try {
            config = new ConfigLoader(this).load();
        } catch (ConfigException error) {
            initializationError = error.getMessage();
            runtimeState = RuntimeState.CONFIGURATION_LOCKED;
            getLogger().log(Level.SEVERE,
                    "配置校验失败，QQ 门禁保持默认拒绝: " + error.getMessage());
            registerAccessListener(new DenyAllAccessService(), 1000,
                    LOCKED_DENIED_MESSAGE, LOCKED_DATABASE_MESSAGE);
            registerCommand();
            return;
        }

        if (!getProxy().getConfig().isOnlineMode()) {
            getLogger().warning("当前 BungeeCord 为 online_mode=false：一期 QQ 门禁只校验"
                    + "已绑定玩家名，不能证明连接者拥有该 Minecraft 账号；必须继续依赖"
                    + "登录大厅 AuthMe，并阻止玩家绕过代理直连后端。");
        }

        boolean databaseReady = initializeDatabase();
        if (config.getAccessControl().isEnabled()) {
            AccessControlService access = databaseReady
                    ? new BindingAccessService(database)
                    : new DenyAllAccessService();
            registerAccessListener(
                    access,
                    config.getAccessControl().getCheckTimeoutMillis(),
                    config.getAccessControl().getDeniedMessage(),
                    config.getAccessControl().getDatabaseErrorMessage());
        }

        initializeOneBot();
        registerCommand();

        if (databaseReady && coordinator != null
                && config.getGroupEvents().getVerification().getMode()
                != VerificationMode.OFF) {
            scheduler.scheduleWithFixedDelay(
                    coordinator::expirePendingVerifications, 5, 30, TimeUnit.SECONDS);
        }

        if (!databaseReady) {
            runtimeState = RuntimeState.DEGRADED_DATABASE_LOCKED;
        } else if (config.getOneBot().isEnabled() && oneBot == null) {
            runtimeState = RuntimeState.DEGRADED_ONEBOT;
        } else {
            runtimeState = RuntimeState.RUNNING;
        }
        getLogger().info("MinecraftQQBridge-Bungee 已启动，状态=" + runtimeState);
    }

    private boolean initializeDatabase() {
        database = new DatabaseService(config.getDatabase(), config.getMigration(), getLogger());
        try {
            LegacyMigrationReport report = database.start();
            getLogger().info("SQLite 已就绪: " + database.getDatabaseFile());
            getLogger().info("旧 whitelist.db 迁移状态=" + report.getStatus()
                    + ", 总行=" + report.getTotalRows()
                    + ", 导入=" + report.getImportedRows()
                    + ", 问题=" + report.getIssueRows());
            return true;
        } catch (StorageException error) {
            initializationError = error.getMessage();
            getLogger().log(Level.SEVERE,
                    "SQLite 初始化或旧库迁移失败，登录门禁保持默认拒绝: "
                            + error.getMessage());
            return false;
        }
    }

    private void initializeOneBot() {
        AtomicReference<BridgeCoordinator> coordinatorReference =
                new AtomicReference<BridgeCoordinator>();
        try {
            oneBot = new OneBotClient(
                    config.getOneBot(),
                    (rawJson, event) -> {
                        BridgeCoordinator active = coordinatorReference.get();
                        if (active != null) {
                            active.onEvent(rawJson, event);
                        }
                    },
                    getLogger());
            coordinator = new BridgeCoordinator(
                    config, database, new OneBotClientGateway(oneBot),
                    getProxy(), scheduler, getLogger());
            coordinatorReference.set(coordinator);
            oneBot.start();
        } catch (RuntimeException error) {
            initializationError = error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
            oneBot = null;
            coordinator = null;
            getLogger().log(Level.SEVERE,
                    "OneBot 客户端初始化失败，QQ 功能不可用: " + initializationError);
        }
    }

    private void registerAccessListener(AccessControlService access, int timeoutMillis,
                                        String deniedMessage, String databaseMessage) {
        loginListener = new ProxyLoginListener(
                this, access, scheduler, timeoutMillis, deniedMessage, databaseMessage,
                getLogger());
        getProxy().getPluginManager().registerListener(this, loginListener);
    }

    private void registerCommand() {
        getProxy().getPluginManager().registerCommand(
                this, new MinecraftQQBridgeCommand(this));
    }

    @Override
    public void onDisable() {
        runtimeState = RuntimeState.STOPPED;
        if (loginListener != null) {
            loginListener.close();
        }
        if (oneBot != null) {
            oneBot.stop();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (database != null) {
            database.close();
        }
        getLogger().info("MinecraftQQBridge-Bungee 已停止");
    }

    public List<String> statusLines() {
        List<String> lines = new ArrayList<String>();
        lines.add("MinecraftQQBridge-Bungee " + getDescription().getVersion());
        lines.add("运行状态: " + runtimeState);
        lines.add("代理版本: " + getProxy().getVersion());
        lines.add("登录门禁: " + (config == null
                ? "LOCKED"
                : config.getAccessControl().isEnabled() ? "ENABLED" : "DISABLED"));
        if (database == null) {
            lines.add("数据库: NOT_INITIALIZED");
        } else {
            DatabaseHealth health = database.getHealth();
            lines.add("数据库: " + health.getStatus()
                    + " | 队列=" + database.getQueueSize());
        }
        if (oneBot == null) {
            lines.add("OneBot: NOT_INITIALIZED");
        } else {
            OneBotStatus status = oneBot.snapshot();
            lines.add("OneBot: " + status.getConnectionState()
                    + " | 心跳=" + (status.isHeartbeatHealthy() ? "HEALTHY" : "STALE")
                    + " | 待响应=" + status.getPendingRequests());
        }
        if (!initializationError.isEmpty()) {
            lines.add("最近启动错误: " + initializationError);
        }
        return lines;
    }

    public List<String> migrationLines() {
        List<String> lines = new ArrayList<String>();
        if (database == null) {
            lines.add("迁移: 数据库服务未初始化");
            return lines;
        }
        LegacyMigrationReport report = database.getMigrationReport();
        lines.add("迁移状态: " + report.getStatus());
        lines.add("总行=" + report.getTotalRows()
                + " | 导入=" + report.getImportedRows()
                + " | 问题=" + report.getIssueRows());
        if (report.getBackupDirectory() != null) {
            lines.add("备份目录: " + report.getBackupDirectory());
        }
        return lines;
    }

    public String reconnectOneBot() {
        if (oneBot == null || config == null || !config.getOneBot().isEnabled()) {
            return "OneBot 未启用或尚未初始化。";
        }
        oneBot.stop();
        oneBot.start();
        return "OneBot 重连流程已启动。";
    }

    public void checkDatabase(CommandSender sender) {
        if (database == null) {
            sender.sendMessage("数据库服务未初始化。");
            return;
        }
        sender.sendMessage("正在执行 SQLite quick_check...");
        database.checkHealth().whenComplete((health, error) -> {
            if (error != null) {
                sender.sendMessage("数据库检查失败: " + rootMessage(error));
            } else {
                sender.sendMessage("数据库检查结果: " + health.getStatus()
                        + " | " + health.getDetail());
            }
        });
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }
}
