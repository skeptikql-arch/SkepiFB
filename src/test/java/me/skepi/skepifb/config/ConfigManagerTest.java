package me.skepi.skepifb.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigManagerTest {

    @Test
    void normalizeConfigKeyTrimsAndLowercasesValues() {
        assertEquals("my_arena", ConfigManager.normalizeConfigKey(" My Arena "));
        assertEquals("default", ConfigManager.normalizeConfigKey("DEFAULT"));
    }
}
