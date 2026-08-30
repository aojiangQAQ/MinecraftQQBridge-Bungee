package h_aaa.mcqqbridge.service;

public final class AccessDecision {
    public enum Type {
        ALLOW_BOUND,
        DENY_UNBOUND,
        DENY_INVALID_NAME,
        DENY_STORAGE_ERROR
    }

    private final Type type;

    private AccessDecision(Type type) {
        this.type = type;
    }

    public static AccessDecision allowBound() {
        return new AccessDecision(Type.ALLOW_BOUND);
    }

    public static AccessDecision deny(Type type) {
        if (type == Type.ALLOW_BOUND) {
            throw new IllegalArgumentException("Use allowBound for an allow decision");
        }
        return new AccessDecision(type);
    }

    public Type getType() {
        return type;
    }

    public boolean isAllowed() {
        return type == Type.ALLOW_BOUND;
    }
}
