package org.cubexmc.update;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigUpdaterTest {

    @TempDir
    Path tempDir;

    @Test
    void mergeKeepsRemovedGemAndPowerDefaultsRemoved() throws Exception {
        JavaPlugin plugin = plugin();
        write("config.yml", "language: zh_CN\n");
        write("features/appoint.yml", "enabled: true\n");
        write("features/navigate.yml", "enabled: true\n");
        write("gems/gems.yml", "custom:\n  count: 1\n");
        write("powers/powers.yml", "custom_power:\n  permissions:\n    - custom.node\n");

        ConfigUpdater.merge(plugin);

        YamlConfiguration config = load("config.yml");
        assertTrue(config.getBoolean("added_config_default"));

        YamlConfiguration gems = load("gems/gems.yml");
        assertTrue(gems.contains("custom"));
        assertFalse(gems.contains("default_gem"));

        YamlConfiguration powers = load("powers/powers.yml");
        assertTrue(powers.contains("custom_power"));
        assertFalse(powers.contains("default_power"));
    }

    @Test
    void mergeDoesNotCreateMissingDefinitionFiles() throws Exception {
        JavaPlugin plugin = plugin();
        write("config.yml", "language: zh_CN\n");
        write("features/appoint.yml", "enabled: true\n");
        write("features/navigate.yml", "enabled: true\n");

        ConfigUpdater.merge(plugin);

        assertFalse(Files.exists(tempDir.resolve("gems/gems.yml")));
        assertFalse(Files.exists(tempDir.resolve("powers/powers.yml")));
    }

    private JavaPlugin plugin() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ConfigUpdaterTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            String content = resources().get(path);
            return content == null ? null
                    : new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        });
        doAnswer(invocation -> {
            String path = invocation.getArgument(0);
            String content = resources().get(path);
            if (content == null) {
                return null;
            }
            write(path, content);
            return null;
        }).when(plugin).saveResource(anyString(), eq(false));
        return plugin;
    }

    private Map<String, String> resources() {
        return Map.of(
                "config.yml", "language: en_US\nadded_config_default: true\n",
                "features/appoint.yml", "enabled: false\nadded_appoint_default: true\n",
                "features/navigate.yml", "enabled: false\nadded_navigate_default: true\n",
                "gems/gems.yml", "default_gem:\n  count: 1\n",
                "powers/powers.yml", "default_power:\n  permissions:\n    - default.node\n");
    }

    private void write(String relativePath, String content) throws Exception {
        Path target = tempDir.resolve(relativePath);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private YamlConfiguration load(String relativePath) {
        return YamlConfiguration.loadConfiguration(new File(tempDir.toFile(), relativePath));
    }
}
