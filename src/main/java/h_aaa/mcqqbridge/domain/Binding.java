package h_aaa.mcqqbridge.domain;

public final class Binding {
    private final String qqUserId;
    private final String playerName;
    private final String normalizedPlayerName;
    private final long createdAtEpochMillis;
    private final String source;

    public Binding(String qqUserId, String playerName, String normalizedPlayerName,
                   long createdAtEpochMillis, String source) {
        this.qqUserId = qqUserId;
        this.playerName = playerName;
        this.normalizedPlayerName = normalizedPlayerName;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.source = source;
    }

    public String getQqUserId() { return qqUserId; }
    public String getPlayerName() { return playerName; }
    public String getNormalizedPlayerName() { return normalizedPlayerName; }
    public long getCreatedAtEpochMillis() { return createdAtEpochMillis; }
    public String getSource() { return source; }
}
