package link.sharedworld.screen;

import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldModels.WorldMembershipDto;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

final class MemberBrowserList extends ObjectSelectionList<MemberBrowserList.Entry> {
    private final EditSharedWorldScreen owner;

    MemberBrowserList(Minecraft minecraft, int width, int height, int y, int itemHeight, EditSharedWorldScreen owner) {
        super(minecraft, width, height, y, itemHeight);
        this.owner = owner;
    }

    void setMembers(List<WorldMembershipDto> memberships, String selectedPlayerUuid) {
        this.clearEntries();
        Entry selected = null;
        for (WorldMembershipDto membership : memberships) {
            Entry entry = new Entry(membership);
            this.addEntry(entry);
            if (selectedPlayerUuid != null && selectedPlayerUuid.equals(membership.playerUuid())) {
                selected = entry;
            }
        }
        Entry resolved = selected == null && !this.children().isEmpty() ? this.children().get(0) : selected;
        this.setSelected(resolved);
        this.owner.onMemberSelected(resolved == null ? null : resolved.membership);
    }

    WorldMembershipDto selectedMember() {
        Entry entry = this.getSelected();
        return entry == null ? null : entry.membership;
    }

    @Override
    public int getRowWidth() {
        return this.width - 12;
    }

    final class Entry extends ObjectSelectionList.Entry<Entry> {
        private final WorldMembershipDto membership;

        Entry(WorldMembershipDto membership) {
            this.membership = membership;
        }

        @Override
        public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int x = this.getContentX();
            int y = this.getContentY();
            guiGraphics.drawString(MemberBrowserList.this.minecraft.font, Component.literal(this.membership.playerName()), x, y + 4, 0xFFFFFFFF);
            String subtitle = SharedWorldText.string("owner".equalsIgnoreCase(this.membership.role())
                    ? "screen.sharedworld.role_owner"
                    : "screen.sharedworld.role_member");
            guiGraphics.drawString(MemberBrowserList.this.minecraft.font, Component.literal(subtitle), x, y + 16, "owner".equalsIgnoreCase(this.membership.role()) ? 0xFFFFD37A : 0xFF9AA8BA);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            MemberBrowserList.this.setSelected(this);
            MemberBrowserList.this.owner.onMemberSelected(this.membership);
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(this.membership.playerName());
        }
    }
}
