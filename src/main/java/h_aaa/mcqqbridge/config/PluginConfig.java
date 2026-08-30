package h_aaa.mcqqbridge.config;

import h_aaa.mcqqbridge.domain.VerificationMode;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class PluginConfig {
    private final OneBot oneBot;
    private final Set<String> allowedGroups;
    private final Set<String> adminUserIds;
    private final boolean requireAdminGroupRole;
    private final AccessControl accessControl;
    private final Database database;
    private final Migration migration;
    private final Commands commands;
    private final Messages messages;
    private final GroupEvents groupEvents;
    private final CompanionApi companionApi;

    public PluginConfig(
            OneBot oneBot,
            Set<String> allowedGroups,
            Set<String> adminUserIds,
            boolean requireAdminGroupRole,
            AccessControl accessControl,
            Database database,
            Migration migration,
            Commands commands,
            Messages messages,
            GroupEvents groupEvents,
            CompanionApi companionApi) {
        this.oneBot = oneBot;
        this.allowedGroups = Collections.unmodifiableSet(allowedGroups);
        this.adminUserIds = Collections.unmodifiableSet(adminUserIds);
        this.requireAdminGroupRole = requireAdminGroupRole;
        this.accessControl = accessControl;
        this.database = database;
        this.migration = migration;
        this.commands = commands;
        this.messages = messages;
        this.groupEvents = groupEvents;
        this.companionApi = companionApi;
    }

    public OneBot getOneBot() {
        return oneBot;
    }

    public Set<String> getAllowedGroups() {
        return allowedGroups;
    }

    public Set<String> getAdminUserIds() {
        return adminUserIds;
    }

    public boolean isRequireAdminGroupRole() {
        return requireAdminGroupRole;
    }

    public AccessControl getAccessControl() {
        return accessControl;
    }

    public Database getDatabase() {
        return database;
    }

    public Migration getMigration() {
        return migration;
    }

    public Commands getCommands() {
        return commands;
    }

    public Messages getMessages() {
        return messages;
    }

    public GroupEvents getGroupEvents() {
        return groupEvents;
    }

    public CompanionApi getCompanionApi() {
        return companionApi;
    }

    public boolean isAllowedGroup(String groupId) {
        return allowedGroups.contains(groupId);
    }

    public boolean isConfiguredAdmin(String userId) {
        return adminUserIds.contains(userId);
    }

    public static final class OneBot {
        private final boolean enabled;
        private final String url;
        private final String token;
        private final int connectTimeoutMillis;
        private final int requestTimeoutMillis;
        private final int heartbeatTimeoutMillis;
        private final int reconnectMinSeconds;
        private final int reconnectMaxSeconds;

        public OneBot(boolean enabled, String url, String token, int connectTimeoutMillis,
                      int requestTimeoutMillis, int heartbeatTimeoutMillis,
                      int reconnectMinSeconds, int reconnectMaxSeconds) {
            this.enabled = enabled;
            this.url = url;
            this.token = token;
            this.connectTimeoutMillis = connectTimeoutMillis;
            this.requestTimeoutMillis = requestTimeoutMillis;
            this.heartbeatTimeoutMillis = heartbeatTimeoutMillis;
            this.reconnectMinSeconds = reconnectMinSeconds;
            this.reconnectMaxSeconds = reconnectMaxSeconds;
        }

        public boolean isEnabled() { return enabled; }
        public String getUrl() { return url; }
        public String getToken() { return token; }
        public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
        public int getRequestTimeoutMillis() { return requestTimeoutMillis; }
        public int getHeartbeatTimeoutMillis() { return heartbeatTimeoutMillis; }
        public int getReconnectMinSeconds() { return reconnectMinSeconds; }
        public int getReconnectMaxSeconds() { return reconnectMaxSeconds; }
    }

    public static final class AccessControl {
        private final boolean enabled;
        private final int checkTimeoutMillis;
        private final String deniedMessage;
        private final String databaseErrorMessage;
        private final String revokedMessage;

        public AccessControl(boolean enabled, int checkTimeoutMillis, String deniedMessage,
                             String databaseErrorMessage, String revokedMessage) {
            this.enabled = enabled;
            this.checkTimeoutMillis = checkTimeoutMillis;
            this.deniedMessage = deniedMessage;
            this.databaseErrorMessage = databaseErrorMessage;
            this.revokedMessage = revokedMessage;
        }

        public boolean isEnabled() { return enabled; }
        public int getCheckTimeoutMillis() { return checkTimeoutMillis; }
        public String getDeniedMessage() { return deniedMessage; }
        public String getDatabaseErrorMessage() { return databaseErrorMessage; }
        public String getRevokedMessage() { return revokedMessage; }
    }

    public static final class Database {
        private final Path file;
        private final int busyTimeoutMillis;
        private final int queueCapacity;

        public Database(Path file, int busyTimeoutMillis, int queueCapacity) {
            this.file = file;
            this.busyTimeoutMillis = busyTimeoutMillis;
            this.queueCapacity = queueCapacity;
        }

        public Path getFile() { return file; }
        public int getBusyTimeoutMillis() { return busyTimeoutMillis; }
        public int getQueueCapacity() { return queueCapacity; }
    }

    public static final class Migration {
        private final boolean enabled;
        private final List<Path> legacyCandidates;
        private final Path backupDirectory;

        public Migration(boolean enabled, List<Path> legacyCandidates, Path backupDirectory) {
            this.enabled = enabled;
            this.legacyCandidates = Collections.unmodifiableList(legacyCandidates);
            this.backupDirectory = backupDirectory;
        }

        public boolean isEnabled() { return enabled; }
        public List<Path> getLegacyCandidates() { return legacyCandidates; }
        public Path getBackupDirectory() { return backupDirectory; }
    }

    public static final class Commands {
        private final String bind;
        private final String myBinding;
        private final String queryOther;
        private final String unbindPlayer;
        private final String unbindQq;
        private final String status;
        private final String menu;
        private final String verify;
        private final boolean allowMembersQueryOther;
        private final boolean allowMembersStatus;

        public Commands(String bind, String myBinding, String queryOther, String unbindPlayer,
                        String unbindQq, String status, String menu, String verify,
                        boolean allowMembersQueryOther, boolean allowMembersStatus) {
            this.bind = bind;
            this.myBinding = myBinding;
            this.queryOther = queryOther;
            this.unbindPlayer = unbindPlayer;
            this.unbindQq = unbindQq;
            this.status = status;
            this.menu = menu;
            this.verify = verify;
            this.allowMembersQueryOther = allowMembersQueryOther;
            this.allowMembersStatus = allowMembersStatus;
        }

        public String getBind() { return bind; }
        public String getMyBinding() { return myBinding; }
        public String getQueryOther() { return queryOther; }
        public String getUnbindPlayer() { return unbindPlayer; }
        public String getUnbindQq() { return unbindQq; }
        public String getStatus() { return status; }
        public String getMenu() { return menu; }
        public String getVerify() { return verify; }
        public boolean isAllowMembersQueryOther() { return allowMembersQueryOther; }
        public boolean isAllowMembersStatus() { return allowMembersStatus; }
    }

    public static final class Messages {
        private final String bindSuccess;
        private final String qqAlreadyBound;
        private final String playerAlreadyBound;
        private final String invalidPlayerName;
        private final String myBinding;
        private final String notBound;
        private final String otherBinding;
        private final String unbindSuccess;
        private final String notAdmin;
        private final String invalidQq;
        private final String commandUsage;
        private final String verificationNotPending;
        private final String legacyConflict;
        private final String databaseError;
        private final String status;
        private final String menu;

        public Messages(String bindSuccess, String qqAlreadyBound, String playerAlreadyBound,
                        String invalidPlayerName, String myBinding, String notBound,
                        String otherBinding, String unbindSuccess, String notAdmin,
                        String invalidQq, String commandUsage, String verificationNotPending,
                        String legacyConflict,
                        String databaseError, String status, String menu) {
            this.bindSuccess = bindSuccess;
            this.qqAlreadyBound = qqAlreadyBound;
            this.playerAlreadyBound = playerAlreadyBound;
            this.invalidPlayerName = invalidPlayerName;
            this.myBinding = myBinding;
            this.notBound = notBound;
            this.otherBinding = otherBinding;
            this.unbindSuccess = unbindSuccess;
            this.notAdmin = notAdmin;
            this.invalidQq = invalidQq;
            this.commandUsage = commandUsage;
            this.verificationNotPending = verificationNotPending;
            this.legacyConflict = legacyConflict;
            this.databaseError = databaseError;
            this.status = status;
            this.menu = menu;
        }

        public String getBindSuccess() { return bindSuccess; }
        public String getQqAlreadyBound() { return qqAlreadyBound; }
        public String getPlayerAlreadyBound() { return playerAlreadyBound; }
        public String getInvalidPlayerName() { return invalidPlayerName; }
        public String getMyBinding() { return myBinding; }
        public String getNotBound() { return notBound; }
        public String getOtherBinding() { return otherBinding; }
        public String getUnbindSuccess() { return unbindSuccess; }
        public String getNotAdmin() { return notAdmin; }
        public String getInvalidQq() { return invalidQq; }
        public String getCommandUsage() { return commandUsage; }
        public String getVerificationNotPending() { return verificationNotPending; }
        public String getLegacyConflict() { return legacyConflict; }
        public String getDatabaseError() { return databaseError; }
        public String getStatus() { return status; }
        public String getMenu() { return menu; }
    }

    public static final class GroupEvents {
        private final Welcome welcome;
        private final boolean leaveUnbindEnabled;
        private final Verification verification;
        private final GroupCard groupCard;

        public GroupEvents(Welcome welcome, boolean leaveUnbindEnabled,
                           Verification verification, GroupCard groupCard) {
            this.welcome = welcome;
            this.leaveUnbindEnabled = leaveUnbindEnabled;
            this.verification = verification;
            this.groupCard = groupCard;
        }

        public Welcome getWelcome() { return welcome; }
        public boolean isLeaveUnbindEnabled() { return leaveUnbindEnabled; }
        public Verification getVerification() { return verification; }
        public GroupCard getGroupCard() { return groupCard; }
    }

    public static final class Welcome {
        private final boolean enabled;
        private final String message;

        public Welcome(boolean enabled, String message) {
            this.enabled = enabled;
            this.message = message;
        }

        public boolean isEnabled() { return enabled; }
        public String getMessage() { return message; }
    }

    public static final class Verification {
        private final VerificationMode mode;
        private final int expiresMinutes;
        private final int maxAttempts;
        private final int randomCodeLength;
        private final String fixedCode;
        private final boolean kickOnExpire;
        private final String prompt;
        private final String success;
        private final String failed;
        private final String expired;

        public Verification(VerificationMode mode, int expiresMinutes, int maxAttempts,
                            int randomCodeLength, String fixedCode, boolean kickOnExpire,
                            String prompt, String success,
                            String failed, String expired) {
            this.mode = mode;
            this.expiresMinutes = expiresMinutes;
            this.maxAttempts = maxAttempts;
            this.randomCodeLength = randomCodeLength;
            this.fixedCode = fixedCode;
            this.kickOnExpire = kickOnExpire;
            this.prompt = prompt;
            this.success = success;
            this.failed = failed;
            this.expired = expired;
        }

        public VerificationMode getMode() { return mode; }
        public int getExpiresMinutes() { return expiresMinutes; }
        public int getMaxAttempts() { return maxAttempts; }
        public int getRandomCodeLength() { return randomCodeLength; }
        public String getFixedCode() { return fixedCode; }
        public boolean isKickOnExpire() { return kickOnExpire; }
        public String getPrompt() { return prompt; }
        public String getSuccess() { return success; }
        public String getFailed() { return failed; }
        public String getExpired() { return expired; }
    }

    public static final class GroupCard {
        private final boolean enabled;
        private final String format;

        public GroupCard(boolean enabled, String format) {
            this.enabled = enabled;
            this.format = format;
        }

        public boolean isEnabled() { return enabled; }
        public String getFormat() { return format; }
    }

    public static final class CompanionApi {
        private final int protocolVersion;
        private final String channel;

        public CompanionApi(int protocolVersion, String channel) {
            this.protocolVersion = protocolVersion;
            this.channel = channel;
        }

        public int getProtocolVersion() { return protocolVersion; }
        public String getChannel() { return channel; }
    }
}
