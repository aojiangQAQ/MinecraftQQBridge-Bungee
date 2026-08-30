package h_aaa.mcqqbridge.domain;

import h_aaa.mcqqbridge.config.PluginConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroupCommandParserTest {
    private final GroupCommandParser parser = new GroupCommandParser(new PluginConfig.Commands(
            "绑定 ", "我的绑定", "他人绑定 ", "删除ID ", "删除QQ ",
            "服务器状态", "菜单", "验证 ", true, true));

    @Test
    void parsesExactAndArgumentCommands() {
        assertEquals(CommandType.MY_BINDING, parser.parse(" 我的绑定 ").getType());
        ParsedCommand bind = parser.parse("绑定 Steve_01");
        assertEquals(CommandType.BIND, bind.getType());
        assertEquals("Steve_01", bind.getArgument());
    }

    @Test
    void doesNotAcceptPrefixCollisions() {
        assertEquals(CommandType.UNKNOWN, parser.parse("绑定Steve").getType());
        assertEquals(CommandType.UNKNOWN, parser.parse("菜单更多").getType());
    }
}
