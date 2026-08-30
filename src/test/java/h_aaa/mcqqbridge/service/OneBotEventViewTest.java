package h_aaa.mcqqbridge.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneBotEventViewTest {
    @Test
    void readsStructuredRoleAndUsesDepartingUserId() {
        String raw = "{\"post_type\":\"notice\",\"notice_type\":\"group_decrease\","
                + "\"sub_type\":\"kick\",\"group_id\":123456,\"user_id\":234567,"
                + "\"operator_id\":345678,\"sender\":{\"role\":\"owner\"}}";
        JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
        OneBotEventView event = new OneBotEventView(raw, json);

        assertTrue(event.isNotice("group_decrease"));
        assertEquals("234567", event.getUserId());
        assertEquals("345678", event.getOperatorId());
        assertEquals("owner", event.getSenderRole());
        assertFalse(event.eventKey().isEmpty());
    }

    @Test
    void commandTextCannotForgeSenderRole() {
        String raw = "{\"post_type\":\"message\",\"message_type\":\"group\","
                + "\"group_id\":123456,\"user_id\":234567,"
                + "\"raw_message\":\"服务器状态 owner admin\","
                + "\"sender\":{\"role\":\"member\"}}";
        OneBotEventView event = new OneBotEventView(
                raw, JsonParser.parseString(raw).getAsJsonObject());

        assertTrue(event.isGroupMessage());
        assertEquals("member", event.getSenderRole());
    }
}
