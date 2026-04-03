package link.sharedworld.sync;

@FunctionalInterface
public interface WorldSyncProgressListener {
    void onProgress(WorldSyncProgress progress);
}
