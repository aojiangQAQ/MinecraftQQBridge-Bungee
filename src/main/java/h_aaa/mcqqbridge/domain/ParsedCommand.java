package h_aaa.mcqqbridge.domain;

public final class ParsedCommand {
    private final CommandType type;
    private final String argument;

    public ParsedCommand(CommandType type, String argument) {
        this.type = type;
        this.argument = argument == null ? "" : argument;
    }

    public CommandType getType() { return type; }
    public String getArgument() { return argument; }
}
