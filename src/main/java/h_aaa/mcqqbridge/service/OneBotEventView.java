package h_aaa.mcqqbridge.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import h_aaa.mcqqbridge.util.CryptoUtil;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class OneBotEventView {
    private final JsonObject payload;
    private final String rawJson;

    public OneBotEventView(String rawJson, JsonObject payload) {
        this.rawJson = rawJson == null ? "" : rawJson;
        this.payload = payload;
    }

    public boolean isGroupMessage() {
        return "message".equals(value("post_type"))
                && "group".equals(value("message_type"));
    }

    public boolean isNotice(String noticeType) {
        return "notice".equals(value("post_type"))
                && noticeType.equals(value("notice_type"));
    }

    public String getGroupId() {
        return value("group_id");
    }

    public String getUserId() {
        return value("user_id");
    }

    public String getOperatorId() {
        return value("operator_id");
    }

    public String getSelfId() {
        return value("self_id");
    }

    public String getSubType() {
        return value("sub_type");
    }

    public String getRawMessage() {
        String rawMessage = value("raw_message");
        return rawMessage.isEmpty() ? value("message") : rawMessage;
    }

    public String getSenderRole() {
        JsonObject sender = object("sender");
        if (sender == null || !sender.has("role")) {
            return "member";
        }
        return string(sender.get("role")).toLowerCase(Locale.ROOT);
    }

    public String eventKey() {
        return CryptoUtil.sha256Hex(rawJson.getBytes(StandardCharsets.UTF_8));
    }

    private String value(String name) {
        return payload != null && payload.has(name) ? string(payload.get(name)) : "";
    }

    private JsonObject object(String name) {
        JsonElement element = payload == null ? null : payload.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        try {
            return element.getAsString();
        } catch (RuntimeException ignored) {
            return element.toString();
        }
    }
}
