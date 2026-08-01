package link.sharedworld.versioned;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permissions;

/**
 * Version-specific admin-permission check for command {@code .requires()} gates,
 * for Minecraft versions where permissions are permission-set atoms.
 */
public final class CommandPermissionCompat {
    private CommandPermissionCompat() {
    }

    public static boolean hasAdminCommandPermission(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
    }
}
