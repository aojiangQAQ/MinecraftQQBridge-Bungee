package h_aaa.mcqqbridge.onebot;

import com.google.gson.JsonObject;

@FunctionalInterface
public interface OneBotEventListener {
    void onEvent(String rawJson, JsonObject event);
}
