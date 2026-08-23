package link.sharedworld.devhelper.e2e;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** Fabric entrypoint shim for the loader-neutral e2e driver. */
public final class SharedWorldE2eDriverFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SharedWorldE2eDriver driver = new SharedWorldE2eDriver();
        if (driver.init()) {
            ClientTickEvents.END_CLIENT_TICK.register(driver::tick);
        }
    }
}
