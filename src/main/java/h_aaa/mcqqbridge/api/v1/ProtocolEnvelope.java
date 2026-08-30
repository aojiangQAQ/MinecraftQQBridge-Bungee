package h_aaa.mcqqbridge.api.v1;

public final class ProtocolEnvelope {
    private final int protocolMajor;
    private final int protocolMinor;
    private final String requestId;
    private final String serverId;
    private final long issuedAtEpochMillis;
    private final String nonce;
    private final CompanionProtocol.MessageType type;
    private final String payloadJson;

    public ProtocolEnvelope(int protocolMajor, int protocolMinor, String requestId,
                            String serverId, long issuedAtEpochMillis, String nonce,
                            CompanionProtocol.MessageType type, String payloadJson) {
        this.protocolMajor = protocolMajor;
        this.protocolMinor = protocolMinor;
        this.requestId = requestId;
        this.serverId = serverId;
        this.issuedAtEpochMillis = issuedAtEpochMillis;
        this.nonce = nonce;
        this.type = type;
        this.payloadJson = payloadJson;
    }

    public int getProtocolMajor() { return protocolMajor; }
    public int getProtocolMinor() { return protocolMinor; }
    public String getRequestId() { return requestId; }
    public String getServerId() { return serverId; }
    public long getIssuedAtEpochMillis() { return issuedAtEpochMillis; }
    public String getNonce() { return nonce; }
    public CompanionProtocol.MessageType getType() { return type; }
    public String getPayloadJson() { return payloadJson; }
}
