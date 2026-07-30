package link.sharedworld.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateWizardModelTest {
    @Test
    void startsOnConnectStepWhileTheAccountCheckIsPending() {
        CreateWizardModel model = new CreateWizardModel();
        assertEquals(CreateWizardModel.Step.CONNECT_DRIVE, model.step());
        assertEquals(CreateWizardModel.StorageState.CHECKING, model.storageState());
        assertFalse(model.canAdvance(true, true));
    }

    @Test
    void linkedAccountSkipsTheConnectStepEntirely() {
        CreateWizardModel model = new CreateWizardModel();
        assertTrue(model.onStorageAccountChecked(true));
        assertEquals(CreateWizardModel.Step.PICK_WORLD, model.step());
        assertFalse(model.connectStepRequired());
        // Back from the first visible step leaves the wizard.
        assertFalse(model.back());
    }

    @Test
    void unlinkedAccountStaysOnConnectUntilAFreshLinkCompletes() {
        CreateWizardModel model = new CreateWizardModel();
        assertFalse(model.onStorageAccountChecked(false));
        assertEquals(CreateWizardModel.Step.CONNECT_DRIVE, model.step());
        assertFalse(model.canAdvance(true, true));

        assertTrue(model.onLinkCompleted());
        assertEquals(CreateWizardModel.Step.PICK_WORLD, model.step());
        assertTrue(model.storageSatisfied());
        // Once linked, the connect step is skipped both ways: Back from the
        // first visible step leaves the wizard instead of reopening it.
        assertFalse(model.back());
        assertEquals(CreateWizardModel.Step.PICK_WORLD, model.step());
    }

    @Test
    void advanceOrderIsWorldThenDetailsThenCreate() {
        CreateWizardModel model = new CreateWizardModel();
        model.onStorageAccountChecked(true);

        assertFalse(model.advance(false, false));
        assertTrue(model.advance(true, false));
        assertEquals(CreateWizardModel.Step.DETAILS, model.step());

        assertTrue(model.advanceIsCreate());
        assertFalse(model.canAdvance(true, false));
        assertTrue(model.canAdvance(true, true));
        // Advancing on the last step never changes the step: it means create.
        assertFalse(model.advance(true, true));
        assertEquals(CreateWizardModel.Step.DETAILS, model.step());
    }

    @Test
    void createIsBlockedUntilStorageIsSatisfied() {
        CreateWizardModel model = new CreateWizardModel();
        model.onStorageAccountChecked(false);
        model.onLinkCompleted();
        model.advance(true, true);
        assertTrue(model.canAdvance(true, true));

        model.onLinkLost();
        assertFalse(model.canAdvance(true, true));
    }

    @Test
    void accountCheckArrivingAfterAFreshLinkDoesNotDowngradeState() {
        CreateWizardModel model = new CreateWizardModel();
        model.onLinkCompleted();
        assertFalse(model.onStorageAccountChecked(false));
        assertEquals(CreateWizardModel.StorageState.LINKED_THIS_RUN, model.storageState());
    }

    @Test
    void checkFailureFallsBackToTheConnectStep() {
        CreateWizardModel model = new CreateWizardModel();
        model.onStorageAccountCheckFailed();
        assertEquals(CreateWizardModel.StorageState.NOT_LINKED, model.storageState());
        assertEquals(CreateWizardModel.Step.CONNECT_DRIVE, model.step());
    }

    @Test
    void restoredDraftsLandOnDetails() {
        CreateWizardModel model = new CreateWizardModel();
        model.onLinkCompleted();
        model.restoreToDetails();
        assertEquals(CreateWizardModel.Step.DETAILS, model.step());
        assertTrue(model.canAdvance(true, true));
        assertTrue(model.back());
        assertEquals(CreateWizardModel.Step.PICK_WORLD, model.step());
    }
}
