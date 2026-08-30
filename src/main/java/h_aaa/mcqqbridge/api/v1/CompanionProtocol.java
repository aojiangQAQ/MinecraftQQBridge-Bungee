package h_aaa.mcqqbridge.api.v1;

public final class CompanionProtocol {
    public static final int MAJOR_VERSION = 1;
    public static final int MINOR_VERSION = 0;
    public static final String DEFAULT_CHANNEL = "MCQQB:Bridge";
    public static final int MAX_PAYLOAD_BYTES = 24 * 1024;

    private CompanionProtocol() {
    }

    public enum MessageType {
        HELLO,
        WELCOME,
        REJECT,
        IDENTITY_QUERY,
        IDENTITY_RESPONSE,
        EVENT,
        ERROR
    }
}
