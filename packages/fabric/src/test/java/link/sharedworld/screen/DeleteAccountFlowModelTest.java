package link.sharedworld.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeleteAccountFlowModelTest {
    @Test
    void happyPathWalksEveryStageWithMonotonicProgress() {
        DeleteAccountFlowModel model = new DeleteAccountFlowModel();
        assertEquals(DeleteAccountFlowModel.Stage.DELETING_WORLDS, model.stage());

        model.onWorldsListed(2);
        float afterList = model.progress();
        model.onWorldDeleted();
        assertEquals(DeleteAccountFlowModel.Stage.DELETING_WORLDS, model.stage());
        float afterFirst = model.progress();
        assertTrue(afterFirst > afterList);

        model.onWorldDeleted();
        assertEquals(DeleteAccountFlowModel.Stage.PURGING_ACCOUNT, model.stage());
        float afterWorlds = model.progress();
        assertTrue(afterWorlds >= afterFirst);

        model.onAccountStep(false, 100);
        assertEquals(100, model.driveRemaining());
        model.onAccountStep(false, 40);
        float midSweep = model.progress();
        assertTrue(midSweep > afterWorlds);

        model.onAccountStep(true, 0);
        assertEquals(DeleteAccountFlowModel.Stage.WIPING_LOCAL, model.stage());
        assertTrue(model.progress() >= midSweep);

        model.onLocalWipeFinished();
        assertEquals(DeleteAccountFlowModel.Stage.DONE, model.stage());
        assertEquals(1.0F, model.progress());
    }

    @Test
    void noWorldsSkipsStraightToTheAccountPurge() {
        DeleteAccountFlowModel model = new DeleteAccountFlowModel();
        model.onWorldsListed(0);
        assertEquals(DeleteAccountFlowModel.Stage.PURGING_ACCOUNT, model.stage());
        assertTrue(model.progress() > 0.0F);
    }

    @Test
    void oneStepDeletionFinishesEvenWithoutADriveSweep() {
        DeleteAccountFlowModel model = new DeleteAccountFlowModel();
        model.onWorldsListed(0);
        model.onAccountStep(true, 0);
        assertEquals(DeleteAccountFlowModel.Stage.WIPING_LOCAL, model.stage());
        model.onLocalWipeFinished();
        assertEquals(DeleteAccountFlowModel.Stage.DONE, model.stage());
    }

    @Test
    void progressNeverMovesBackwardsWhenRemainingGrows() {
        DeleteAccountFlowModel model = new DeleteAccountFlowModel();
        model.onWorldsListed(0);
        model.onAccountStep(false, 50);
        model.onAccountStep(false, 10);
        float high = model.progress();
        // A concurrent upload could make the reported remaining grow again.
        model.onAccountStep(false, 80);
        assertTrue(model.progress() >= high);
        assertEquals(80, model.driveRemaining());
    }
}
