package link.sharedworld.screen;

/**
 * Pure state machine behind the create wizard: a fixed, enforced step order
 * (Connect Google Drive → Choose a world → Name it and create) where the
 * connect step is skipped entirely for players whose account is already
 * linked and healthy. No Minecraft types so the ordering rules are unit
 * testable on their own.
 */
final class CreateWizardModel {
    enum Step {
        CONNECT_DRIVE,
        PICK_WORLD,
        DETAILS
    }

    enum StorageState {
        /** The account check hasn't answered yet; the connect step waits. */
        CHECKING,
        /** A healthy reusable account exists; the connect step is skipped. */
        LINKED_ACCOUNT,
        /** A link session completed during this wizard run. */
        LINKED_THIS_RUN,
        /** No usable account: the player must connect before continuing. */
        NOT_LINKED
    }

    private StorageState storageState = StorageState.CHECKING;
    private Step step = Step.CONNECT_DRIVE;

    Step step() {
        return this.step;
    }

    StorageState storageState() {
        return this.storageState;
    }

    boolean storageSatisfied() {
        return this.storageState == StorageState.LINKED_ACCOUNT || this.storageState == StorageState.LINKED_THIS_RUN;
    }

    /** True while the wizard must show the connect step before anything else. */
    boolean connectStepRequired() {
        return !this.storageSatisfied();
    }

    /**
     * The account check answered. When the account is usable and the player is
     * still parked on the connect step, move straight to picking a world —
     * returning players should never see the Drive step again.
     */
    boolean onStorageAccountChecked(boolean linkedAndHealthy) {
        if (this.storageState == StorageState.LINKED_THIS_RUN) {
            return false;
        }
        this.storageState = linkedAndHealthy ? StorageState.LINKED_ACCOUNT : StorageState.NOT_LINKED;
        if (linkedAndHealthy && this.step == Step.CONNECT_DRIVE) {
            this.step = Step.PICK_WORLD;
            return true;
        }
        return false;
    }

    /**
     * Both storage providers are usable: storage is satisfied, but the connect
     * step holds until the player picks where the new world will live.
     */
    void onStorageProviderChoiceRequired() {
        if (this.storageState != StorageState.LINKED_THIS_RUN) {
            this.storageState = StorageState.LINKED_ACCOUNT;
        }
    }

    /** The account check itself failed; treat as not linked so the player can still connect. */
    void onStorageAccountCheckFailed() {
        if (this.storageState == StorageState.CHECKING) {
            this.storageState = StorageState.NOT_LINKED;
        }
    }

    /** A fresh link session completed: advance past the connect step immediately. */
    boolean onLinkCompleted() {
        this.storageState = StorageState.LINKED_THIS_RUN;
        if (this.step == Step.CONNECT_DRIVE) {
            this.step = Step.PICK_WORLD;
            return true;
        }
        return false;
    }

    /** A completed link broke (relink cancelled/expired mid-run). */
    void onLinkLost() {
        if (this.storageState == StorageState.LINKED_THIS_RUN) {
            this.storageState = StorageState.NOT_LINKED;
        }
    }

    boolean canAdvance(boolean saveSelected, boolean nameValid) {
        return switch (this.step) {
            case CONNECT_DRIVE -> false;
            case PICK_WORLD -> saveSelected;
            case DETAILS -> saveSelected && nameValid && this.storageSatisfied();
        };
    }

    /** The primary action on the last step is Create, not Next. */
    boolean advanceIsCreate() {
        return this.step == Step.DETAILS;
    }

    /** @return true when the step changed (false when create should run instead). */
    boolean advance(boolean saveSelected, boolean nameValid) {
        if (!this.canAdvance(saveSelected, nameValid)) {
            return false;
        }
        if (this.step == Step.PICK_WORLD) {
            this.step = Step.DETAILS;
            return true;
        }
        return false;
    }

    /** @return true when the step changed (false means: leave the wizard). */
    boolean back() {
        if (this.step == Step.DETAILS) {
            this.step = Step.PICK_WORLD;
            return true;
        }
        if (this.step == Step.PICK_WORLD && this.connectStepRequired()) {
            this.step = Step.CONNECT_DRIVE;
            return true;
        }
        return false;
    }

    /** Restore straight to the details step (create-failure drafts land there). */
    void restoreToDetails() {
        this.step = Step.DETAILS;
    }
}
