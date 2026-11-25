package link.sharedworld.forge;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.command.SharedWorldCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * The Forge 1.20.1 entrypoint: a thin shim that initializes the loader-neutral
 * core and wires MinecraftForge's events to it; the mirror of the Fabric
 * SharedWorldFabricClient and NeoForge SharedWorldNeoForgeClient.
 *
 * <p>The core initializes inside {@link FMLClientSetupEvent} where the client
 * singleton is reliably available, and game-bus listeners register only after
 * init so no tick can observe an uninitialized core.
 */
@Mod("sharedworld")
public final class SharedWorldForgeClient {
    public SharedWorldForgeClient() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(() -> {
            SharedWorldClient.init();
            registerGameListeners();
        }));
    }

    private static void registerGameListeners() {
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                SharedWorldClient.onEndClientTick(Minecraft.getInstance());
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> {
            ClientPacketListener handler = event.getPlayer() != null
                    ? event.getPlayer().connection
                    : Minecraft.getInstance().getConnection();
            if (handler != null) {
                SharedWorldClient.onPlayJoin(handler, Minecraft.getInstance());
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            ClientPacketListener handler = event.getPlayer() != null
                    ? event.getPlayer().connection
                    : Minecraft.getInstance().getConnection();
            // A teardown with no reachable handler is caught by the core's
            // connectionless-session reconciler within two seconds.
            if (handler != null) {
                SharedWorldClient.onPlayDisconnectEvent(handler, Minecraft.getInstance());
            }
        });
        MinecraftForge.EVENT_BUS.addListener((GameShuttingDownEvent event) ->
                SharedWorldClient.onClientStopping(Minecraft.getInstance()));
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> {
            // Include integrated-server commands only; dedicated server commands stay out.
            if (event.getCommandSelection() != net.minecraft.commands.Commands.CommandSelection.DEDICATED) {
                SharedWorldCommands.registerCommands(event.getDispatcher());
            }
        });
    }
}
