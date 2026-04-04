package link.sharedworld.host;

import link.sharedworld.api.SharedWorldModels.StartupProgressDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HostStartupProgressRelayControllerTest {
    @Test
    void steadyStateProgressIsCappedAtOneRequestPerSecond() {
        MutableClock clock = new MutableClock();
        ManualExecutor executor = new ManualExecutor();
        List<String> calls = new ArrayList<>();
        HostStartupProgressRelayController controller = new HostStartupProgressRelayController(
                (worldId, runtimeEpoch, hostToken, progress) -> calls.add(describe(worldId, progress)),
                executor,
                clock::now
        );

        controller.relay(authority("world-1"), progress("Syncing world", "determinate", 0.10));
        executor.runNext();

        controller.relay(authority("world-1"), progress("Syncing world", "determinate", 0.20));
        controller.relay(authority("world-1"), progress("Syncing world", "determinate", 0.30));
        controller.tick();
        assertEquals(0, executor.size());

        clock.set(999L);
        controller.tick();
        assertEquals(0, executor.size());

        clock.set(1_000L);
        controller.tick();
        executor.runNext();

        assertEquals(List.of(
                "world-1:Syncing world:determinate:0.1",
                "world-1:Syncing world:determinate:0.3"
        ), calls);
    }

    @Test
    void multipleProgressChangesWhileInflightCollapseToLatestStateOnly() {
        MutableClock clock = new MutableClock();
        ManualExecutor executor = new ManualExecutor();
        List<String> calls = new ArrayList<>();
        HostStartupProgressRelayController controller = new HostStartupProgressRelayController(
                (worldId, runtimeEpoch, hostToken, progress) -> calls.add(describe(worldId, progress)),
                executor,
                clock::now
        );

        controller.relay(authority("world-1"), progress("Syncing world", "determinate", 0.10));
        controller.relay(authority("world-1"), progress("Syncing world", "determinate", 0.20));
        controller.relay(authority("world-1"), progress("Syncing world", "determinate", 0.30));

        assertEquals(1, executor.size());
        executor.runNext();
        assertEquals(List.of("world-1:Syncing world:determinate:0.1"), calls);
        assertEquals(0, executor.size());

        clock.set(1_000L);
        controller.tick();
        assertEquals(1, executor.size());
        executor.runNext();

        assertEquals(List.of(
                "world-1:Syncing world:determinate:0.1",
                "world-1:Syncing world:determinate:0.3"
        ), calls);
    }

    @Test
    void labelChangesBypassTheSteadyStateThrottle() {
        MutableClock clock = new MutableClock();
        ManualExecutor executor = new ManualExecutor();
        List<String> calls = new ArrayList<>();
        HostStartupProgressRelayController controller = new HostStartupProgressRelayController(
                (worldId, runtimeEpoch, hostToken, progress) -> calls.add(describe(worldId, progress)),
                executor,
                clock::now
        );

        controller.relay(authority("world-1"), progress("Syncing world", "determinate", 0.10));
        executor.runNext();

        clock.set(100L);
        controller.relay(authority("world-1"), progress("Finalizing snapshot", "indeterminate", null));
        assertEquals(1, executor.size());
        executor.runNext();

        assertEquals(List.of(
                "world-1:Syncing world:determinate:0.1",
                "world-1:Finalizing snapshot:indeterminate:null"
        ), calls);
    }

    @Test
    void identicalProgressRefreshesAfterThirtySeconds() {
        MutableClock clock = new MutableClock();
        ManualExecutor executor = new ManualExecutor();
        List<String> calls = new ArrayList<>();
        HostStartupProgressRelayController controller = new HostStartupProgressRelayController(
                (worldId, runtimeEpoch, hostToken, progress) -> calls.add(describe(worldId, progress)),
                executor,
                clock::now
        );

        controller.relay(authority("world-1"), progress("Finalizing snapshot", "indeterminate", null));
        executor.runNext();

        clock.set(29_999L);
        controller.relay(authority("world-1"), progress("Finalizing snapshot", "indeterminate", null));
        controller.tick();
        assertEquals(0, executor.size());

        clock.set(30_000L);
        controller.tick();
        assertEquals(1, executor.size());
        executor.runNext();

        assertEquals(List.of(
                "world-1:Finalizing snapshot:indeterminate:null",
                "world-1:Finalizing snapshot:indeterminate:null"
        ), calls);
    }

    @Test
    void leavingRelayableStateQueuesOneClearAfterOlderInflightWorkFinishes() {
        MutableClock clock = new MutableClock();
        ManualExecutor executor = new ManualExecutor();
        List<String> calls = new ArrayList<>();
        HostStartupProgressRelayController controller = new HostStartupProgressRelayController(
                (worldId, runtimeEpoch, hostToken, progress) -> calls.add(describe(worldId, progress)),
                executor,
                clock::now
        );

        controller.relay(authority("world-1"), progress("Syncing world", "determinate", 0.10));
        controller.clear(authority("world-1"));

        assertEquals(1, executor.size());
        executor.runNext();
        assertEquals(1, executor.size());
        executor.runNext();

        assertEquals(List.of("world-1:<clear>"), calls);
    }

    @Test
    void clearDuringInflightRelayQueuesSingleClearAfterwards() {
        MutableClock clock = new MutableClock();
        List<String> calls = new ArrayList<>();
        HostStartupProgressRelayController[] controllerRef = new HostStartupProgressRelayController[1];
        HostStartupProgressRelayController controller = new HostStartupProgressRelayController(
                (worldId, runtimeEpoch, hostToken, progress) -> {
                    calls.add(describe(worldId, progress));
                    if (progress != null) {
                        controllerRef[0].clear(new HostStartupProgressRelayController.AuthorityContext(
                                worldId,
                                runtimeEpoch,
                                hostToken,
                                1L
                        ));
                    }
                },
                Runnable::run,
                clock::now
        );
        controllerRef[0] = controller;

        controller.relay(authority("world-1"), progress("Syncing world", "determinate", 0.10));

        assertEquals(List.of(
                "world-1:Syncing world:determinate:0.1",
                "world-1:<clear>"
        ), calls);
    }

    private static StartupProgressDto progress(String label, String mode, Double fraction) {
        return new StartupProgressDto(label, mode, fraction, null);
    }

    private static HostStartupProgressRelayController.AuthorityContext authority(String worldId) {
        return new HostStartupProgressRelayController.AuthorityContext(worldId, 7L, "token-" + worldId, 1L);
    }

    private static String describe(String worldId, StartupProgressDto progress) {
        if (progress == null) {
            return worldId + ":<clear>";
        }
        return worldId + ":" + progress.label() + ":" + progress.mode() + ":" + progress.fraction();
    }

    private static final class MutableClock {
        private long now;

        private long now() {
            return this.now;
        }

        private void set(long now) {
            this.now = now;
        }
    }

    private static final class ManualExecutor implements java.util.concurrent.Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            this.tasks.add(command);
        }

        private int size() {
            return this.tasks.size();
        }

        private void runNext() {
            Runnable task = this.tasks.remove();
            task.run();
        }
    }
}
