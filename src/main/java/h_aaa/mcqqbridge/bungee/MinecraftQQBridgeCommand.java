package h_aaa.mcqqbridge.bungee;

import h_aaa.mcqqbridge.MinecraftQQBridgePlugin;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;

import java.util.Locale;

public final class MinecraftQQBridgeCommand extends Command {
    private final MinecraftQQBridgePlugin plugin;

    public MinecraftQQBridgeCommand(MinecraftQQBridgePlugin plugin) {
        super("mcqqbridge", null, "mcqq");
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        String subcommand = args.length == 0
                ? "status"
                : args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "status":
                if (!allowed(sender, "mcqqbridge.command.status")) {
                    deny(sender);
                    return;
                }
                for (String line : plugin.statusLines()) {
                    sender.sendMessage(line);
                }
                break;
            case "reconnect":
                if (!allowed(sender, "mcqqbridge.command.reconnect")) {
                    deny(sender);
                    return;
                }
                sender.sendMessage(plugin.reconnectOneBot());
                break;
            case "dbcheck":
                if (!allowed(sender, "mcqqbridge.database.check")) {
                    deny(sender);
                    return;
                }
                plugin.checkDatabase(sender);
                break;
            case "migration":
                if (!allowed(sender, "mcqqbridge.migration.view")) {
                    deny(sender);
                    return;
                }
                for (String line : plugin.migrationLines()) {
                    sender.sendMessage(line);
                }
                break;
            default:
                sender.sendMessage("用法: /mcqqbridge status|reconnect|dbcheck|migration");
                break;
        }
    }

    private boolean allowed(CommandSender sender, String permission) {
        return sender == plugin.getProxy().getConsole()
                || sender.hasPermission("mcqqbridge.admin")
                || sender.hasPermission(permission);
    }

    private static void deny(CommandSender sender) {
        sender.sendMessage("您没有执行此命令的权限。");
    }
}
