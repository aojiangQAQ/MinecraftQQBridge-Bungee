package h_aaa.mcqqbridge.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QqIdentifier {
    private static final Pattern PLAIN = Pattern.compile("[0-9]{5,20}");
    private static final Pattern CQ_AT = Pattern.compile(
            "\\[CQ:at,qq=([0-9]{5,20})(?:,[^\\]]*)?\\]");

    private QqIdentifier() {
    }

    public static String parse(String input) {
        String value = input == null ? "" : input.trim();
        if (PLAIN.matcher(value).matches()) {
            return value;
        }
        Matcher matcher = CQ_AT.matcher(value);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IllegalArgumentException("Invalid QQ identifier");
    }

    public static boolean isValid(String input) {
        try {
            parse(input);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
