package link.sharedworld;

import link.sharedworld.api.SharedWorldModels.ObserveWaitingResponseDto;
import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;
import net.minecraft.network.chat.Component;

final class SharedWorldWaitingFlowLogic {
    private SharedWorldWaitingFlowLogic() {
    }

    static PollDecision evaluateObservation(boolean hostChangeFlow, ObserveWaitingResponseDto observation) {
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
                        statusMessageFor(hostChangeFlow, runtime),
                        progressLabelFor(runtime)
                );
            }
            default -> PollDecision.error(Component.translatable("screen.sharedworld.waiting_error_unknown_action").getString());
        };
    }

    static boolean samePlayerUuid(String left, String right) {
        return left != null && right != null && left.replace("-", "").equalsIgnoreCase(right.replace("-", ""));
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

    private static String statusMessageFor(boolean hostChangeFlow, WorldRuntimeStatusDto runtime) {
        if (runtime == null) {
            return Component.translatable("screen.sharedworld.waiting").getString();
        }
        if (runtime.candidatePlayerName() != null) {
            return Component.translatable(hostChangeFlow
                    ? "screen.sharedworld.host_change_to_player"
                    : "screen.sharedworld.joining_waiting_for", runtime.candidatePlayerName()).getString();
        }
        if ("host-finalizing".equals(runtime.phase())) {
            return Component.translatable(hostChangeFlow
                    ? "screen.sharedworld.host_change_finalizing"
                    : "screen.sharedworld.joining_finalizing").getString();
        }
        return Component.translatable(hostChangeFlow
                ? "screen.sharedworld.host_change_waiting_start"
                : "screen.sharedworld.joining_waiting_start").getString();
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
