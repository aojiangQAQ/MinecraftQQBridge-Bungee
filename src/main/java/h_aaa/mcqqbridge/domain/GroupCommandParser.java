package h_aaa.mcqqbridge.domain;

import h_aaa.mcqqbridge.config.PluginConfig;

public final class GroupCommandParser {
    private final PluginConfig.Commands commands;

    public GroupCommandParser(PluginConfig.Commands commands) {
        this.commands = commands;
    }

    public ParsedCommand parse(String input) {
        String text = input == null ? "" : input.trim();
        if (text.equals(commands.getMyBinding().trim())) {
            return new ParsedCommand(CommandType.MY_BINDING, "");
        }
        if (text.equals(commands.getStatus().trim())) {
            return new ParsedCommand(CommandType.STATUS, "");
        }
        if (text.equals(commands.getMenu().trim())) {
            return new ParsedCommand(CommandType.MENU, "");
        }
        ParsedCommand parsed = prefix(text, commands.getBind(), CommandType.BIND);
        if (parsed != null) return parsed;
        parsed = prefix(text, commands.getQueryOther(), CommandType.QUERY_OTHER);
        if (parsed != null) return parsed;
        parsed = prefix(text, commands.getUnbindPlayer(), CommandType.UNBIND_PLAYER);
        if (parsed != null) return parsed;
        parsed = prefix(text, commands.getUnbindQq(), CommandType.UNBIND_QQ);
        if (parsed != null) return parsed;
        parsed = prefix(text, commands.getVerify(), CommandType.VERIFY);
        if (parsed != null) return parsed;
        return new ParsedCommand(CommandType.UNKNOWN, "");
    }

    private static ParsedCommand prefix(String text, String configured, CommandType type) {
        String prefix = configured.trim();
        if (!text.startsWith(prefix)) {
            return null;
        }
        if (text.length() == prefix.length()) {
            return new ParsedCommand(type, "");
        }
        char separator = text.charAt(prefix.length());
        if (!Character.isWhitespace(separator)) {
            return null;
        }
        return new ParsedCommand(type, text.substring(prefix.length()).trim());
    }
}
