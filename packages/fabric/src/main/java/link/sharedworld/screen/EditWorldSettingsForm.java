package link.sharedworld.screen;

import link.sharedworld.SharedWorldClient;
import link.sharedworld.api.SharedWorldModels.WorldSettingsDto;
import link.sharedworld.host.SharedWorldGameRule;
import link.sharedworld.sync.ManagedWorldStore;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The Settings tab's form: difficulty, default game mode, and the managed
 * gamerule toggles. Owns the form state (which survives screen re-inits on
 * resize), the prefill order (saved backend settings, then the local working
 * copy's level.dat, then vanilla defaults), dirty tracking against the loaded
 * baseline, and the tab's buttons. The screen keeps save orchestration,
 * status, and footer wiring.
 */
final class EditWorldSettingsForm {
    private final ManagedWorldStore worldStore;
    private final String worldId;
    private final BooleanSupplier editableByPlayer;
    private final Runnable onChanged;

    private String difficulty = "normal";
    private String gameMode = "survival";
    private final EnumMap<SharedWorldGameRule, Boolean> rules = new EnumMap<>(SharedWorldGameRule.class);
    private WorldSettingsDto loadedSettings;
    private boolean prefillFailed;

    private Button difficultyButton;
    private Button gameModeButton;
    private final EnumMap<SharedWorldGameRule, Button> ruleButtons = new EnumMap<>(SharedWorldGameRule.class);

    EditWorldSettingsForm(ManagedWorldStore worldStore, String worldId, BooleanSupplier editableByPlayer, Runnable onChanged) {
        this.worldStore = worldStore;
        this.worldId = worldId;
        this.editableByPlayer = editableByPlayer;
        this.onChanged = onChanged;
        this.rules.put(SharedWorldGameRule.KEEP_INVENTORY, false);
        this.rules.put(SharedWorldGameRule.MOB_GRIEFING, true);
        this.rules.put(SharedWorldGameRule.DAYLIGHT_CYCLE, true);
        this.rules.put(SharedWorldGameRule.WEATHER_CYCLE, true);
        this.rules.put(SharedWorldGameRule.PVP, true);
    }

    /** Rebuilds the tab's buttons; called from the screen's init (also on resize). */
    void createWidgets() {
        this.difficultyButton = Button.builder(Component.empty(), ignored -> this.cycleDifficulty()).width(170).build();
        this.gameModeButton = Button.builder(Component.empty(), ignored -> this.cycleGameMode()).width(170).build();
        this.ruleButtons.clear();
        for (SharedWorldGameRule rule : SharedWorldGameRule.values()) {
            this.ruleButtons.put(rule, Button.builder(Component.empty(), ignored -> this.toggleRule(rule)).width(170).build());
        }
    }

    void visitWidgets(Consumer<AbstractWidget> consumer) {
        consumer.accept(this.difficultyButton);
        consumer.accept(this.gameModeButton);
        for (Button button : this.ruleButtons.values()) {
            consumer.accept(button);
        }
    }

    void layout(ScreenRectangle area) {
        int columnWidth = Math.min(170, area.width() / 2 - 24);
        int leftColumn = area.left() + (area.width() / 2 - columnWidth) / 2;
        int rightColumn = area.left() + area.width() / 2 + (area.width() / 2 - columnWidth) / 2;
        int top = area.top() + 34;

        this.difficultyButton.setWidth(columnWidth);
        this.difficultyButton.setPosition(leftColumn, top);
        this.gameModeButton.setWidth(columnWidth);
        this.gameModeButton.setPosition(leftColumn, top + 24);

        int y = top;
        for (Button button : this.ruleButtons.values()) {
            button.setWidth(columnWidth);
            button.setPosition(rightColumn, y);
            y += 24;
        }
    }

    /**
     * Prefill order: saved backend settings, then the local working copy's
     * level.dat (difficulty/game mode only — their NBT shape is stable across
     * versions, unlike gamerule keys), then vanilla defaults.
     */
    void populate(WorldSettingsDto saved) {
        if (saved == null || saved.difficulty() == null || saved.defaultGameMode() == null) {
            this.prefillFromLevelDat();
        }
        if (saved != null) {
            if (saved.difficulty() != null) {
                this.difficulty = saved.difficulty();
            }
            if (saved.defaultGameMode() != null) {
                this.gameMode = saved.defaultGameMode();
            }
            if (saved.gamerules() != null) {
                for (var entry : saved.gamerules().entrySet()) {
                    SharedWorldGameRule rule = SharedWorldGameRule.byId(entry.getKey());
                    if (rule != null && entry.getValue() != null) {
                        this.rules.put(rule, entry.getValue());
                    }
                }
            }
        }
        this.loadedSettings = this.currentDto();
    }

    private void prefillFromLevelDat() {
        boolean levelDatExists = false;
        try {
            Path levelDat = this.worldStore.workingCopy(this.worldId).resolve("level.dat");
            if (!Files.isRegularFile(levelDat)) {
                return;
            }
            levelDatExists = true;
            var data = link.sharedworld.versioned.NbtCompat.getCompoundOrEmpty(
                    link.sharedworld.versioned.NbtCompat.readCompressed(levelDat), "Data");
            byte difficultyByte = link.sharedworld.versioned.NbtCompat.getByteOr(data, "Difficulty", (byte) 2);
            this.difficulty = switch (difficultyByte) {
                case 0 -> "peaceful";
                case 1 -> "easy";
                case 3 -> "hard";
                default -> "normal";
            };
            int gameType = link.sharedworld.versioned.NbtCompat.getIntOr(data, "GameType", 0);
            this.gameMode = switch (gameType) {
                case 1 -> "creative";
                case 2 -> "adventure";
                default -> "survival";
            };
        } catch (Exception exception) {
            if (levelDatExists) {
                // An existing level.dat that cannot be read is NOT "defaults":
                // flag it so the settings tab warns and refuses to save what
                // would silently replace the world's real values.
                this.prefillFailed = true;
                SharedWorldClient.LOGGER.warn("SharedWorld could not read level.dat for {}", this.worldId, exception);
            }
            // Otherwise: no local copy yet (never synced here), defaults stand.
        }
    }

    WorldSettingsDto currentDto() {
        Map<String, Boolean> gamerules = new LinkedHashMap<>();
        for (var entry : this.rules.entrySet()) {
            gamerules.put(entry.getKey().id(), entry.getValue());
        }
        return new WorldSettingsDto(this.difficulty, this.gameMode, gamerules);
    }

    boolean dirty() {
        return this.loadedSettings != null && !this.currentDto().equals(this.loadedSettings);
    }

    boolean prefillFailed() {
        return this.prefillFailed;
    }

    /** The given dto was accepted by the backend; it is the new clean baseline. */
    void markSaved(WorldSettingsDto dto) {
        this.loadedSettings = dto;
    }

    private void cycleDifficulty() {
        if (!this.editableByPlayer.getAsBoolean()) {
            return;
        }
        List<String> order = List.of("peaceful", "easy", "normal", "hard");
        int index = order.indexOf(this.difficulty);
        this.difficulty = order.get((index + 1) % order.size());
        this.onChanged.run();
    }

    private void cycleGameMode() {
        if (!this.editableByPlayer.getAsBoolean()) {
            return;
        }
        List<String> order = List.of("survival", "creative", "adventure");
        int index = order.indexOf(this.gameMode);
        this.gameMode = order.get((index + 1) % order.size());
        this.onChanged.run();
    }

    private void toggleRule(SharedWorldGameRule rule) {
        if (!this.editableByPlayer.getAsBoolean()) {
            return;
        }
        this.rules.put(rule, !Boolean.TRUE.equals(this.rules.get(rule)));
        this.onChanged.run();
    }

    void updateWidgets(boolean editable) {
        this.difficultyButton.setMessage(Component.translatable(
                "screen.sharedworld.settings_difficulty",
                Component.translatable(difficultyValueKey(this.difficulty))));
        this.difficultyButton.active = editable;
        this.gameModeButton.setMessage(Component.translatable(
                "screen.sharedworld.settings_game_mode",
                Component.translatable(gameModeValueKey(this.gameMode))));
        this.gameModeButton.active = editable;
        for (var entry : this.ruleButtons.entrySet()) {
            boolean value = Boolean.TRUE.equals(this.rules.get(entry.getKey()));
            entry.getValue().setMessage(Component.translatable(
                    gameRuleLabelKey(entry.getKey()),
                    Component.translatable(value ? "screen.sharedworld.settings_value_on" : "screen.sharedworld.settings_value_off")));
            entry.getValue().active = editable;
        }
    }

    // Full-literal keys (never built from fragments): the localization parity
    // test resolves every referenced key against the lang files.
    private static String difficultyValueKey(String difficulty) {
        return switch (difficulty) {
            case "peaceful" -> "screen.sharedworld.settings_value_peaceful";
            case "easy" -> "screen.sharedworld.settings_value_easy";
            case "hard" -> "screen.sharedworld.settings_value_hard";
            default -> "screen.sharedworld.settings_value_normal";
        };
    }

    private static String gameModeValueKey(String gameMode) {
        return switch (gameMode) {
            case "creative" -> "screen.sharedworld.settings_value_creative";
            case "adventure" -> "screen.sharedworld.settings_value_adventure";
            default -> "screen.sharedworld.settings_value_survival";
        };
    }

    private static String gameRuleLabelKey(SharedWorldGameRule rule) {
        return switch (rule) {
            case KEEP_INVENTORY -> "screen.sharedworld.settings_rule_keepInventory";
            case MOB_GRIEFING -> "screen.sharedworld.settings_rule_mobGriefing";
            case DAYLIGHT_CYCLE -> "screen.sharedworld.settings_rule_daylightCycle";
            case WEATHER_CYCLE -> "screen.sharedworld.settings_rule_weatherCycle";
            case PVP -> "screen.sharedworld.settings_rule_pvp";
        };
    }
}
