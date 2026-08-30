package h_aaa.mcqqbridge.domain;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinecraftNameTest {
    @Test
    void parsesAndNormalizesUsingRootLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            MinecraftName name = MinecraftName.parse("I_Player");
            assertEquals("I_Player", name.getValue());
            assertEquals("i_player", name.getNormalized());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void rejectsWhitespaceUnicodeAndInvalidLengths() {
        assertThrows(IllegalArgumentException.class, () -> MinecraftName.parse(" Steve"));
        assertThrows(IllegalArgumentException.class, () -> MinecraftName.parse("Steve "));
        assertThrows(IllegalArgumentException.class, () -> MinecraftName.parse("玩家123"));
        assertThrows(IllegalArgumentException.class, () -> MinecraftName.parse("ab"));
        assertThrows(IllegalArgumentException.class, () -> MinecraftName.parse("abcdefghijklmnopq"));
    }

    @Test
    void legacyParserAllowsOneCharacterAndTrimsRecordedInput() {
        assertEquals("A", MinecraftName.parseLegacy(" A ").getValue());
    }
}
