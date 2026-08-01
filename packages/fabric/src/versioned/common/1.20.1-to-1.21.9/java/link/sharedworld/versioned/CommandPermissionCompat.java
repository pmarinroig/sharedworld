package link.sharedworld.versioned;

import net.minecraft.commands.CommandSourceStack;

/**
 * Version-specific admin-permission check for command {@code .requires()} gates,
 * for Minecraft versions where permissions are integer op levels.
 */
public final class CommandPermissionCompat {
    private CommandPermissionCompat() {
    }

    public static boolean hasAdminCommandPermission(CommandSourceStack source) {
        return source.hasPermission(3);
    }
}
