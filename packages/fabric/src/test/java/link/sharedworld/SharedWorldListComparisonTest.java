package link.sharedworld;

import link.sharedworld.api.SharedWorldModels.SignedBlobUrlDto;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldListComparisonTest {
    @Test
    void ignoresSignedUrlChurn() {
        WorldSummaryDto left = world(
                "world-1",
                "hosting",
                2,
                new String[]{"HostA", "GuestB"},
                "icons/world-1.png",
                signedUrl("https://example.test/icon.png?expires=1", "2026-04-01T10:00:00Z")
        );
        WorldSummaryDto right = world(
                "world-1",
                "hosting",
                2,
                new String[]{"HostA", "GuestB"},
                "icons/world-1.png",
                signedUrl("https://example.test/icon.png?expires=2", "2026-04-01T10:15:00Z")
        );

        assertTrue(SharedWorldListComparison.orderedWorldsEqual(List.of(left), List.of(right)));
    }

    @Test
    void detectsStatusChanges() {
        WorldSummaryDto idle = world("world-1", "idle", 0, new String[0], "icons/world-1.png", null);
        WorldSummaryDto hosting = world("world-1", "hosting", 0, new String[0], "icons/world-1.png", null);

        assertFalse(SharedWorldListComparison.orderedWorldsEqual(List.of(idle), List.of(hosting)));
    }

    @Test
    void detectsPlayerPresenceChanges() {
        WorldSummaryDto left = world("world-1", "hosting", 1, new String[]{"HostA"}, "icons/world-1.png", null);
        WorldSummaryDto right = world("world-1", "hosting", 2, new String[]{"HostA", "GuestB"}, "icons/world-1.png", null);

        assertFalse(SharedWorldListComparison.orderedWorldsEqual(List.of(left), List.of(right)));
    }

    @Test
    void detectsCustomIconStorageKeyChanges() {
        WorldSummaryDto left = world("world-1", "hosting", 1, new String[]{"HostA"}, "icons/world-1.png", null);
        WorldSummaryDto right = world("world-1", "hosting", 1, new String[]{"HostA"}, "icons/world-2.png", null);

        assertFalse(SharedWorldListComparison.orderedWorldsEqual(List.of(left), List.of(right)));
    }

    @Test
    void detectsOrderChanges() {
        WorldSummaryDto worldOne = world("world-1", "idle", 0, new String[0], null, null);
        WorldSummaryDto worldTwo = world("world-2", "idle", 0, new String[0], null, null);

        assertFalse(SharedWorldListComparison.orderedWorldsEqual(List.of(worldOne, worldTwo), List.of(worldTwo, worldOne)));
    }

    private static WorldSummaryDto world(
            String id,
            String status,
            int onlinePlayerCount,
            String[] onlinePlayerNames,
            String customIconStorageKey,
            SignedBlobUrlDto customIconDownload
    ) {
        return new WorldSummaryDto(
                id,
                id + "-slug",
                "World " + id,
                "owner-" + id,
                "Line 1\nLine 2",
                customIconStorageKey,
                customIconDownload,
                3,
                status,
                "snapshot-" + id,
                "2026-03-31T10:15:30Z",
                "host-" + id,
                "Host " + id,
                "join." + id,
                onlinePlayerCount,
                onlinePlayerNames,
                "google-drive",
                true,
                "owner@example.com"
        );
    }

    private static SignedBlobUrlDto signedUrl(String url, String expiresAt) {
        return new SignedBlobUrlDto("GET", url, Map.of("accept", "image/png"), expiresAt);
    }
}
