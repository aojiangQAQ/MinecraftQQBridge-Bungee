package h_aaa.mcqqbridge.storage;

public final class LegacyRowDisposition {
    public enum Outcome {
        IMPORTED,
        ALREADY_PRESENT,
        DUPLICATE_IDENTICAL,
        CONFLICT_PLAYER,
        CONFLICT_QQ,
        CONFLICT_BOTH,
        INVALID_PLAYER_NAME,
        INVALID_QQ_ID
    }

    private final long legacyRowId;
    private final String rawPlayerName;
    private final String rawQqUserId;
    private final String normalizedPlayerName;
    private final Outcome outcome;
    private final String detail;

    public LegacyRowDisposition(long legacyRowId, String rawPlayerName, String rawQqUserId,
                                String normalizedPlayerName, Outcome outcome, String detail) {
        this.legacyRowId = legacyRowId;
        this.rawPlayerName = rawPlayerName;
        this.rawQqUserId = rawQqUserId;
        this.normalizedPlayerName = normalizedPlayerName;
        this.outcome = outcome;
        this.detail = detail == null ? "" : detail;
    }

    public long getLegacyRowId() { return legacyRowId; }
    public String getRawPlayerName() { return rawPlayerName; }
    public String getRawQqUserId() { return rawQqUserId; }
    public String getNormalizedPlayerName() { return normalizedPlayerName; }
    public Outcome getOutcome() { return outcome; }
    public String getDetail() { return detail; }
}
