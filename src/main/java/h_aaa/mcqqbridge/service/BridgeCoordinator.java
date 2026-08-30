package h_aaa.mcqqbridge.service;

import com.google.gson.JsonObject;
import h_aaa.mcqqbridge.config.PluginConfig;
import h_aaa.mcqqbridge.domain.BindResult;
import h_aaa.mcqqbridge.domain.Binding;
import h_aaa.mcqqbridge.domain.CommandType;
import h_aaa.mcqqbridge.domain.GroupCommandParser;
import h_aaa.mcqqbridge.domain.MessageTemplates;
import h_aaa.mcqqbridge.domain.MinecraftName;
import h_aaa.mcqqbridge.domain.ParsedCommand;
import h_aaa.mcqqbridge.domain.QqIdentifier;
import h_aaa.mcqqbridge.domain.VerificationMode;
import h_aaa.mcqqbridge.onebot.OneBotEventListener;
import h_aaa.mcqqbridge.storage.UnbindResult;
import h_aaa.mcqqbridge.storage.StorageException;
import h_aaa.mcqqbridge.storage.VerificationAttemptResult;
import h_aaa.mcqqbridge.storage.VerificationChallenge;
import h_aaa.mcqqbridge.util.CryptoUtil;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BridgeCoordinator implements OneBotEventListener {
    private static final int MAX_GROUP_CARD_LENGTH = 60;

    private final PluginConfig config;
    private final DatabaseService database;
    private final OneBotGateway oneBot;
    private final ProxyServer proxy;
    private final ScheduledExecutorService scheduler;
    private final Logger logger;
    private final GroupCommandParser commandParser;
    private final AdminPolicy adminPolicy;

    public BridgeCoordinator(PluginConfig config, DatabaseService database,
                             OneBotGateway oneBot, ProxyServer proxy,
                             ScheduledExecutorService scheduler, Logger logger) {
        this.config = config;
        this.database = database;
        this.oneBot = oneBot;
        this.proxy = proxy;
        this.scheduler = scheduler;
        this.logger = logger;
        this.commandParser = new GroupCommandParser(config.getCommands());
        this.adminPolicy = new AdminPolicy(
                config.getAdminUserIds(), config.isRequireAdminGroupRole(),
                config.getCommands().isAllowMembersQueryOther(),
                config.getCommands().isAllowMembersStatus());
    }

    @Override
    public void onEvent(String rawJson, JsonObject payload) {
        OneBotEventView event = new OneBotEventView(rawJson, payload);
        if (!config.isAllowedGroup(event.getGroupId())) {
            return;
        }
        if (event.isGroupMessage()) {
            handleGroupMessage(event);
        } else if (event.isNotice("group_increase")) {
            handleGroupIncrease(event);
        } else if (event.isNotice("group_decrease")) {
            handleGroupDecrease(event);
        }
    }

    private void handleGroupMessage(OneBotEventView event) {
        ParsedCommand command = commandParser.parse(event.getRawMessage());
        if (command.getType() == CommandType.UNKNOWN) {
            return;
        }
        switch (command.getType()) {
            case BIND:
                bind(event, command.getArgument());
                break;
            case MY_BINDING:
                findOwnBinding(event);
                break;
            case QUERY_OTHER:
                queryOther(event, command.getArgument());
                break;
            case UNBIND_PLAYER:
                unbindPlayer(event, command.getArgument());
                break;
            case UNBIND_QQ:
                unbindQq(event, command.getArgument());
                break;
            case STATUS:
                sendStatus(event);
                break;
            case MENU:
                reply(event, render(config.getMessages().getMenu(), event, null));
                break;
            case VERIFY:
                verifyNewMember(event, command.getArgument());
                break;
            default:
                break;
        }
    }

    private void bind(OneBotEventView event, String argument) {
        MinecraftName name;
        try {
            name = MinecraftName.parse(argument);
        } catch (IllegalArgumentException error) {
            reply(event, render(config.getMessages().getInvalidPlayerName(), event, null));
            return;
        }
        database.bind(name.getValue(), event.getUserId(), "GROUP_DIRECT", "QQ_USER",
                        event.getUserId(), "QQ group bind command")
                .whenComplete((result, error) -> {
                    if (error != null) {
                        databaseFailure(event, "创建绑定", error);
                        return;
                    }
                    Binding binding = result.getBinding();
                    if (result.getStatus() == BindResult.Status.CREATED) {
                        reply(event, render(config.getMessages().getBindSuccess(), event, binding));
                        updateGroupCard(event, binding);
                    } else if (result.getStatus() == BindResult.Status.SAME_BINDING
                            || result.getStatus() == BindResult.Status.QQ_ALREADY_BOUND) {
                        reply(event, render(
                                config.getMessages().getQqAlreadyBound(), event, binding));
                    } else {
                        reply(event, render(
                                config.getMessages().getPlayerAlreadyBound(), event, binding));
                    }
                });
    }

    private void findOwnBinding(OneBotEventView event) {
        database.findByQq(event.getUserId()).whenComplete((binding, error) -> {
            if (error != null) {
                databaseFailure(event, "查询自己的绑定", error);
                return;
            }
            reply(event, binding.isPresent()
                    ? render(config.getMessages().getMyBinding(), event, binding.get())
                    : render(config.getMessages().getNotBound(), event, null));
        });
    }

    private void queryOther(OneBotEventView event, String argument) {
        if (!adminPolicy.canQueryOther(event.getUserId(), event.getSenderRole())) {
            reply(event, config.getMessages().getNotAdmin());
            return;
        }
        String qq;
        try {
            qq = QqIdentifier.parse(argument);
        } catch (IllegalArgumentException error) {
            reply(event, render(config.getMessages().getInvalidQq(), event, null));
            return;
        }
        final String targetQq = qq;
        database.findByQq(targetQq).whenComplete((binding, error) -> {
            if (error != null) {
                databaseFailure(event, "查询其他绑定", error);
                return;
            }
            if (binding.isPresent()) {
                Map<String, String> values = tokens(event, binding.get());
                values.put("qq", targetQq);
                reply(event, MessageTemplates.render(
                        config.getMessages().getOtherBinding(), values));
            } else {
                reply(event, render(config.getMessages().getNotBound(), event, null));
            }
        });
    }

    private void unbindPlayer(OneBotEventView event, String argument) {
        if (!adminPolicy.isAdministrator(event.getUserId(), event.getSenderRole())) {
            reply(event, config.getMessages().getNotAdmin());
            return;
        }
        MinecraftName name;
        try {
            name = MinecraftName.parseLegacy(argument);
        } catch (IllegalArgumentException error) {
            reply(event, render(config.getMessages().getInvalidPlayerName(), event, null));
            return;
        }
        database.unbindByPlayer(name.getValue(), "QQ_ADMIN", event.getUserId(),
                        "QQ administrator unbind by player")
                .whenComplete((result, error) -> handleUnbindResult(event, result, error));
    }

    private void unbindQq(OneBotEventView event, String argument) {
        if (!adminPolicy.isAdministrator(event.getUserId(), event.getSenderRole())) {
            reply(event, config.getMessages().getNotAdmin());
            return;
        }
        String qq;
        try {
            qq = QqIdentifier.parse(argument);
        } catch (IllegalArgumentException error) {
            reply(event, render(config.getMessages().getInvalidQq(), event, null));
            return;
        }
        database.unbindByQq(qq, "QQ_ADMIN", event.getUserId(),
                        "QQ administrator unbind by QQ")
                .whenComplete((result, error) -> handleUnbindResult(event, result, error));
    }

    private void handleUnbindResult(OneBotEventView event, UnbindResult result, Throwable error) {
        if (error != null) {
            databaseFailure(event, "解除绑定", error);
            return;
        }
        if (result.getStatus() != UnbindResult.Status.REMOVED || result.getBinding() == null) {
            reply(event, render(config.getMessages().getNotBound(), event, null));
            return;
        }
        Binding binding = result.getBinding();
        reply(event, render(config.getMessages().getUnbindSuccess(), event, binding));
        disconnectBoundPlayer(binding);
    }

    private void sendStatus(OneBotEventView event) {
        if (!adminPolicy.canViewStatus(event.getUserId(), event.getSenderRole())) {
            reply(event, config.getMessages().getNotAdmin());
            return;
        }
        Map<String, String> values = tokens(event, null);
        values.put("online", Integer.toString(proxy.getOnlineCount()));
        values.put("onebot", oneBot.statusSummary());
        values.put("database", database.getHealth().getStatus().name());
        reply(event, MessageTemplates.render(config.getMessages().getStatus(), values));
    }

    private void handleGroupIncrease(OneBotEventView event) {
        if (event.getUserId().isEmpty() || event.getUserId().equals(event.getSelfId())) {
            return;
        }
        if (config.getGroupEvents().getWelcome().isEnabled()) {
            reply(event, render(
                    config.getGroupEvents().getWelcome().getMessage(), event, null));
        }
        PluginConfig.Verification verification = config.getGroupEvents().getVerification();
        if (verification.getMode() == VerificationMode.OFF) {
            return;
        }
        String code = verification.getMode() == VerificationMode.FIXED
                ? verification.getFixedCode()
                : CryptoUtil.randomCode(verification.getRandomCodeLength());
        long expiresAt = System.currentTimeMillis()
                + TimeUnit.MINUTES.toMillis(verification.getExpiresMinutes());
        database.createVerification(event.getGroupId(), event.getUserId(), code, expiresAt,
                        verification.getMaxAttempts())
                .whenComplete((challenge, error) -> {
                    if (error != null) {
                        databaseFailure(event, "创建入群验证码", error);
                        return;
                    }
                    Map<String, String> values = tokens(event, null);
                    values.put("minutes", Integer.toString(verification.getExpiresMinutes()));
                    values.put("code", code);
                    reply(event, MessageTemplates.render(verification.getPrompt(), values));
                    scheduleVerificationExpiry(challenge);
                });
    }

    private void verifyNewMember(OneBotEventView event, String code) {
        if (code == null || code.isEmpty()) {
            reply(event, render(config.getMessages().getCommandUsage(), event, null));
            return;
        }
        database.verify(event.getGroupId(), event.getUserId(), code)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        databaseFailure(event, "校验入群验证码", error);
                        return;
                    }
                    PluginConfig.Verification verification =
                            config.getGroupEvents().getVerification();
                    if (result.getStatus() == VerificationAttemptResult.Status.VERIFIED) {
                        reply(event, render(verification.getSuccess(), event, null));
                    } else if (result.getStatus()
                            == VerificationAttemptResult.Status.INVALID_CODE) {
                        Map<String, String> values = tokens(event, null);
                        values.put("remaining", Integer.toString(
                                result.getChallenge().getRemainingAttempts()));
                        reply(event, MessageTemplates.render(verification.getFailed(), values));
                    } else if (result.getStatus()
                            == VerificationAttemptResult.Status.NOT_FOUND) {
                        reply(event, render(
                                config.getMessages().getVerificationNotPending(), event, null));
                    } else {
                        reply(event, render(verification.getExpired(), event, null));
                        if (verification.isKickOnExpire()) {
                            kick(event.getGroupId(), event.getUserId());
                        }
                    }
                });
    }

    private void handleGroupDecrease(OneBotEventView event) {
        String departedUser = event.getUserId();
        if (departedUser.isEmpty()) {
            return;
        }
        if (departedUser.equals(event.getSelfId()) || "kick_me".equals(event.getSubType())) {
            logger.severe("OneBot account was removed from an allowed QQ group; no bindings were changed");
            return;
        }
        if (!("leave".equals(event.getSubType()) || "kick".equals(event.getSubType()))) {
            return;
        }
        if (!config.getGroupEvents().isLeaveUnbindEnabled()) {
            database.cancelVerification(event.getGroupId(), departedUser, "GROUP_DECREASE")
                    .whenComplete((ignored, error) -> logFailure("取消离群验证码", error));
            return;
        }
        database.processExternalUnbind(
                        event.eventKey(), "ONEBOT_GROUP_DECREASE", event.getGroupId(), departedUser,
                        event.getOperatorId(), event.getSubType())
                .whenComplete((result, error) -> {
                    if (error != null) {
                        logFailure("处理退群解绑", error);
                        return;
                    }
                    if (result.getStatus() == UnbindResult.Status.REMOVED
                            && result.getBinding() != null) {
                        disconnectBoundPlayer(result.getBinding());
                    }
                });
    }

    private void updateGroupCard(OneBotEventView event, Binding binding) {
        PluginConfig.GroupCard cardConfig = config.getGroupEvents().getGroupCard();
        if (!cardConfig.isEnabled()) {
            return;
        }
        String card = render(cardConfig.getFormat(), event, binding);
        if (card.length() > MAX_GROUP_CARD_LENGTH) {
            card = card.substring(0, MAX_GROUP_CARD_LENGTH);
        }
        try {
            oneBot.setGroupCard(event.getGroupId(), event.getUserId(), card)
                    .whenComplete((ignored, error) -> logFailure("更新群名片", error));
        } catch (RuntimeException error) {
            logFailure("更新群名片", error);
        }
    }

    private void scheduleVerificationExpiry(VerificationChallenge challenge) {
        long delay = Math.max(1L,
                challenge.getExpiresAtEpochMillis() - System.currentTimeMillis());
        try {
            scheduler.schedule(this::expirePendingVerifications, delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException error) {
            logFailure("安排验证码过期任务", error);
        }
    }

    public void expirePendingVerifications() {
        database.expireAndListVerifications().whenComplete((expired, error) -> {
            if (error != null) {
                logFailure("标记过期验证码", error);
                return;
            }
            PluginConfig.Verification verification = config.getGroupEvents().getVerification();
            for (VerificationChallenge challenge : expired) {
                Map<String, String> values = baseTokens(challenge.getQqUserId(), null);
                send(challenge.getGroupId(), MessageTemplates.render(
                        verification.getExpired(), values));
                if (verification.isKickOnExpire()) {
                    kick(challenge.getGroupId(), challenge.getQqUserId());
                }
            }
        });
    }

    private void disconnectBoundPlayer(Binding binding) {
        ProxiedPlayer player = proxy.getPlayer(binding.getPlayerName());
        if (player != null && player.isConnected()) {
            player.disconnect(config.getAccessControl().getRevokedMessage());
        }
    }

    private void reply(OneBotEventView event, String message) {
        send(event.getGroupId(), message);
    }

    private void send(String groupId, String message) {
        try {
            oneBot.sendGroupMessage(groupId, message)
                    .whenComplete((ignored, error) -> logFailure("发送群消息", error));
        } catch (RuntimeException error) {
            logFailure("发送群消息", error);
        }
    }

    private void kick(String groupId, String userId) {
        try {
            oneBot.kickGroupMember(groupId, userId)
                    .whenComplete((ignored, error) -> logFailure("移出未验证群成员", error));
        } catch (RuntimeException error) {
            logFailure("移出未验证群成员", error);
        }
    }

    private void databaseFailure(OneBotEventView event, String operation, Throwable error) {
        StorageException storage = storageException(error);
        if (storage != null && storage.getKind() == StorageException.Kind.CONSTRAINT) {
            logger.warning(operation + "被数据库约束安全拒绝");
            reply(event, render(config.getMessages().getLegacyConflict(), event, null));
            return;
        }
        logFailure(operation, error);
        reply(event, render(config.getMessages().getDatabaseError(), event, null));
    }

    private static StorageException storageException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof StorageException) {
                return (StorageException) current;
            }
            current = current.getCause();
        }
        return null;
    }

    private void logFailure(String operation, Throwable error) {
        if (error != null) {
            logger.log(Level.WARNING, operation + "失败: " + safeMessage(error));
        }
    }

    private String safeMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        String safe = message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName()
                : message;
        String token = config.getOneBot().getToken();
        return token == null || token.isEmpty() ? safe : safe.replace(token, "<redacted>");
    }

    private String render(String template, OneBotEventView event, Binding binding) {
        return MessageTemplates.render(template, tokens(event, binding));
    }

    private Map<String, String> tokens(OneBotEventView event, Binding binding) {
        return baseTokens(event.getUserId(), binding);
    }

    private static Map<String, String> baseTokens(String qqUserId, Binding binding) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("at", MessageTemplates.mention(qqUserId));
        values.put("qq", binding == null ? qqUserId : binding.getQqUserId());
        values.put("player", binding == null ? "" : binding.getPlayerName());
        return values;
    }
}
