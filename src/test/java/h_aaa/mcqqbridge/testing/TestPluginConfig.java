package h_aaa.mcqqbridge.testing;

import h_aaa.mcqqbridge.config.PluginConfig;
import h_aaa.mcqqbridge.domain.VerificationMode;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

public final class TestPluginConfig {
    private TestPluginConfig() {
    }

    public static PluginConfig create(Path directory, Set<String> administrators,
                                      VerificationMode verificationMode) {
        PluginConfig.OneBot oneBot = new PluginConfig.OneBot(
                false, "ws://127.0.0.1:3001/", "", 500, 500, 5000, 1, 2);
        PluginConfig.AccessControl access = new PluginConfig.AccessControl(
                true, 500, "UNBOUND", "DB_ERROR", "REVOKED");
        PluginConfig.Database database = new PluginConfig.Database(
                directory.resolve("bridge.db"), 1000, 64);
        PluginConfig.Migration migration = new PluginConfig.Migration(
                false, Collections.<Path>emptyList(), directory.resolve("backups"));
        PluginConfig.Commands commands = new PluginConfig.Commands(
                "绑定 ", "我的绑定", "他人绑定 ", "删除ID ", "删除QQ ",
                "服务器状态", "菜单", "验证 ", true, true);
        PluginConfig.Messages messages = new PluginConfig.Messages(
                "%at% BOUND %player%", "%at% QQ_BOUND %player%", "%at% PLAYER_BOUND",
                "%at% INVALID_PLAYER", "%at% MINE %player%", "%at% NOT_BOUND",
                "%at% OTHER %qq% %player%", "UNBOUND %qq% %player%", "NO_PERMISSION",
                "%at% INVALID_QQ", "%at% USAGE", "%at% NO_VERIFICATION",
                "%at% LEGACY_CONFLICT", "DATABASE_ERROR",
                "STATUS %online% %onebot% %database%", "MENU");
        PluginConfig.Verification verification = new PluginConfig.Verification(
                verificationMode, 5, 3, 6, "FIXED-CODE", true,
                "%at% VERIFY %code%", "%at% VERIFIED", "%at% WRONG %remaining%",
                "%at% EXPIRED");
        PluginConfig.GroupEvents events = new PluginConfig.GroupEvents(
                new PluginConfig.Welcome(false, "%at% WELCOME"), true, verification,
                new PluginConfig.GroupCard(false, "%player%"));
        return new PluginConfig(
                oneBot,
                Collections.singleton("123456"),
                administrators,
                false,
                access,
                database,
                migration,
                commands,
                messages,
                events,
                new PluginConfig.CompanionApi(1, "MCQQB:Bridge"));
    }
}
