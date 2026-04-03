package link.sharedworld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SharedWorldListState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("sharedworld-worlds.json");

    private State state = load();

    public synchronized List<WorldSummaryDto> cachedWorlds() {
        return new ArrayList<>(this.state.cachedWorlds);
    }

    public synchronized String selectedWorldId() {
        return this.state.selectedWorldId;
    }

    public synchronized void rememberSelectedWorld(String worldId) {
        this.state.selectedWorldId = worldId;
        save();
    }

    public synchronized List<WorldSummaryDto> orderFreshWorlds(List<WorldSummaryDto> worlds) {
        Map<String, WorldSummaryDto> byId = new LinkedHashMap<>();
        for (WorldSummaryDto world : worlds) {
            byId.put(world.id(), world);
        }

        List<WorldSummaryDto> ordered = new ArrayList<>(worlds.size());
        for (String worldId : this.state.orderedWorldIds) {
            WorldSummaryDto world = byId.remove(worldId);
            if (world != null) {
                ordered.add(world);
            }
        }
        ordered.addAll(byId.values());
        return ordered;
    }

    public synchronized List<WorldSummaryDto> applyFreshWorlds(List<WorldSummaryDto> worlds) {
        List<WorldSummaryDto> ordered = orderFreshWorlds(worlds);
        if (!SharedWorldListComparison.orderedWorldsEqual(this.state.cachedWorlds, ordered)) {
            this.replaceStateWorlds(ordered);
        }
        return new ArrayList<>(this.state.cachedWorlds);
    }

    public synchronized List<WorldSummaryDto> moveWorld(String worldId, int offset) {
        int index = indexOf(worldId);
        int targetIndex = index + offset;
        if (index < 0 || targetIndex < 0 || targetIndex >= this.state.cachedWorlds.size()) {
            return new ArrayList<>(this.state.cachedWorlds);
        }

        WorldSummaryDto world = this.state.cachedWorlds.remove(index);
        this.state.cachedWorlds.add(targetIndex, world);
        this.state.orderedWorldIds = extractIds(this.state.cachedWorlds);
        save();
        return new ArrayList<>(this.state.cachedWorlds);
    }

    public synchronized boolean canMoveWorld(String worldId, int offset) {
        int index = indexOf(worldId);
        int targetIndex = index + offset;
        return index >= 0 && targetIndex >= 0 && targetIndex < this.state.cachedWorlds.size();
    }

    private int indexOf(String worldId) {
        for (int index = 0; index < this.state.cachedWorlds.size(); index++) {
            if (this.state.cachedWorlds.get(index).id().equals(worldId)) {
                return index;
            }
        }
        return -1;
    }

    private void replaceStateWorlds(List<WorldSummaryDto> worlds) {
        this.state.cachedWorlds = new ArrayList<>(worlds);
        this.state.orderedWorldIds = extractIds(worlds);
        if (this.state.selectedWorldId != null
                && this.state.cachedWorlds.stream().noneMatch(world -> world.id().equals(this.state.selectedWorldId))) {
            this.state.selectedWorldId = null;
        }
        save();
    }

    private static List<String> extractIds(List<WorldSummaryDto> worlds) {
        List<String> ids = new ArrayList<>(worlds.size());
        for (WorldSummaryDto world : worlds) {
            ids.add(world.id());
        }
        return ids;
    }

    private static State load() {
        if (!Files.exists(FILE)) {
            return new State();
        }

        try (Reader reader = Files.newBufferedReader(FILE)) {
            State loaded = GSON.fromJson(reader, State.class);
            return loaded == null ? new State() : loaded;
        } catch (IOException exception) {
            SharedWorldClient.LOGGER.warn("Failed to load SharedWorld world list state", exception);
            return new State();
        }
    }

    private void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(this.state, writer);
            }
        } catch (IOException exception) {
            SharedWorldClient.LOGGER.warn("Failed to save SharedWorld world list state", exception);
        }
    }

    private static final class State {
        private List<WorldSummaryDto> cachedWorlds = new ArrayList<>();
        private List<String> orderedWorldIds = new ArrayList<>();
        private String selectedWorldId;
    }
}
