package h_aaa.mcqqbridge.domain;

import java.util.Locale;
import java.util.regex.Pattern;

public final class MinecraftName {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern LEGACY_VALID = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final String value;
    private final String normalized;

    private MinecraftName(String value) {
        this.value = value;
        this.normalized = value.toLowerCase(Locale.ROOT);
    }

    public static MinecraftName parse(String input) {
        String value = input == null ? "" : input;
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Minecraft name");
        }
        return new MinecraftName(value);
    }

    public static MinecraftName parseLegacy(String input) {
        String value = input == null ? "" : input.trim();
        if (!LEGACY_VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid legacy Minecraft name");
        }
        return new MinecraftName(value);
    }

    public static String normalize(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT);
    }

    public String getValue() {
        return value;
    }

    public String getNormalized() {
        return normalized;
    }

    @Override
    public String toString() {
        return value;
    }
}
