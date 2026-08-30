package h_aaa.mcqqbridge.config;

import h_aaa.mcqqbridge.domain.VerificationMode;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class ConfigLoader {
    private static final Pattern QQ_ID = Pattern.compile("[0-9]{5,20}");

    private final Plugin plugin;

    public ConfigLoader(Plugin plugin) {
        this.plugin = plugin;
    }

    public PluginConfig load() throws ConfigException {
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path configFile = dataFolder.resolve("config.yml");
        try {
            Files.createDirectories(dataFolder);
            if (Files.notExists(configFile)) {
                try (InputStream input = plugin.getResourceAsStream("config.yml")) {
                    if (input == null) {
                        throw new ConfigException("JAR 中缺少 config.yml");
                    }
                    copyDefaultConfig(input, configFile);
                }
            }
            Configuration root = ConfigurationProvider.getProvider(YamlConfiguration.class)
                    .load(configFile.toFile());
            return parse(root, dataFolder);
        } catch (IOException e) {
            throw new ConfigException("读取配置失败: " + e.getMessage(), e);
        }
    }

    PluginConfig parse(Configuration root, Path dataFolder) throws ConfigException {
        boolean oneBotEnabled = root.getBoolean("onebot.enabled", false);
        String oneBotUrl = required(root, "onebot.url");
        String token = root.getString("onebot.token", "");
        validateWebSocketUrl(oneBotUrl);
        if (oneBotEnabled) {
            validateToken(token);
        }

        PluginConfig.OneBot oneBot = new PluginConfig.OneBot(
                oneBotEnabled,
                oneBotUrl,
                token,
                ranged(root, "onebot.connect-timeout-ms", 5000, 500, 60000),
                ranged(root, "onebot.request-timeout-ms", 5000, 500, 60000),
                ranged(root, "onebot.heartbeat-timeout-ms", 90000, 5000, 600000),
                ranged(root, "onebot.reconnect-min-seconds", 1, 1, 300),
                ranged(root, "onebot.reconnect-max-seconds", 60, 1, 3600));
        if (oneBot.getReconnectMinSeconds() > oneBot.getReconnectMaxSeconds()) {
            throw new ConfigException("OneBot 最小重连间隔不能大于最大重连间隔");
        }

        Set<String> groups = numericIds(root.getStringList("groups.allowed"), "groups.allowed");
        Set<String> admins = numericIds(root.getStringList("admins.user-ids"), "admins.user-ids");

        PluginConfig.AccessControl access = new PluginConfig.AccessControl(
                root.getBoolean("access-control.enabled", true),
                ranged(root, "access-control.check-timeout-ms", 3000, 250, 30000),
                required(root, "access-control.denied-message"),
                required(root, "access-control.database-error-message"),
                required(root, "access-control.revoked-message"));

        Path databaseFile = resolveContained(dataFolder, required(root, "database.file"), "database.file");
        PluginConfig.Database database = new PluginConfig.Database(
                databaseFile,
                ranged(root, "database.busy-timeout-ms", 2000, 100, 30000),
                ranged(root, "database.queue-capacity", 1024, 16, 100000));

        List<Path> legacyCandidates = new ArrayList<Path>();
        for (String configured : root.getStringList("migration.legacy-whitelist-db-candidates")) {
            if (configured == null || configured.trim().isEmpty()) {
                continue;
            }
            Path candidate = java.nio.file.Paths.get(configured.trim());
            legacyCandidates.add(candidate.isAbsolute()
                    ? candidate.normalize()
                    : dataFolder.resolve(candidate).normalize());
        }
        Path migrationBackup = resolveContained(dataFolder,
                required(root, "migration.backup-directory"),
                "migration.backup-directory");
        PluginConfig.Migration migration = new PluginConfig.Migration(
                root.getBoolean("migration.enabled", true), legacyCandidates, migrationBackup);

        PluginConfig.Commands commands = new PluginConfig.Commands(
                required(root, "commands.bind"),
                required(root, "commands.my-binding"),
                required(root, "commands.query-other"),
                required(root, "commands.unbind-player"),
                required(root, "commands.unbind-qq"),
                required(root, "commands.status"),
                required(root, "commands.menu"),
                required(root, "commands.verify"),
                root.getBoolean("commands.allow-members-query-other", true),
                root.getBoolean("commands.allow-members-status", true));
        validateCommands(commands);

        PluginConfig.Messages messages = new PluginConfig.Messages(
                required(root, "messages.bind-success"),
                required(root, "messages.qq-already-bound"),
                required(root, "messages.player-already-bound"),
                required(root, "messages.invalid-player-name"),
                required(root, "messages.my-binding"),
                required(root, "messages.not-bound"),
                required(root, "messages.other-binding"),
                required(root, "messages.unbind-success"),
                required(root, "messages.not-admin"),
                required(root, "messages.invalid-qq"),
                required(root, "messages.command-usage"),
                required(root, "messages.verification-not-pending"),
                required(root, "messages.legacy-conflict"),
                required(root, "messages.database-error"),
                required(root, "messages.status"),
                required(root, "messages.menu"));

        VerificationMode verificationMode;
        try {
            verificationMode = VerificationMode.parse(root.getString("group-events.verification.mode", "OFF"));
        } catch (IllegalArgumentException e) {
            throw new ConfigException("group-events.verification.mode 只能是 OFF、FIXED 或 RANDOM");
        }
        String fixedCode = root.getString("group-events.verification.fixed-code", "").trim();
        if (verificationMode == VerificationMode.FIXED && fixedCode.isEmpty()) {
            throw new ConfigException("固定验证模式下 fixed-code 不能为空");
        }
        PluginConfig.Verification verification = new PluginConfig.Verification(
                verificationMode,
                ranged(root, "group-events.verification.expires-minutes", 5, 1, 1440),
                ranged(root, "group-events.verification.max-attempts", 5, 1, 100),
                ranged(root, "group-events.verification.random-code-length", 6, 4, 16),
                fixedCode,
                root.getBoolean("group-events.verification.kick-on-expire", true),
                required(root, "group-events.verification.prompt"),
                required(root, "group-events.verification.success"),
                required(root, "group-events.verification.failed"),
                required(root, "group-events.verification.expired"));
        PluginConfig.GroupEvents events = new PluginConfig.GroupEvents(
                new PluginConfig.Welcome(
                        root.getBoolean("group-events.welcome.enabled", false),
                        required(root, "group-events.welcome.message")),
                root.getBoolean("group-events.leave-unbind.enabled", false),
                verification,
                new PluginConfig.GroupCard(
                        root.getBoolean("group-events.group-card.enabled", false),
                        required(root, "group-events.group-card.format")));

        int protocolVersion = ranged(root, "companion-api.protocol-version", 1, 1, 32767);
        String channel = required(root, "companion-api.channel");
        if (channel.length() > 20) {
            throw new ConfigException("companion-api.channel 不能超过 20 个字符，以兼容旧版 Bukkit");
        }

        return new PluginConfig(
                oneBot,
                groups,
                admins,
                root.getBoolean("admins.require-group-role", false),
                access,
                database,
                migration,
                commands,
                messages,
                events,
                new PluginConfig.CompanionApi(protocolVersion, channel));
    }

    static void copyDefaultConfig(InputStream input, Path configFile) throws IOException {
        Files.copy(input, configFile);
    }

    private static String required(Configuration root, String path) throws ConfigException {
        String value = root.getString(path, "");
        if (value == null || value.trim().isEmpty()) {
            throw new ConfigException(path + " 不能为空");
        }
        return value;
    }

    private static int ranged(Configuration root, String path, int defaultValue, int min, int max)
            throws ConfigException {
        int value = root.getInt(path, defaultValue);
        if (value < min || value > max) {
            throw new ConfigException(path + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return value;
    }

    private static Set<String> numericIds(List<String> values, String path) throws ConfigException {
        Set<String> result = new LinkedHashSet<String>();
        for (Object raw : values) {
            String value = String.valueOf(raw).trim();
            if (!QQ_ID.matcher(value).matches()) {
                throw new ConfigException(path + " 包含无效 QQ/群号，请使用带引号的纯数字字符串");
            }
            result.add(value);
        }
        return result;
    }

    private static Path resolveContained(Path dataFolder, String configured, String path)
            throws ConfigException {
        Path resolved = dataFolder.resolve(configured).toAbsolutePath().normalize();
        if (!resolved.startsWith(dataFolder)) {
            throw new ConfigException(path + " 必须位于插件数据目录内");
        }
        return resolved;
    }

    private static void validateWebSocketUrl(String value) throws ConfigException {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("ws".equals(scheme) || "wss".equals(scheme)) || uri.getHost() == null) {
                throw new ConfigException("onebot.url 必须是有效的 ws:// 或 wss:// 地址");
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new ConfigException("onebot.url 不允许包含用户信息、查询参数或片段");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!("127.0.0.1".equals(host) || "::1".equals(host)
                    || "[::1]".equals(host))) {
                throw new ConfigException("onebot.url 只允许使用 127.0.0.1 或 ::1");
            }
        } catch (URISyntaxException e) {
            throw new ConfigException("onebot.url 格式无效", e);
        }
    }

    private static void validateToken(String token) throws ConfigException {
        if (token.length() < 32 || token.length() > 256) {
            throw new ConfigException("onebot.enabled=true 时 token 长度必须为 32 到 256 个字符");
        }
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (value < 0x21 || value > 0x7e) {
                throw new ConfigException("onebot.token 只能包含不带空白的可打印 ASCII 字符");
            }
        }
    }

    private static void validateCommands(PluginConfig.Commands commands) throws ConfigException {
        Set<String> values = new LinkedHashSet<String>();
        String[] all = {
                commands.getBind(), commands.getMyBinding(), commands.getQueryOther(),
                commands.getUnbindPlayer(), commands.getUnbindQq(), commands.getStatus(),
                commands.getMenu(), commands.getVerify()
        };
        for (String command : all) {
            if (!values.add(command)) {
                throw new ConfigException("QQ 命令不能重复: " + command);
            }
        }
    }
}
