package link.sharedworld.host;

import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ReleaseOwnershipRegressionTest {
    @Test
    void hostingManagerNoLongerExposesLegacyReleaseEntryPoints() {
        assertThrows(NoSuchMethodException.class, () -> SharedWorldHostingManager.class.getDeclaredMethod("beginGracefulDisconnect", Minecraft.class));
        assertThrows(NoSuchMethodException.class, () -> SharedWorldHostingManager.class.getDeclaredMethod("onClientDisconnect", Minecraft.class));
        assertThrows(NoSuchMethodException.class, () -> SharedWorldHostingManager.class.getDeclaredMethod("resumePendingFinalization", String.class, String.class));
    }

    @Test
    void sharedWorldClientNoLongerFallsBackToHostingManagerDisconnectPath() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("user.dir"), "src/main/java/link/sharedworld/SharedWorldClient.java"));

        assertFalse(source.contains("hostingManager.onClientDisconnect"));
        assertFalse(source.contains("hostingManager.onClientStopping"));
    }
}
