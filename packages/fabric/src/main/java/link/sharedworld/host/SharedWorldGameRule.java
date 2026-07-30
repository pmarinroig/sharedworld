package link.sharedworld.host;

/**
 * The curated gamerules SharedWorld exposes in the settings UI, keyed by the
 * backend's version-independent ids. Each Minecraft version bucket maps these
 * onto its own gamerule API in ServerSettingsCompat (the rule constants,
 * packages, and even pvp's mechanism drift across versions).
 */
public enum SharedWorldGameRule {
    KEEP_INVENTORY("keepInventory"),
    MOB_GRIEFING("mobGriefing"),
    DAYLIGHT_CYCLE("daylightCycle"),
    WEATHER_CYCLE("weatherCycle"),
    PVP("pvp");

    private final String id;

    SharedWorldGameRule(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public static SharedWorldGameRule byId(String id) {
        for (SharedWorldGameRule rule : values()) {
            if (rule.id.equals(id)) {
                return rule;
            }
        }
        return null;
    }
}
