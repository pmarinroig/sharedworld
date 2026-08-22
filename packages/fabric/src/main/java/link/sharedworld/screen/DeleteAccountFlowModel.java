package link.sharedworld.screen;

/**
 * State machine of the full account deletion, kept free of Minecraft types so
 * the transitions and progress math are unit-testable. The progress screen's
 * worker drives it: list worlds → delete each (backups first) → loop
 * DELETE /account until done → wipe local files.
 */
public final class DeleteAccountFlowModel {
    public enum Stage {
        DELETING_WORLDS,
        PURGING_ACCOUNT,
        WIPING_LOCAL,
        DONE
    }

    /** Progress span reserved for the world-deletion stage. */
    private static final float WORLDS_SPAN = 0.5F;
    /** Progress span reserved for the server-side account purge. */
    private static final float ACCOUNT_SPAN = 0.4F;

    private Stage stage = Stage.DELETING_WORLDS;
    private int totalWorlds;
    private int deletedWorlds;
    private long initialDriveRemaining = -1;
    private long driveRemaining = -1;
    private float progress;

    public void onWorldsListed(int total) {
        this.totalWorlds = Math.max(0, total);
        if (this.totalWorlds == 0) {
            this.advanceTo(Stage.PURGING_ACCOUNT, WORLDS_SPAN);
        }
    }

    public void onWorldDeleted() {
        this.deletedWorlds = Math.min(this.deletedWorlds + 1, this.totalWorlds);
        this.raiseProgress(WORLDS_SPAN * this.deletedWorlds / Math.max(1, this.totalWorlds));
        if (this.deletedWorlds >= this.totalWorlds) {
            this.advanceTo(Stage.PURGING_ACCOUNT, WORLDS_SPAN);
        }
    }

    public void onAccountStep(boolean done, long remaining) {
        this.advanceTo(Stage.PURGING_ACCOUNT, WORLDS_SPAN);
        if (done) {
            this.driveRemaining = 0;
            this.advanceTo(Stage.WIPING_LOCAL, WORLDS_SPAN + ACCOUNT_SPAN);
            return;
        }
        this.driveRemaining = Math.max(0, remaining);
        if (this.initialDriveRemaining < 0) {
            this.initialDriveRemaining = this.driveRemaining;
        }
        if (this.initialDriveRemaining > 0) {
            float swept = (float) (this.initialDriveRemaining - this.driveRemaining) / this.initialDriveRemaining;
            this.raiseProgress(WORLDS_SPAN + ACCOUNT_SPAN * Math.max(0.0F, Math.min(1.0F, swept)));
        }
    }

    public void onLocalWipeFinished() {
        this.stage = Stage.DONE;
        this.progress = 1.0F;
    }

    public Stage stage() {
        return this.stage;
    }

    /** Monotonic overall fraction in [0, 1]. */
    public float progress() {
        return this.progress;
    }

    public int deletedWorlds() {
        return this.deletedWorlds;
    }

    public int totalWorlds() {
        return this.totalWorlds;
    }

    /** Known-remaining Drive files during the purge, or -1 before the first step reports. */
    public long driveRemaining() {
        return this.driveRemaining;
    }

    private void advanceTo(Stage stage, float floor) {
        if (stage.ordinal() > this.stage.ordinal()) {
            this.stage = stage;
        }
        this.raiseProgress(floor);
    }

    private void raiseProgress(float value) {
        if (value > this.progress) {
            this.progress = Math.min(value, 1.0F);
        }
    }
}
