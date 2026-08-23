package link.sharedworld.neoforge;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.command.SharedWorldCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The NeoForge entrypoint: a thin shim that initializes the loader-neutral
 * core and wires NeoForge's events to it — the mirror of the Fabric
 * SharedWorldFabricClient. The core initializes inside FMLClientSetupEvent
 * (Minecraft.getInstance() is reliably usable there), and the game-bus
 * listeners register only after init so no tick can observe a half-built core.
 */
@Mod(value = "sharedworld", dist = Dist.CLIENT)
public final class SharedWorldNeoForgeClient {
    public SharedWorldNeoForgeClient(IEventBus modBus) {
        modBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(() -> {
            SharedWorldClient.init();
            registerGameListeners();
        }));
    }

    private static void registerGameListeners() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) ->
                SharedWorldClient.onEndClientTick(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> {
            ClientPacketListener handler = event.getPlayer() != null
                    ? event.getPlayer().connection
                    : Minecraft.getInstance().getConnection();
            if (handler != null) {
                SharedWorldClient.onPlayJoin(handler, Minecraft.getInstance());
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            ClientPacketListener handler = event.getPlayer() != null
                    ? event.getPlayer().connection
                    : Minecraft.getInstance().getConnection();
            // A teardown with no reachable handler is caught by the core's
            // connectionless-session reconciler within two seconds.
            if (handler != null) {
                SharedWorldClient.onPlayDisconnectEvent(handler, Minecraft.getInstance());
            }
        });
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) ->
                SharedWorldClient.onClientStopping(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> {
            // includeIntegrated is package-private in mojmap; every selection
            // except DEDICATED includes the integrated server.
            if (event.getCommandSelection() != net.minecraft.commands.Commands.CommandSelection.DEDICATED) {
                SharedWorldCommands.registerCommands(event.getDispatcher());
            }
        });
    }
}
