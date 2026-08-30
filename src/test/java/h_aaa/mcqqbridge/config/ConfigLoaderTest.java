package h_aaa.mcqqbridge.config;

import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigLoaderTest {
    @TempDir
    Path tempDirectory;

    @Test
    void firstLoadCopiesBundledConfiguration() throws Exception {
        Path destination = tempDirectory.resolve("config.yml");
        try (InputStream input = Files.newInputStream(Paths.get("src/main/resources/config.yml"))) {
            ConfigLoader.copyDefaultConfig(input, destination);
        }

        assertTrue(Files.isRegularFile(destination));
        try (InputStream input = Files.newInputStream(destination)) {
            assertTrue(input.read() >= 0);
        }
    }

    @Test
    void parsesNumericYamlValuesWithoutStringCoercion() throws Exception {
        Configuration root = loadDefault();
        PluginConfig config = new ConfigLoader(null).parse(
                root, Paths.get("E:/MinecraftQQBridge-Bungee/test-config"));

        assertEquals(5000, config.getOneBot().getConnectTimeoutMillis());
        assertEquals(2000, config.getDatabase().getBusyTimeoutMillis());
        assertEquals(6, config.getGroupEvents().getVerification().getRandomCodeLength());
    }

    @Test
    void enabledOneBotRequiresStrongToken() throws Exception {
        Configuration root = loadDefault();
        root.set("onebot.enabled", true);
        root.set("onebot.token", "short-token");

        assertThrows(ConfigException.class, () -> new ConfigLoader(null).parse(
                root, Paths.get("E:/MinecraftQQBridge-Bungee/test-config")));
    }

    @Test
    void enabledOneBotRejectsWhitespaceOrControlCharactersInToken() throws Exception {
        Configuration root = loadDefault();
        root.set("onebot.enabled", true);
        root.set("onebot.token", "0123456789012345678901234567890\nX");

        assertThrows(ConfigException.class, () -> new ConfigLoader(null).parse(
                root, Paths.get("E:/MinecraftQQBridge-Bungee/test-config")));
    }

    @Test
    void refusesNonLoopbackOneBotEndpoint() throws Exception {
        Configuration root = loadDefault();
        root.set("onebot.url", "ws://192.0.2.10:3001/");

        assertThrows(ConfigException.class, () -> new ConfigLoader(null).parse(
                root, Paths.get("E:/MinecraftQQBridge-Bungee/test-config")));
    }

    @Test
    void refusesAmbiguousLoopbackEndpoint() throws Exception {
        Configuration root = loadDefault();
        root.set("onebot.url", "ws://localhost:3001/?token=secret");

        assertThrows(ConfigException.class, () -> new ConfigLoader(null).parse(
                root, Paths.get("E:/MinecraftQQBridge-Bungee/test-config")));
    }

    private static Configuration loadDefault() throws Exception {
        return ConfigurationProvider.getProvider(YamlConfiguration.class).load(
                Paths.get("src/main/resources/config.yml").toFile());
    }
}
