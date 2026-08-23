package link.sharedworld.fabric;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.command.SharedWorldCommands;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/**
 * The Fabric entrypoint: a thin shim that initializes the loader-neutral core
 * and wires Fabric's events to it. Excluded from the NeoForge build, whose
 * @Mod entrypoint does the same with NeoForge events.
 */
public final class SharedWorldFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SharedWorldClient.init();
        ClientTickEvents.END_CLIENT_TICK.register(SharedWorldClient::onEndClientTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> SharedWorldClient.onPlayJoin(handler, client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SharedWorldClient.onPlayDisconnectEvent(handler, client));
        ClientLifecycleEvents.CLIENT_STOPPING.register(SharedWorldClient::onClientStopping);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            if (!environment.includeIntegrated) {
                return;
            }
            SharedWorldCommands.registerCommands(dispatcher);
        });
    }
}
