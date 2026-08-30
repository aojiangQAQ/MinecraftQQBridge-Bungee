package h_aaa.mcqqbridge.storage;

public final class StorageException extends Exception {
    public enum Kind {
        UNAVAILABLE,
        BUSY,
        CORRUPT,
        SCHEMA,
        IO,
        CONSTRAINT,
        QUEUE_FULL,
        CLOSED
    }

    private final Kind kind;

    public StorageException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public StorageException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
