package link.sharedworld;

import link.sharedworld.api.SharedWorldModels.ObserveWaitingResponseDto;
import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;

final class SharedWorldWaitingFlowLogic {
    static final long DISCARD_PENDING_FINALIZATION_STALL_MS = 15_000L;

    private SharedWorldWaitingFlowLogic() {
    }

    static PollDecision evaluateObservation(WaitingContext context, ObserveWaitingResponseDto observation) {
        if (observation == null || observation.action() == null) {
            return PollDecision.error(Component.translatable("screen.sharedworld.waiting_error_missing_action").getString());
        }
        WorldRuntimeStatusDto runtime = observation.runtime();
        return switch (observation.action()) {
            case "connect" -> {
                String target = runtime == null ? null : runtime.joinTarget();
                if (target == null || target.isBlank()) {
                    yield PollDecision.error(Component.translatable("screen.sharedworld.waiting_error_missing_join_target").getString());
                }
                yield PollDecision.connect(target, runtime.runtimeEpoch());
            }
            case "host" -> PollDecision.error(Component.translatable("screen.sharedworld.waiting_error_unexpected_host").getString());
            case "restart" -> PollDecision.restart();
            case "wait" -> {
                String waiterSessionId = observation.waiterSessionId();
                if (waiterSessionId == null || waiterSessionId.isBlank()) {
                    yield PollDecision.error(Component.translatable("screen.sharedworld.waiting_error_missing_waiter_session").getString());
                }
                yield PollDecision.waiting(
                        waiterSessionId,
                        runtime,
                        statusMessageFor(context, runtime),
                        progressLabelFor(runtime)
                );
            }
            default -> PollDecision.error(Component.translatable("screen.sharedworld.waiting_error_unknown_action").getString());
        };
    }

    static PollDecision waitingFallback(WaitingContext context, WorldRuntimeStatusDto runtime) {
        return PollDecision.waiting(
                null,
                runtime,
                statusMessageFor(context, runtime),
                progressLabelFor(runtime)
        );
    }

    static boolean samePlayerUuid(String left, String right) {
        return left != null && right != null && left.replace("-", "").equalsIgnoreCase(right.replace("-", ""));
    }

    static boolean canDiscardPendingFinalization(
            String ownerUuid,
            String currentPlayerUuid,
            WorldRuntimeStatusDto runtime,
            boolean transitionStarted,
            boolean actionInFlight,
            long nowMillis
    ) {
        if (runtime == null || ownerUuid == null || currentPlayerUuid == null) {
            return false;
        }
        if (transitionStarted || actionInFlight) {
            return false;
        }
        if (!"host-finalizing".equals(runtime.phase())) {
            return false;
        }
        if (!samePlayerUuid(currentPlayerUuid, ownerUuid)) {
            return false;
        }
        if (samePlayerUuid(currentPlayerUuid, runtime.hostUuid())) {
            return false;
        }
        long lastActivityAt = lastFinalizationActivityAtMillis(runtime);
        return lastActivityAt != Long.MIN_VALUE && nowMillis - lastActivityAt >= DISCARD_PENDING_FINALIZATION_STALL_MS;
    }

    private static Component progressLabelFor(WorldRuntimeStatusDto runtime) {
        if (runtime != null && runtime.startupProgress() != null) {
            return Component.literal(runtime.startupProgress().label());
        }
        if (runtime != null && "host-finalizing".equals(runtime.phase())) {
            return Component.translatable("screen.sharedworld.progress.finalizing_previous_host");
        }
        return Component.translatable("screen.sharedworld.progress.waiting_for_host");
    }

    private static String statusMessageFor(WaitingContext context, WorldRuntimeStatusDto runtime) {
        if (runtime == null) {
            return Component.translatable("screen.sharedworld.waiting").getString();
        }
        if (runtime.candidatePlayerName() != null) {
            return Component.translatable(context.hostChangeFlow()
                    ? "screen.sharedworld.host_change_to_player"
                    : "screen.sharedworld.joining_waiting_for", runtime.candidatePlayerName()).getString();
        }
        if ("host-finalizing".equals(runtime.phase())) {
            return Component.translatable(context.hostChangeFlow()
                    ? "screen.sharedworld.host_change_finalizing"
                    : "screen.sharedworld.joining_finalizing").getString();
        }
        return Component.translatable(context.hostChangeFlow()
                ? "screen.sharedworld.host_change_waiting_start"
                : "screen.sharedworld.joining_waiting_start").getString();
    }

    private static boolean sameTarget(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static long lastFinalizationActivityAtMillis(WorldRuntimeStatusDto runtime) {
        long runtimeUpdatedAt = parseIsoInstantMillis(runtime.lastProgressAt());
        if (runtime.startupProgress() == null) {
            return runtimeUpdatedAt;
        }
        long progressUpdatedAt = parseIsoInstantMillis(runtime.startupProgress().updatedAt());
        return Math.max(runtimeUpdatedAt, progressUpdatedAt);
    }

    private static long parseIsoInstantMillis(String value) {
        if (value == null || value.isBlank()) {
            return Long.MIN_VALUE;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return Long.MIN_VALUE;
        }
    }

    record WaitingContext(
            String worldId,
            String worldName,
            String previousJoinTarget,
            boolean hostChangeFlow
    ) {
    }

    record PollDecision(
            Outcome outcome,
            String connectTarget,
            long runtimeEpoch,
            String waiterSessionId,
            WorldRuntimeStatusDto runtimeStatus,
            String statusMessage,
            Component progressLabel,
            String errorMessage
    ) {
        static PollDecision connect(String target, long runtimeEpoch) {
            return new PollDecision(Outcome.CONNECT, target, runtimeEpoch, null, null, null, null, null);
        }

        static PollDecision restart() {
            return new PollDecision(Outcome.RESTART, null, 0L, null, null, null, null, null);
        }

        static PollDecision waiting(String waiterSessionId, WorldRuntimeStatusDto runtimeStatus, String statusMessage, Component progressLabel) {
            return new PollDecision(Outcome.STAY_WAITING, null, 0L, waiterSessionId, runtimeStatus, statusMessage, progressLabel, null);
        }

        static PollDecision error(String errorMessage) {
            return new PollDecision(Outcome.ERROR, null, 0L, null, null, null, null, errorMessage);
        }
    }

    enum Outcome {
        CONNECT,
        RESTART,
        STAY_WAITING,
        ERROR
    }
}
