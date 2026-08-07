package link.sharedworld.command;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import link.sharedworld.CanonicalPlayerIdentity;
import link.sharedworld.SharedWorldDevSessionBridge;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.host.MemberCommandGrant;
import link.sharedworld.host.SharedWorldHostingManager;
import link.sharedworld.versioned.CommandPermissionCompat;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * SharedWorld's in-game commands on the hosted integrated server.
 *
 * Vanilla registers /op, /deop and /ban only on dedicated servers, so these
 * literals are free on the integrated server; /kick exists there on modern
 * versions, and a fallback is registered only when the dispatcher lacks it.
 * All literals are registered unconditionally with dynamic {@code .requires()}
 * gates reading the dev-session bridge, so they are invisible outside a hosted
 * shared world (plain singleplayer included).
 */
public final class SharedWorldCommands {
    private SharedWorldCommands() {
    }

    /** Dependencies the vanilla /ban interception needs outside a command context. */
    private record BanCommandWiring(
            SharedWorldApiClient apiClient,
            Supplier<SharedWorldHostingManager> hostingManager,
            Executor ioExecutor,
            Executor clientMainThreadExecutor
    ) {
    }

    private static volatile BanCommandWiring banWiring;

    public static void register(
            SharedWorldApiClient apiClient,
            Supplier<SharedWorldHostingManager> hostingManager,
            Executor ioExecutor,
            Executor clientMainThreadExecutor
    ) {
        banWiring = new BanCommandWiring(apiClient, hostingManager, ioExecutor, clientMainThreadExecutor);
        SuggestionProvider<CommandSourceStack> memberNames = (context, builder) -> {
            for (MemberCommandGrant grant : SharedWorldDevSessionBridge.hostedMemberGrants().values()) {
                if (grant.playerName() != null && !grant.playerName().isBlank()) {
                    builder.suggest(grant.playerName());
                }
            }
            return builder.buildFuture();
        };

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            if (!environment.includeIntegrated) {
                return;
            }
            dispatcher.register(Commands.literal("op")
                    .requires(SharedWorldCommands::sourceIsHostingOwner)
                    .then(Commands.argument("player", StringArgumentType.word())
                            .suggests(memberNames)
                            .executes(context -> togglePermission(context, true, apiClient, hostingManager, ioExecutor, clientMainThreadExecutor))));
            dispatcher.register(Commands.literal("deop")
                    .requires(SharedWorldCommands::sourceIsHostingOwner)
                    .then(Commands.argument("player", StringArgumentType.word())
                            .suggests(memberNames)
                            .executes(context -> togglePermission(context, false, apiClient, hostingManager, ioExecutor, clientMainThreadExecutor))));
            dispatcher.register(Commands.literal("ban")
                    .requires(SharedWorldCommands::sourceIsHostingOwner)
                    .then(Commands.argument("player", StringArgumentType.word())
                            .suggests(memberNames)
                            .executes(context -> banMember(context, apiClient, hostingManager, ioExecutor, clientMainThreadExecutor))));
            // Vanilla added integrated-server /kick in 1.20.2; register a session-only
            // fallback where it is absent (in practice: the 1.20.1 bucket).
            if (dispatcher.getRoot().getChild("kick") == null) {
                dispatcher.register(Commands.literal("kick")
                        .requires(source -> SharedWorldDevSessionBridge.isHostingSharedWorld()
                                && CommandPermissionCompat.hasAdminCommandPermission(source))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(context -> kickPlayers(context, null, hostingManager))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> kickPlayers(context, StringArgumentType.getString(context, "reason"), hostingManager)))));
            }
        });
    }

    private static boolean sourceIsHostingOwner(CommandSourceStack source) {
        if (!SharedWorldDevSessionBridge.isHostingSharedWorld()) {
            return false;
        }
        String ownerUuid = SharedWorldDevSessionBridge.hostingSharedWorldOwnerUuid();
        ServerPlayer player = source.getPlayer();
        return player != null
                && ownerUuid != null
                && CanonicalPlayerIdentity.sameUuid(player.getUUID().toString(), ownerUuid);
    }

    private static int togglePermission(
            CommandContext<CommandSourceStack> context,
            boolean grantCommands,
            SharedWorldApiClient apiClient,
            Supplier<SharedWorldHostingManager> hostingManager,
            Executor ioExecutor,
            Executor clientMainThreadExecutor
    ) {
        CommandSourceStack source = context.getSource();
        OwnerCommandTarget target = resolveOwnerCommandTarget(
                source, StringArgumentType.getString(context, "player"), hostingManager, false);
        if (target == null) {
            return 0;
        }
        if (target.member().canUseCommands() == grantCommands) {
            source.sendFailure(Component.translatable(
                    grantCommands ? "sharedworld.command.op.already" : "sharedworld.command.deop.already",
                    target.member().playerName()));
            return 0;
        }
        MinecraftServer server = source.getServer();
        CompletableFuture.runAsync(() -> {
            try {
                apiClient.setMemberCommandPermission(target.worldId(), target.member().playerUuid(), grantCommands);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, ioExecutor).whenComplete((ignored, error) -> clientMainThreadExecutor.execute(() -> {
            if (error == null) {
                SharedWorldHostingManager manager = hostingManager.get();
                if (manager != null) {
                    manager.applyLocalMemberPermissionChange(
                            target.worldId(),
                            target.member().playerUuid(),
                            target.member().playerName(),
                            grantCommands
                    );
                }
            }
            server.execute(() -> {
                if (error != null) {
                    source.sendFailure(Component.literal(friendlyMessage(error)));
                } else {
                    source.sendSuccess(() -> Component.translatable(
                            grantCommands ? "sharedworld.command.op.success" : "sharedworld.command.deop.success",
                            target.member().playerName()), true);
                }
            });
        }));
        return 1;
    }

    private static int banMember(
            CommandContext<CommandSourceStack> context,
            SharedWorldApiClient apiClient,
            Supplier<SharedWorldHostingManager> hostingManager,
            Executor ioExecutor,
            Executor clientMainThreadExecutor
    ) {
        return executeBanByName(
                context.getSource(),
                StringArgumentType.getString(context, "player"),
                apiClient,
                hostingManager,
                ioExecutor,
                clientMainThreadExecutor
        );
    }

    /**
     * Reroute a vanilla /ban into the SharedWorld membership ban while a shared
     * world is hosted; returns true when the vanilla execution must be
     * cancelled. e4mc's "restoreDedicatedCommands" registers vanilla's ban on
     * integrated servers, where it would kick any target from the server —
     * including the hosting player, tearing the session down with no release —
     * and record it in a banned-players.json that outlives the session on
     * whichever machine happened to host. Outside a hosted shared world the
     * vanilla command keeps its e4mc-given behavior.
     */
    public static boolean interceptVanillaBan(CommandSourceStack source, java.util.Collection<?> targets) {
        if (!SharedWorldDevSessionBridge.isHostingSharedWorld()) {
            return false;
        }
        BanCommandWiring wiring = banWiring;
        if (wiring == null) {
            // Hosted session but the mod never wired commands: refusing outright
            // beats letting a vanilla ban corrupt the session.
            return true;
        }
        for (Object target : targets) {
            String playerName = link.sharedworld.versioned.ServerSettingsCompat.profileDisplayName(target);
            executeBanByName(
                    source,
                    playerName == null ? "" : playerName,
                    wiring.apiClient(),
                    wiring.hostingManager(),
                    wiring.ioExecutor(),
                    wiring.clientMainThreadExecutor()
            );
        }
        return true;
    }

    private static int executeBanByName(
            CommandSourceStack source,
            String playerName,
            SharedWorldApiClient apiClient,
            Supplier<SharedWorldHostingManager> hostingManager,
            Executor ioExecutor,
            Executor clientMainThreadExecutor
    ) {
        OwnerCommandTarget target = resolveOwnerCommandTarget(source, playerName, hostingManager, true);
        if (target == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        CompletableFuture.runAsync(() -> {
            try {
                apiClient.kickMember(target.worldId(), target.member().playerUuid());
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, ioExecutor).whenComplete((ignored, error) -> clientMainThreadExecutor.execute(() -> {
            if (error == null) {
                // Membership is gone; drop the grant now so a reconnect before the
                // next heartbeat refresh cannot arrive with operator permissions.
                SharedWorldHostingManager manager = hostingManager.get();
                if (manager != null) {
                    manager.applyLocalMemberPermissionChange(
                            target.worldId(),
                            target.member().playerUuid(),
                            target.member().playerName(),
                            false
                    );
                }
            }
            server.execute(() -> {
                if (error != null) {
                    source.sendFailure(Component.literal(friendlyMessage(error)));
                    return;
                }
                ServerPlayer online = findOnlinePlayer(server, target.member().playerUuid());
                if (online != null) {
                    online.connection.disconnect(Component.translatable("sharedworld.command.ban.disconnected"));
                }
                source.sendSuccess(() -> Component.translatable("sharedworld.command.ban.success", target.member().playerName()), true);
            });
        }));
        return 1;
    }

    private static int kickPlayers(
            CommandContext<CommandSourceStack> context,
            String reason,
            Supplier<SharedWorldHostingManager> hostingManager
    ) {
        CommandSourceStack source = context.getSource();
        Component reasonComponent = reason == null || reason.isBlank()
                ? Component.translatable("multiplayer.disconnect.kicked")
                : Component.literal(reason);
        SharedWorldHostingManager manager = hostingManager.get();
        String hostUuid = manager == null ? null : manager.activeHostPlayerUuid();
        int kicked = 0;
        try {
            for (ServerPlayer player : EntityArgument.getPlayers(context, "targets")) {
                // Never kick the hosting player out of their own integrated server.
                if (hostUuid != null && CanonicalPlayerIdentity.sameUuid(player.getUUID().toString(), hostUuid)) {
                    continue;
                }
                player.connection.disconnect(reasonComponent);
                kicked += 1;
            }
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
        if (kicked == 0) {
            source.sendFailure(Component.translatable("sharedworld.command.kick.nobody"));
            return 0;
        }
        int count = kicked;
        source.sendSuccess(() -> Component.translatable("sharedworld.command.kick.success", count), true);
        return kicked;
    }

    private record OwnerCommandTarget(String worldId, MemberCommandGrant member) {
    }

    /**
     * Shared validation for /op, /deop and /ban: runner is the owner, the owner is
     * the local host, the named player is a member, and the target is not the owner
     * (nor, for /ban, the runner). Sends the failure feedback itself and returns
     * null when the command must not proceed.
     */
    private static OwnerCommandTarget resolveOwnerCommandTarget(
            CommandSourceStack source,
            String playerName,
            Supplier<SharedWorldHostingManager> hostingManager,
            boolean forbidSelf
    ) {
        SharedWorldHostingManager manager = hostingManager.get();
        SharedWorldHostingManager.ActiveHostSession session = manager == null ? null : manager.activeHostSession();
        String ownerUuid = SharedWorldDevSessionBridge.hostingSharedWorldOwnerUuid();
        ServerPlayer runner = source.getPlayer();
        boolean authorized = session != null
                && runner != null
                && SharedWorldCommandGuards.canRunOwnerCommand(
                        SharedWorldDevSessionBridge.isHostingSharedWorld(),
                        runner.getUUID().toString(),
                        ownerUuid,
                        manager.activeHostPlayerUuid()
                );
        if (!authorized) {
            source.sendFailure(Component.translatable("sharedworld.command.owner_only"));
            return null;
        }
        Optional<MemberCommandGrant> member =
                SharedWorldCommandGuards.resolveMemberByName(SharedWorldDevSessionBridge.hostedMemberGrants(), playerName);
        if (member.isEmpty()) {
            source.sendFailure(Component.translatable("sharedworld.command.unknown_member", playerName));
            return null;
        }
        if (CanonicalPlayerIdentity.sameUuid(member.get().playerUuid(), ownerUuid)) {
            source.sendFailure(Component.translatable("sharedworld.command.cannot_target_owner"));
            return null;
        }
        if (forbidSelf && CanonicalPlayerIdentity.sameUuid(member.get().playerUuid(), runner.getUUID().toString())) {
            source.sendFailure(Component.translatable("sharedworld.command.cannot_target_self"));
            return null;
        }
        return new OwnerCommandTarget(session.worldId(), member.get());
    }

    private static ServerPlayer findOnlinePlayer(MinecraftServer server, String playerUuid) {
        try {
            // Backend membership UUIDs come unhyphenated; UUID.fromString needs hyphens.
            return server.getPlayerList().getPlayer(
                    UUID.fromString(CanonicalPlayerIdentity.normalizeUuidWithHyphens(playerUuid, "member UUID")));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String friendlyMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause.getMessage() == null || cause.getMessage().isBlank()) {
            return link.sharedworld.SharedWorldText.string("screen.sharedworld.error_generic");
        }
        return cause.getMessage();
    }
}
