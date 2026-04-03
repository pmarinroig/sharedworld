package link.sharedworld;

import link.sharedworld.api.SharedWorldApiClient;
import net.minecraft.network.chat.Component;

public final class SharedWorldText {
    private SharedWorldText() {
    }

    public static Component component(String key, Object... args) {
        return Component.translatable(key, args);
    }

    public static String string(String key, Object... args) {
        return component(key, args).getString();
    }

    public static String defaultMotd() {
        return string("screen.sharedworld.motd_hint", SharedWorldApiClient.currentPlayerName());
    }

    public static String displayWorldName(String worldName) {
        return worldName == null || worldName.isBlank()
                ? string("screen.sharedworld.unnamed_world")
                : worldName;
    }

    public static String errorMessageOrDefault(String message) {
        return message == null || message.isBlank()
                ? string("screen.sharedworld.error_generic")
                : message;
    }

    public static Component playerCount(int current, int max) {
        return component("screen.sharedworld.player_count", current, max);
    }
}
