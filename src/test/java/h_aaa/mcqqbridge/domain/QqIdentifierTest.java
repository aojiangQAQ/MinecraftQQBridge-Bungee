package h_aaa.mcqqbridge.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QqIdentifierTest {
    @Test
    void parsesPlainAndStandardCqAtIdentifiers() {
        assertEquals("123456", QqIdentifier.parse("123456"));
        assertEquals("123456", QqIdentifier.parse("[CQ:at,qq=123456]"));
        assertEquals("123456", QqIdentifier.parse("target [CQ:at,qq=123456,name=test]"));
    }

    @Test
    void rejectsAllAndMalformedIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> QqIdentifier.parse("[CQ:at,qq=all]"));
        assertThrows(IllegalArgumentException.class, () -> QqIdentifier.parse("1234"));
        assertThrows(IllegalArgumentException.class, () -> QqIdentifier.parse("12345 OR 1=1"));
    }
}
