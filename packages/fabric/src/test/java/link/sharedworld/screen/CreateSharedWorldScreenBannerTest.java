package link.sharedworld.screen;

import link.sharedworld.api.SharedWorldModels.StorageLinkSessionDto;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The create screen's storage-banner policy. The load-bearing case: a create
 * failure restores the wizard with a sticky error banner, and the storage
 * banner refresh that runs in the same init() (and again when the async
 * storage-account check completes) must NOT wipe it — it used to, which made
 * every create failure land the user back on the screen with no message at
 * all (the "loading finished, then nothing happened" field report).
 */
final class CreateSharedWorldScreenBannerTest {
    @Test
    void aRestoredCreateErrorSurvivesOffConnectStepBannerRefreshes() {
        SharedWorldStatusBanner banner = new SharedWorldStatusBanner();
        banner.set(SharedWorldStatusBanner.Kind.ERROR, Component.literal("Google Drive upload failed. HTTP 403."));

        boolean stillOwned = CreateSharedWorldScreen.updateStorageBanner(
                banner, false, true, null, CreateWizardModel.StorageState.CHECKING, null);

        assertTrue(stillOwned, "the restore error keeps owning the banner");
        assertTrue(banner.isVisible(), "the failure message must reach the user's eyes");
        assertEquals(SharedWorldStatusBanner.Kind.ERROR, banner.kind());

        // The async storage-account check triggers another refresh later;
        // the error must survive that one too.
        boolean stillOwnedAfterAccountCheck = CreateSharedWorldScreen.updateStorageBanner(
                banner, false, true, null, CreateWizardModel.StorageState.LINKED_ACCOUNT, null);
        assertTrue(stillOwnedAfterAccountCheck);
        assertTrue(banner.isVisible());
    }

    @Test
    void withoutARestoreErrorTheOffConnectStepRefreshClearsStickyMessages() {
        SharedWorldStatusBanner banner = new SharedWorldStatusBanner();
        banner.set(SharedWorldStatusBanner.Kind.INFO, Component.literal("Checking your Google Drive connection..."));

        boolean owned = CreateSharedWorldScreen.updateStorageBanner(
                banner, false, false, null, CreateWizardModel.StorageState.CHECKING, null);

        assertFalse(owned);
        assertFalse(banner.isVisible(), "connect-step messages must not strand on other steps");
    }

    @Test
    void enteringTheConnectStepHandsTheBannerToLinkMessaging() {
        SharedWorldStatusBanner banner = new SharedWorldStatusBanner();
        banner.set(SharedWorldStatusBanner.Kind.ERROR, Component.literal("previous create failure"));

        boolean owned = CreateSharedWorldScreen.updateStorageBanner(
                banner, true, true, null, CreateWizardModel.StorageState.CHECKING, null);

        assertFalse(owned, "the connect step owns its own messaging");
        assertTrue(banner.isVisible());
        assertEquals(SharedWorldStatusBanner.Kind.INFO, banner.kind(), "link-state messaging replaces the stale error");
    }

    @Test
    void aFailedLinkSessionMessageShowsOnTheConnectStep() {
        SharedWorldStatusBanner banner = new SharedWorldStatusBanner();
        StorageLinkSessionDto failed = new StorageLinkSessionDto(
                "link-1", "google-drive", "failed", null, null, null, null,
                "Google didn't grant SharedWorld access to its app folder in your Drive. Return to Minecraft, connect again, and tick the Drive access checkbox on the Google screen.");

        CreateSharedWorldScreen.updateStorageBanner(
                banner, true, false, null, CreateWizardModel.StorageState.NOT_LINKED, failed);

        assertTrue(banner.isVisible());
        assertEquals(SharedWorldStatusBanner.Kind.ERROR, banner.kind());
    }
}
