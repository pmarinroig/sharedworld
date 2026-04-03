package link.sharedworld.screen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldMetadataIconsRegressionTest {
    @Test
    void createAndEditFlowsUseSharedMetadataIconHelper() throws IOException {
        String abstractMetadataSource = Files.readString(sourcePath("link/sharedworld/screen/AbstractSharedWorldMetadataScreen.java"));
        String createSource = Files.readString(sourcePath("link/sharedworld/screen/CreateSharedWorldScreen.java"));
        String editSource = Files.readString(sourcePath("link/sharedworld/screen/EditSharedWorldScreen.java"));

        assertTrue(abstractMetadataSource.contains("SharedWorldMetadataIcons.uploadPreview("));
        assertTrue(abstractMetadataSource.contains("SharedWorldMetadataIcons.encodeSelectedIcon("));
        assertTrue(createSource.contains("SharedWorldMetadataIcons.uploadPreview("));
        assertTrue(editSource.contains("SharedWorldMetadataIcons.uploadPreview("));
    }

    private static Path sourcePath(String relativePath) {
        return Path.of(System.getProperty("user.dir"), "src/main/java", relativePath);
    }
}
