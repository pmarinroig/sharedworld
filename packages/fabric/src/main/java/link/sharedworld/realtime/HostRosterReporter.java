package link.sharedworld.realtime;

import link.sharedworld.api.SharedWorldModels.RoomPlayerDto;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Host-side room presence (0.3.0): polls the integrated server's player list
 * locally (free) and pushes the FULL roster over the realtime channel only
 * when it changes; the same local-poll/push-on-change pattern as gamerule
 * detection, so no per-bucket event hooks are needed. The backend
 * coordinator treats the roster as authoritative for "who is online in the
 * server" while this hosting session reports one.
 *
 * [P9] The server is only ever read behind SharedWorldHostServerGate: a
 * vanilla singleplayer world is never observed.
 */
public final class HostRosterReporter {
    private static final long LOCAL_POLL_INTERVAL_MS = 1_000L;

    /** Seam for the channel send; prod wires SharedWorldPushChannel. */
    public interface RosterSender {
        void sendHostPlayers(String worldId, long runtimeEpoch, List<RoomPlayerDto> players);
    }

    private final Supplier<String> runningWorldId;
    private final LongSupplier runtimeEpoch;
    private final BooleanSupplier channelConnected;
    private final RosterSender sender;
    private long lastPollAt;
    private List<RoomPlayerDto> lastSentRoster;
    private String lastWorldId;

    public HostRosterReporter(
            Supplier<String> runningWorldId,
            LongSupplier runtimeEpoch,
            BooleanSupplier channelConnected,
            RosterSender sender
    ) {
        this.runningWorldId = runningWorldId;
        this.runtimeEpoch = runtimeEpoch;
        this.channelConnected = channelConnected;
        this.sender = sender;
    }

    /** Main-thread client tick entry. */
    public void tick(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || !link.sharedworld.host.SharedWorldHostServerGate.isManagedSharedWorldHost(server)) {
            reset();
            return;
        }
        maybeReport(readRoster(server), System.currentTimeMillis());
    }

    /** Core decision logic, unit-tested without a Minecraft server. */
    void maybeReport(List<RoomPlayerDto> roster, long now) {
        String worldId = this.runningWorldId.get();
        if (worldId == null) {
            reset();
            return;
        }
        if (!this.channelConnected.getAsBoolean()) {
            // Forget what was sent so a reconnect (or a coordinator that lost
            // its roster in a deploy) gets the full picture again.
            this.lastSentRoster = null;
            return;
        }
        if (!worldId.equals(this.lastWorldId)) {
            this.lastWorldId = worldId;
            this.lastSentRoster = null;
            this.lastPollAt = 0L;
        }
        if (now - this.lastPollAt < LOCAL_POLL_INTERVAL_MS) {
            return;
        }
        this.lastPollAt = now;
        if (roster.equals(this.lastSentRoster)) {
            return;
        }
        this.sender.sendHostPlayers(worldId, this.runtimeEpoch.getAsLong(), roster);
        this.lastSentRoster = roster;
    }

    private void reset() {
        this.lastSentRoster = null;
        this.lastWorldId = null;
        this.lastPollAt = 0L;
    }

    /** Stable order so roster equality means roster identity. */
    static List<RoomPlayerDto> readRoster(MinecraftServer server) {
        List<RoomPlayerDto> roster = new ArrayList<>();
        var playerList = server.getPlayerList();
        if (playerList == null) {
            return roster;
        }
        for (var player : playerList.getPlayers()) {
            roster.add(new RoomPlayerDto(player.getUUID().toString(), player.getName().getString()));
        }
        roster.sort(Comparator.comparing(RoomPlayerDto::playerUuid));
        return roster;
    }
}
