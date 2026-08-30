package h_aaa.mcqqbridge.onebot;

public final class OneBotActionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String action;
    private final String status;
    private final int retcode;

    OneBotActionException(String action, String status, int retcode, String detail) {
        super(buildMessage(action, status, retcode, detail));
        this.action = action;
        this.status = status;
        this.retcode = retcode;
    }

    public String getAction() {
        return action;
    }

    public String getStatus() {
        return status;
    }

    public int getRetcode() {
        return retcode;
    }

    private static String buildMessage(String action, String status, int retcode, String detail) {
        StringBuilder message = new StringBuilder("OneBot action failed: action=")
                .append(action).append(", status=").append(status)
                .append(", retcode=").append(retcode);
        if (detail != null && !detail.isEmpty()) {
            message.append(", detail=").append(detail);
        }
        return message.toString();
    }
}
