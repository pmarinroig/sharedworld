package link.sharedworld.integration;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

public final class E4mcDomainTracker {
    private static volatile String currentJoinTarget;
    private static volatile String pendingSuppressedMessageTarget;
    private static volatile boolean pinned;

    private E4mcDomainTracker() {
    }

    public static void clear() {
        currentJoinTarget = null;
        pendingSuppressedMessageTarget = null;
        pinned = false;
    }

    public static String currentJoinTarget() {
        return currentJoinTarget;
    }

    /**
     * Custom-join-address hosting: fix the join target so a concurrently
     * running e4mc tunnel (its mixin capture or chat message) can never
     * overwrite it. Cleared with {@link #clear()}.
     */
    public static void pinJoinTarget(String joinTarget) {
        if (joinTarget == null || joinTarget.isBlank()) {
            return;
        }
        currentJoinTarget = joinTarget.trim();
        pendingSuppressedMessageTarget = null;
        pinned = true;
    }

    public static void captureAssignedDomain(String joinTarget) {
        if (pinned || joinTarget == null || joinTarget.isBlank()) {
            return;
        }
        currentJoinTarget = joinTarget.trim();
        pendingSuppressedMessageTarget = currentJoinTarget;
    }

    public static void observeMessage(Component message) {
        if (pinned) {
            return;
        }
        String discovered = findCopyToClipboardValue(message);
        if (discovered != null && !discovered.isBlank()) {
            currentJoinTarget = discovered.trim();
        }
    }

    /**
     * Single entry point for the per-bucket chat mixins: suppression wins,
     * otherwise the message is observed for a copy-to-clipboard join target.
     * Returns true when the message must be cancelled.
     */
    public static boolean interceptMessage(Component message) {
        if (shouldSuppressMessage(message)) {
            return true;
        }
        observeMessage(message);
        return false;
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
        if (clickEvent != null) {
            String copyToClipboardValue = link.sharedworld.versioned.ClickEventCompat.copyToClipboardValue(clickEvent);
            if (copyToClipboardValue != null) {
                return copyToClipboardValue;
            }
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
