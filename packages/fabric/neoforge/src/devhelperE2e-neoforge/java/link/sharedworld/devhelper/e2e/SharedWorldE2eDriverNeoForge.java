package link.sharedworld.devhelper.e2e;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge entrypoint shim for the loader-neutral e2e driver. Arming waits
 * for FMLClientSetupEvent like the main mod (the driver's init touches
 * SharedWorld singletons, which initialize there).
 */
@Mod(value = "sharedworld_dev_e2e", dist = Dist.CLIENT)
public final class SharedWorldE2eDriverNeoForge {
    public SharedWorldE2eDriverNeoForge(IEventBus modBus) {
        modBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(() -> {
            SharedWorldE2eDriver driver = new SharedWorldE2eDriver();
            if (driver.init()) {
                NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post tick) ->
                        driver.tick(Minecraft.getInstance()));
            }
        }));
    }
}
