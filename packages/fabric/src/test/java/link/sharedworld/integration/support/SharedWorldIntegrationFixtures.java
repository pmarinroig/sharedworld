package link.sharedworld.integration.support;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import link.sharedworld.SharedWorldCoordinatorSupport;
import link.sharedworld.SharedWorldRecoveryStore;
import link.sharedworld.SharedWorldSessionCoordinator;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels;
import link.sharedworld.host.SharedWorldHostingManager;
import link.sharedworld.host.SharedWorldReleaseCoordinator;
import link.sharedworld.host.SharedWorldReleasePhase;
import link.sharedworld.host.SharedWorldReleaseStore;
import link.sharedworld.support.SharedWorldCoordinatorHarness;
import link.sharedworld.sync.ManagedWorldStore;
import link.sharedworld.sync.WorldSyncCoordinator;
import link.sharedworld.sync.WorldSyncProgressListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public final class SharedWorldIntegrationFixtures {
    private SharedWorldIntegrationFixtures() {
    }

    public static ReleasedWorld createReleasedWorld(
            String namePrefix,
            SharedWorldIntegrationBackend.TestPlayer owner,
            SharedWorldIntegrationBackend.TestPlayer... members
    ) throws Exception {
        SharedWorldApiClient ownerClient = SharedWorldIntegrationBackend.apiClient(owner);
        SharedWorldModels.CreateWorldResultDto created = ownerClient.createWorld(
                SharedWorldIntegrationBackend.uniqueName(namePrefix),
                null,
                null,
                null,
                null,
                null
        );
        SharedWorldModels.HostAssignmentDto seedAssignment = created.initialUploadAssignment();
        if (seedAssignment != null) {
            ownerClient.releaseHost(created.world().id(), false, seedAssignment.runtimeEpoch(), seedAssignment.hostToken());
        }
        for (SharedWorldIntegrationBackend.TestPlayer member : members) {
            if (member.playerUuidHyphenated().equalsIgnoreCase(owner.playerUuidHyphenated())) {
                continue;
            }
            SharedWorldModels.InviteCodeDto invite = ownerClient.createInvite(created.world().id());
            SharedWorldIntegrationBackend.apiClient(member).redeemInvite(invite.code());
        }
        SharedWorldModels.WorldDetailsDto world = ownerClient.getWorld(created.world().id());
        return new ReleasedWorld(world, ownerClient, owner);
    }

    public static HostedWorld createHostedWorld(
            String namePrefix,
            SharedWorldIntegrationBackend.TestPlayer owner,
            SharedWorldIntegrationBackend.TestPlayer host,
            SharedWorldIntegrationBackend.TestPlayer... additionalMembers
    ) throws Exception {
        List<SharedWorldIntegrationBackend.TestPlayer> members = new ArrayList<>();
        members.add(host);
        for (SharedWorldIntegrationBackend.TestPlayer member : additionalMembers) {
            members.add(member);
        }
        ReleasedWorld released = createReleasedWorld(namePrefix, owner, members.toArray(SharedWorldIntegrationBackend.TestPlayer[]::new));
        SharedWorldApiClient hostClient = owner.playerUuidHyphenated().equalsIgnoreCase(host.playerUuidHyphenated())
                ? released.ownerClient()
                : SharedWorldIntegrationBackend.apiClient(host);
        SharedWorldModels.HostAssignmentDto assignment = hostLive(hostClient, released.world().id(), "join.example");
        SharedWorldModels.WorldDetailsDto refreshedWorld = released.ownerClient().getWorld(released.world().id());
        return new HostedWorld(refreshedWorld, released.ownerClient(), hostClient, owner, host, assignment);
    }

    public static SharedWorldModels.HostAssignmentDto hostLive(
            SharedWorldApiClient hostClient,
            String worldId,
            String joinTarget
    ) throws Exception {
        SharedWorldModels.EnterSessionResponseDto entered = hostClient.enterSession(worldId);
        SharedWorldModels.HostAssignmentDto assignment = entered.assignment();
        hostClient.heartbeatHost(worldId, assignment.runtimeEpoch(), assignment.hostToken(), joinTarget);
        return assignment;
    }

    public static SessionFixture sessionFixture(SharedWorldIntegrationBackend.TestPlayer player) {
        return sessionFixture(player, null);
    }

    public static SessionFixture sessionFixture(
            SharedWorldIntegrationBackend.TestPlayer player,
            Consumer<SharedWorldModels.EnterSessionResponseDto> onHostStart
    ) {
        SharedWorldCoordinatorHarness.DeterministicAsync async = new SharedWorldCoordinatorHarness.DeterministicAsync();
        SharedWorldCoordinatorHarness.FakeClock clock = new SharedWorldCoordinatorHarness.FakeClock(1_700_000_000_000L);
        SharedWorldCoordinatorHarness.FakeClientShell clientShell = new SharedWorldCoordinatorHarness.FakeClientShell();
        SharedWorldApiClient client = SharedWorldIntegrationBackend.apiClient(player);
        List<SharedWorldModels.EnterSessionResponseDto> hostStarts = new ArrayList<>();
        SharedWorldSessionCoordinator coordinator = new SharedWorldSessionCoordinator(
                new RealSessionBackend(client),
                new SharedWorldCoordinatorHarness.InMemoryRecoveryStore(),
                async,
                clock,
                clientShell,
                new SharedWorldCoordinatorHarness.FakePlayerIdentity(player.playerUuid()),
                (parent, result) -> {
                    hostStarts.add(result);
                    if (onHostStart != null) {
                        onHostStart.accept(result);
                    }
                },
                new SharedWorldSessionCoordinator.SessionUi() {
                    @Override
                    public Screen joinError(Screen parent, Throwable error) {
                        clientShell.markNextScreen("join-error");
                        return null;
                    }

                    @Override
                    public Screen hostAcquired(Screen parent, SharedWorldModels.EnterSessionResponseDto result) {
                        clientShell.markNextScreen("host-acquired");
                        return null;
                    }

                    @Override
                    public Screen waiting(Screen parent, String worldId, String worldName, String ownerUuid) {
                        clientShell.markNextScreen("waiting");
                        return null;
                    }

                    @Override
                    public Screen uncleanShutdownWarning(Screen parent, String worldId, String worldName, SharedWorldModels.WorldRuntimeStatusDto runtimeStatus) {
                        clientShell.markNextScreen("unclean-shutdown-warning");
                        return null;
                    }

                    @Override
                    public Screen deleted(Screen parent) {
                        clientShell.markNextScreen("deleted");
                        return null;
                    }
                }
        );
        return new SessionFixture(player, client, async, clock, clientShell, coordinator, hostStarts);
    }

    public static ReleaseFixture releaseFixture(HostedWorld hostedWorld) throws Exception {
        ReleaseResources resources = ReleaseResources.create(hostedWorld);
        return new ReleaseFixture(resources, true, true, true);
    }

    public record ReleasedWorld(
            SharedWorldModels.WorldDetailsDto world,
            SharedWorldApiClient ownerClient,
            SharedWorldIntegrationBackend.TestPlayer owner
    ) {
    }

    public record HostedWorld(
            SharedWorldModels.WorldDetailsDto world,
            SharedWorldApiClient ownerClient,
            SharedWorldApiClient hostClient,
            SharedWorldIntegrationBackend.TestPlayer owner,
            SharedWorldIntegrationBackend.TestPlayer host,
            SharedWorldModels.HostAssignmentDto assignment
    ) {
    }

    public static final class SessionFixture {
        public final SharedWorldIntegrationBackend.TestPlayer player;
        public final SharedWorldApiClient client;
        public final SharedWorldCoordinatorHarness.DeterministicAsync async;
        public final SharedWorldCoordinatorHarness.FakeClock clock;
        public final SharedWorldCoordinatorHarness.FakeClientShell clientShell;
        public final SharedWorldSessionCoordinator coordinator;
        private final List<SharedWorldModels.EnterSessionResponseDto> hostStarts;

        private SessionFixture(
                SharedWorldIntegrationBackend.TestPlayer player,
                SharedWorldApiClient client,
                SharedWorldCoordinatorHarness.DeterministicAsync async,
                SharedWorldCoordinatorHarness.FakeClock clock,
                SharedWorldCoordinatorHarness.FakeClientShell clientShell,
                SharedWorldSessionCoordinator coordinator,
                List<SharedWorldModels.EnterSessionResponseDto> hostStarts
        ) {
            this.player = player;
            this.client = client;
            this.async = async;
            this.clock = clock;
            this.clientShell = clientShell;
            this.coordinator = coordinator;
            this.hostStarts = hostStarts;
        }

        public int hostStartCount() {
            return this.hostStarts.size();
        }

        public SharedWorldModels.EnterSessionResponseDto latestHostStart() {
            return this.hostStarts.isEmpty() ? null : this.hostStarts.getLast();
        }

        public void pollOnce() {
            this.clock.advance(1_000L);
            this.coordinator.tick(null);
            this.async.runUntilIdle();
        }

        public void releaseLatestHost(boolean graceful) throws Exception {
            SharedWorldModels.EnterSessionResponseDto hostStart = latestHostStart();
            if (hostStart == null || hostStart.assignment() == null) {
                throw new IllegalStateException("No host assignment is available to release.");
            }
            this.client.releaseHost(
                    hostStart.assignment().worldId(),
                    graceful,
                    hostStart.assignment().runtimeEpoch(),
                    hostStart.assignment().hostToken()
            );
        }
    }

    public static final class ReleaseFixture implements AutoCloseable {
        public final SharedWorldCoordinatorHarness.DeterministicAsync async;
        public final SharedWorldCoordinatorHarness.FakeClock clock;
        public final SharedWorldCoordinatorHarness.FakeClientShell clientShell;
        public final FileReleaseStore releaseStore;
        public final RealReleaseBackend releaseBackend;
        public final RealHostControl hostControl;
        public final SharedWorldReleaseCoordinator coordinator;
        private final ReleaseResources resources;
        private final boolean cleanupOwner;

        private ReleaseFixture(
                ReleaseResources resources,
                boolean exposeActiveHostSession,
                boolean connectedToLocalWorld,
                boolean cleanupOwner
        ) {
            this.resources = resources;
            this.cleanupOwner = cleanupOwner;
            this.async = new SharedWorldCoordinatorHarness.DeterministicAsync();
            this.clock = new SharedWorldCoordinatorHarness.FakeClock(1_800_000_000_000L);
            this.clientShell = new SharedWorldCoordinatorHarness.FakeClientShell();
            this.clientShell.setLocalServerState(connectedToLocalWorld, connectedToLocalWorld, connectedToLocalWorld);
            this.releaseStore = new FileReleaseStore(this.resources.releaseStorePath);
            this.releaseBackend = new RealReleaseBackend(this.resources);
            this.hostControl = new RealHostControl(this.resources, exposeActiveHostSession);
            this.coordinator = new SharedWorldReleaseCoordinator(
                    this.releaseBackend,
                    this.hostControl,
                    this.releaseStore,
                    this.async,
                    this.clock,
                    this.clientShell,
                    new SharedWorldCoordinatorHarness.FakePlayerIdentity(this.resources.hostPlayer.playerUuid()),
                    worldName -> {
                        this.clientShell.markNextScreen("saving");
                        return null;
                    }
            );
        }

        public ReleaseFixture restartDisconnected() {
            return new ReleaseFixture(this.resources, false, false, false);
        }

        public void driveUntilTerminal() {
            for (int i = 0; i < 24; i++) {
                this.coordinator.tick(null);
                this.async.runUntilIdle();
                SharedWorldReleaseCoordinator.ReleaseView view = this.coordinator.view();
                if (view != null
                        && (view.phase() == SharedWorldReleasePhase.COMPLETE
                        || view.phase() == SharedWorldReleasePhase.TERMINATED_DELETED
                        || view.phase() == SharedWorldReleasePhase.TERMINATED_REVOKED
                        || view.phase() == SharedWorldReleasePhase.ERROR_RECOVERABLE)
                        && !this.async.hasPendingWork()) {
                    return;
                }
            }
        }

        public SharedWorldReleasePhase storedPhase() throws IOException {
            SharedWorldReleaseStore.ReleaseRecord record = this.releaseStore.load();
            return record == null ? null : record.phase;
        }

        public HostedWorld hostedWorld() {
            return this.resources.hostedWorld;
        }

        public SharedWorldApiClient ownerClient() {
            return this.resources.hostedWorld.ownerClient();
        }

        public String worldId() {
            return this.resources.hostedWorld.world().id();
        }

        public void corruptWorkingCopy() throws IOException {
            Files.writeString(this.resources.notesPath(), "corrupted");
        }

        public String notesContent() throws IOException {
            return Files.readString(this.resources.notesPath());
        }

        public String stableMarker() throws IOException {
            CompoundTag level = NbtIo.readCompressed(this.resources.levelPath(), NbtAccounter.unlimitedHeap());
            return level.getCompoundOrEmpty("Data").getString("SharedWorldStableMarker").orElse("");
        }

        @Override
        public void close() throws Exception {
            if (this.cleanupOwner) {
                this.resources.close();
            }
        }
    }

    private static final class ReleaseResources implements AutoCloseable {
        private final Path root;
        private final Path managedRoot;
        private final Path releaseStorePath;
        private final HostedWorld hostedWorld;
        private final SharedWorldIntegrationBackend.TestPlayer hostPlayer;
        private final ManagedWorldStore worldStore;
        private final WorldSyncCoordinator syncCoordinator;
        private int uploadCalls;
        private int clearCalls;
        private int beginCalls;
        private int completeCalls;
        private int releaseCalls;
        private boolean coordinatedReleaseStarted;
        private boolean backendFinalizationStarted;

        private ReleaseResources(Path root, HostedWorld hostedWorld) {
            this.root = root;
            this.managedRoot = root.resolve("managed");
            this.releaseStorePath = root.resolve("release-store.json");
            this.hostedWorld = hostedWorld;
            this.hostPlayer = hostedWorld.host();
            this.worldStore = new ManagedWorldStore(this.managedRoot);
            this.syncCoordinator = new WorldSyncCoordinator(hostedWorld.hostClient(), this.worldStore);
        }

        private static ReleaseResources create(HostedWorld hostedWorld) throws Exception {
            Path root = Files.createTempDirectory("sharedworld-release-integration");
            ReleaseResources resources = new ReleaseResources(root, hostedWorld);
            seedWorkingCopy(resources);
            return resources;
        }

        private static void seedWorkingCopy(ReleaseResources resources) throws Exception {
            Path workingCopy = resources.worldStore.workingCopy(resources.hostedWorld.world().id());
            Files.createDirectories(workingCopy.resolve("data"));
            CompoundTag player = new CompoundTag();
            player.putString("SharedWorldPlayerMarker", resources.hostPlayer.playerName());

            CompoundTag data = new CompoundTag();
            data.putString("LevelName", "Release Integration World");
            data.putString("SharedWorldStableMarker", "release-stable");
            data.put("Player", player);

            CompoundTag level = new CompoundTag();
            level.put("Data", data);
            NbtIo.writeCompressed(level, workingCopy.resolve("level.dat"));
            Files.writeString(workingCopy.resolve("data").resolve("notes.txt"), "original-release-notes");
        }

        private Path notesPath() {
            return this.worldStore.workingCopy(this.hostedWorld.world().id()).resolve("data").resolve("notes.txt");
        }

        private Path levelPath() {
            return this.worldStore.workingCopy(this.hostedWorld.world().id()).resolve("level.dat");
        }

        @Override
        public void close() throws IOException {
            deleteTree(this.root);
        }
    }

    public static final class RealReleaseBackend implements SharedWorldReleaseCoordinator.ReleaseBackend {
        private final ReleaseResources resources;

        private RealReleaseBackend(ReleaseResources resources) {
            this.resources = resources;
        }

        @Override
        public SharedWorldModels.WorldRuntimeStatusDto runtimeStatus(String worldId) throws Exception {
            return this.resources.hostedWorld.hostClient().runtimeStatus(worldId);
        }

        @Override
        public void beginFinalization(String worldId, long runtimeEpoch, String hostToken) throws Exception {
            this.resources.beginCalls += 1;
            this.resources.hostedWorld.hostClient().beginFinalization(worldId, runtimeEpoch, hostToken);
        }

        @Override
        public void completeFinalization(String worldId, long runtimeEpoch, String hostToken) throws Exception {
            this.resources.completeCalls += 1;
            this.resources.hostedWorld.hostClient().completeFinalization(worldId, runtimeEpoch, hostToken);
        }

        @Override
        public void releaseHost(String worldId, long runtimeEpoch, String hostToken, boolean graceful) throws Exception {
            this.resources.releaseCalls += 1;
            this.resources.hostedWorld.hostClient().releaseHost(worldId, graceful, runtimeEpoch, hostToken);
        }

        public int beginCalls() {
            return this.resources.beginCalls;
        }

        public int completeCalls() {
            return this.resources.completeCalls;
        }

        public int releaseCalls() {
            return this.resources.releaseCalls;
        }
    }

    public static final class RealHostControl implements SharedWorldReleaseCoordinator.HostControl {
        private final ReleaseResources resources;
        private SharedWorldHostingManager.ActiveHostSession activeHostSession;

        private RealHostControl(ReleaseResources resources, boolean exposeActiveHostSession) {
            this.resources = resources;
            if (exposeActiveHostSession) {
                this.activeHostSession = new SharedWorldHostingManager.ActiveHostSession(
                        resources.hostedWorld.world().id(),
                        resources.hostedWorld.world().name(),
                        resources.hostedWorld.assignment().runtimeEpoch(),
                        resources.hostedWorld.assignment().hostToken(),
                        "join.example"
                );
            }
        }

        @Override
        public SharedWorldHostingManager.ActiveHostSession activeHostSession() {
            return this.activeHostSession;
        }

        @Override
        public boolean isBackgroundSaveInFlight() {
            return false;
        }

        @Override
        public void beginCoordinatedRelease() {
            this.resources.coordinatedReleaseStarted = true;
        }

        @Override
        public void markCoordinatedBackendFinalizationStarted() {
            this.resources.backendFinalizationStarted = true;
        }

        @Override
        public Path finalReleaseWorldDirectory(String worldId) {
            return this.resources.worldStore.workingCopy(worldId);
        }

        @Override
        public SharedWorldModels.SnapshotManifestDto uploadFinalReleaseSnapshot(
                String worldId,
                Path worldDirectory,
                String hostPlayerUuid,
                long runtimeEpoch,
                String hostToken,
                WorldSyncProgressListener progressListener
        ) throws Exception {
            this.resources.uploadCalls += 1;
            return this.resources.syncCoordinator.uploadSnapshot(
                    worldId,
                    worldDirectory,
                    hostPlayerUuid,
                    runtimeEpoch,
                    hostToken,
                    progressListener
            );
        }

        @Override
        public void clearHostedSessionAfterCoordinatedRelease() {
            this.resources.clearCalls += 1;
            this.activeHostSession = null;
        }

        @Override
        public void clearHostedSessionAfterTerminalExit() {
            this.resources.clearCalls += 1;
            this.activeHostSession = null;
        }

        @Override
        public boolean isStartupCancelable() {
            return false;
        }

        public int uploadCalls() {
            return this.resources.uploadCalls;
        }

        public int clearCalls() {
            return this.resources.clearCalls;
        }

        public boolean coordinatedReleaseStarted() {
            return this.resources.coordinatedReleaseStarted;
        }

        public boolean backendFinalizationStarted() {
            return this.resources.backendFinalizationStarted;
        }
    }

    public static final class FileReleaseStore implements SharedWorldReleaseCoordinator.ReleasePersistence {
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private final Path file;

        private FileReleaseStore(Path file) {
            this.file = file;
        }

        @Override
        public SharedWorldReleaseStore.ReleaseRecord load() {
            if (!Files.exists(this.file)) {
                return null;
            }
            try (Reader reader = Files.newBufferedReader(this.file)) {
                return GSON.fromJson(reader, SharedWorldReleaseStore.ReleaseRecord.class);
            } catch (IOException exception) {
                return null;
            }
        }

        @Override
        public SharedWorldReleaseStore.ReleaseRecord loadFor(String worldId, String hostUuid) {
            SharedWorldReleaseStore.ReleaseRecord record = load();
            if (record == null) {
                return null;
            }
            if (!worldId.equalsIgnoreCase(record.worldId) || !hostUuid.equalsIgnoreCase(record.hostUuid)) {
                return null;
            }
            return record;
        }

        @Override
        public void save(SharedWorldReleaseStore.ReleaseRecord record) throws IOException {
            Files.createDirectories(this.file.getParent());
            try (Writer writer = Files.newBufferedWriter(this.file)) {
                GSON.toJson(record, writer);
            }
        }

        @Override
        public void clear() {
            try {
                Files.deleteIfExists(this.file);
            } catch (IOException ignored) {
            }
        }
    }

    private static final class RealSessionBackend implements SharedWorldSessionCoordinator.SessionBackend {
        private final SharedWorldApiClient client;

        private RealSessionBackend(SharedWorldApiClient client) {
            this.client = client;
        }

        @Override
        public SharedWorldModels.EnterSessionResponseDto enterSession(String worldId, String waiterSessionId, boolean acknowledgeUncleanShutdown) throws Exception {
            return this.client.enterSession(worldId, waiterSessionId, acknowledgeUncleanShutdown);
        }

        @Override
        public SharedWorldModels.ObserveWaitingResponseDto observeWaiting(String worldId, String waiterSessionId) throws Exception {
            return this.client.observeWaiting(worldId, waiterSessionId);
        }

        @Override
        public SharedWorldModels.WorldRuntimeStatusDto cancelWaiting(String worldId, String waiterSessionId) throws Exception {
            return this.client.cancelWaiting(worldId, waiterSessionId);
        }

        @Override
        public SharedWorldModels.FinalizationActionResultDto abandonFinalization(String worldId) throws Exception {
            return this.client.abandonFinalization(worldId);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
    }
}
