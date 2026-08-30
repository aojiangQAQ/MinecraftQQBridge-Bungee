package h_aaa.mcqqbridge.domain;

import java.util.Collections;
import java.util.Map;

public final class MessageTemplates {
    private MessageTemplates() {
    }

    public static String render(String template, Map<String, String> values) {
        String result = template == null ? "" : template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String replacement = entry.getValue() == null ? "" : entry.getValue();
            result = result.replace("%" + entry.getKey() + "%", replacement);
        }
        return result;
    }

    public static String render(String template, String key, String value) {
        return render(template, Collections.singletonMap(key, value));
    }

    public static String mention(String qqUserId) {
        return "[CQ:at,qq=" + qqUserId + "]";
    }
}
