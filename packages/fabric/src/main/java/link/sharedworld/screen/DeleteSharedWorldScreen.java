package link.sharedworld.screen;

import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class DeleteSharedWorldScreen extends Screen {
    private final SharedWorldScreen parent;
    private final WorldSummaryDto world;

    public DeleteSharedWorldScreen(SharedWorldScreen parent, WorldSummaryDto world) {
        super(Component.translatable(isOwner(world)
                ? "screen.sharedworld.delete_title_owner"
                : "screen.sharedworld.delete_title_member"));
        this.parent = parent;
        this.world = world;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.delete"), button -> this.deleteWorld())
                .bounds(this.width / 2 - 155, this.height / 6 + 96, 150, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sharedworld.cancel"), button -> this.minecraft.setScreen(this.parent))
                .bounds(this.width / 2 + 5, this.height / 6 + 96, 150, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.drawCenteredString(
                this.font,
                Component.translatable(isOwner()
                        ? "screen.sharedworld.delete_question_owner"
                        : "screen.sharedworld.delete_question_member", displayName(this.world)),
                this.width / 2,
                84,
                0xFFFFFFFF
        );
        guiGraphics.drawCenteredString(
                this.font,
                Component.translatable(isOwner()
                        ? "screen.sharedworld.delete_detail_owner"
                        : "screen.sharedworld.delete_detail_member"),
                this.width / 2,
                108,
                0xFFB0B0B0
        );
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void deleteWorld() {
        this.minecraft.setScreen(new DeleteSharedWorldProgressScreen(this.parent, this.world));
    }

    private boolean isOwner() {
        return isOwner(this.world);
    }

    private static boolean isOwner(WorldSummaryDto world) {
        String ownerUuid = world.ownerUuid();
        if (ownerUuid == null || ownerUuid.isBlank()) {
            return false;
        }
        return ownerUuid.replace("-", "").equalsIgnoreCase(SharedWorldApiClient.currentPlayerUuid());
    }

    private static String displayName(WorldSummaryDto world) {
        return SharedWorldText.displayWorldName(world.name());
    }
}
