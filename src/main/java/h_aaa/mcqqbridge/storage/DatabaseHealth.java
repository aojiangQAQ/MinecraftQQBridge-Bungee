package h_aaa.mcqqbridge.storage;

public final class DatabaseHealth {
    public enum Status {
        NEW,
        INITIALIZING,
        HEALTHY,
        UNHEALTHY,
        CLOSED
    }

    private final Status status;
    private final String detail;
    private final StorageException.Kind failureKind;
    private final long changedAtEpochMillis;

    public DatabaseHealth(Status status, String detail, StorageException.Kind failureKind,
                          long changedAtEpochMillis) {
        this.status = status;
        this.detail = detail == null ? "" : detail;
        this.failureKind = failureKind;
        this.changedAtEpochMillis = changedAtEpochMillis;
    }

    public Status getStatus() { return status; }
    public String getDetail() { return detail; }
    public StorageException.Kind getFailureKind() { return failureKind; }
    public long getChangedAtEpochMillis() { return changedAtEpochMillis; }
    public boolean isHealthy() { return status == Status.HEALTHY; }
}
