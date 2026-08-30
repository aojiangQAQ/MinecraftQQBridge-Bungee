package h_aaa.mcqqbridge.domain;

import java.util.Locale;

public enum VerificationMode {
    OFF,
    FIXED,
    RANDOM;

    public static VerificationMode parse(String value) {
        if (value == null) {
            return OFF;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
