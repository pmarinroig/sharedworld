package link.sharedworld.integration;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

public final class E4mcDomainTracker {
    private static volatile String currentJoinTarget;
    private static volatile String pendingSuppressedMessageTarget;

    private E4mcDomainTracker() {
    }

    public static void clear() {
        currentJoinTarget = null;
        pendingSuppressedMessageTarget = null;
    }

    public static String currentJoinTarget() {
        return currentJoinTarget;
    }

    public static void captureAssignedDomain(String joinTarget) {
        if (joinTarget == null || joinTarget.isBlank()) {
            return;
        }
        currentJoinTarget = joinTarget.trim();
        pendingSuppressedMessageTarget = currentJoinTarget;
    }

    public static void observeMessage(Component message) {
        String discovered = findCopyToClipboardValue(message);
        if (discovered != null && !discovered.isBlank()) {
            currentJoinTarget = discovered.trim();
        }
    }

    public static boolean shouldSuppressMessage(Component message) {
        String pending = pendingSuppressedMessageTarget;
        if (pending == null || pending.isBlank()) {
            return false;
        }

        String discovered = findCopyToClipboardValue(message);
        if (discovered != null && pending.equals(discovered.trim())) {
            pendingSuppressedMessageTarget = null;
            currentJoinTarget = pending;
            return true;
        }
        return false;
    }

    private static String findCopyToClipboardValue(Component component) {
        Style style = component.getStyle();
        ClickEvent clickEvent = style.getClickEvent();
        if (clickEvent instanceof ClickEvent.CopyToClipboard copyToClipboard) {
            return copyToClipboard.value();
        }

        for (Component sibling : component.getSiblings()) {
            String nested = findCopyToClipboardValue(sibling);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}
