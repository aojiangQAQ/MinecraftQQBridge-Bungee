package h_aaa.mcqqbridge.storage;

public final class VerificationChallenge {
    public enum State {
        PENDING,
        VERIFIED,
        EXPIRED,
        CANCELLED,
        EXHAUSTED
    }

    private final String challengeId;
    private final String groupId;
    private final String qqUserId;
    private final State state;
    private final long createdAtEpochMillis;
    private final long expiresAtEpochMillis;
    private final int attempts;
    private final int maxAttempts;

    public VerificationChallenge(String challengeId, String groupId, String qqUserId,
                                 State state, long createdAtEpochMillis,
                                 long expiresAtEpochMillis, int attempts, int maxAttempts) {
        this.challengeId = challengeId;
        this.groupId = groupId;
        this.qqUserId = qqUserId;
        this.state = state;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.expiresAtEpochMillis = expiresAtEpochMillis;
        this.attempts = attempts;
        this.maxAttempts = maxAttempts;
    }

    public String getChallengeId() { return challengeId; }
    public String getGroupId() { return groupId; }
    public String getQqUserId() { return qqUserId; }
    public State getState() { return state; }
    public long getCreatedAtEpochMillis() { return createdAtEpochMillis; }
    public long getExpiresAtEpochMillis() { return expiresAtEpochMillis; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public int getRemainingAttempts() { return Math.max(0, maxAttempts - attempts); }
}
