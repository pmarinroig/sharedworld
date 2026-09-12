package link.sharedworld.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class XaeroMapCompatTest {
    @TempDir
    Path tempDir;

    @Test
    void readsXaerosOwnFileFormat() {
        assertEquals(1590264755, XaeroMapCompat.parseWorldId("id:1590264755"));
        assertEquals(-42, XaeroMapCompat.parseWorldId("other:x\nid:-42\n"));
        assertNull(XaeroMapCompat.parseWorldId("nothing here"));
        assertNull(XaeroMapCompat.parseWorldId("id:not-a-number"));
        assertEquals("mw$1590264755", XaeroMapCompat.worldNode(1590264755));
    }

    @Test
    void anExistingIdIsReusedAndAMissingOneIsWrittenOnce() throws IOException {
        Path world = Files.createDirectories(this.tempDir.resolve("world_abc"));
        Files.writeString(world.resolve(XaeroMapCompat.WORLD_ID_FILE), "id:7");
        assertEquals(7, XaeroMapCompat.ensureWorldId(world));

        Path fresh = Files.createDirectories(this.tempDir.resolve("world_def"));
        Integer first = XaeroMapCompat.ensureWorldId(fresh);
        assertTrue(Files.exists(fresh.resolve(XaeroMapCompat.WORLD_ID_FILE)));
        assertEquals("id:" + first, Files.readString(fresh.resolve(XaeroMapCompat.WORLD_ID_FILE)));
        assertEquals(first, XaeroMapCompat.ensureWorldId(fresh), "second call reads the same id back");

        assertNull(XaeroMapCompat.ensureWorldId(this.tempDir.resolve("does-not-exist")));
    }
}
