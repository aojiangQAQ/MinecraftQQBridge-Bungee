package h_aaa.mcqqbridge.storage;

public final class VerificationAttemptResult {
    public enum Status {
        VERIFIED,
        INVALID_CODE,
        EXPIRED,
        MAX_ATTEMPTS,
        NOT_FOUND
    }

    private final Status status;
    private final VerificationChallenge challenge;

    public VerificationAttemptResult(Status status, VerificationChallenge challenge) {
        this.status = status;
        this.challenge = challenge;
    }

    public Status getStatus() { return status; }
    public VerificationChallenge getChallenge() { return challenge; }
}
