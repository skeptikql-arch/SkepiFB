package me.skepi.skepifb.hotbar;

public enum HotbarAction {
    NONE("none"),
    BLOCK("block"),
    PRACTICE_BLOCK("practice_block"),
    TOOL("tool"),
    RESPAWN("respawn"),
    ISLAND_MENU("island_menu"),
    REPLAYS_MENU("replays_menu"),
    SETTINGS_MENU("settings_menu"),
    LEAVE("leave");

    private final String configValue;

    HotbarAction(String configValue) {
        this.configValue = configValue;
    }

    public String getConfigValue() {
        return configValue;
    }

    public static HotbarAction fromString(String value) {
        if (value == null) {
            return NONE;
        }
        for (HotbarAction action : values()) {
            if (action.configValue.equalsIgnoreCase(value)) {
                return action;
            }
        }
        return NONE;
    }
}
