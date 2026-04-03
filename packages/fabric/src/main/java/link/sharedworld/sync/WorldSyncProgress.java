package link.sharedworld.sync;

public record WorldSyncProgress(
        String stage,
        double fraction,
        Long bytesDone,
        Long bytesTotal,
        String detailLine
) {
}
