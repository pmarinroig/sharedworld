package link.sharedworld.integration;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class E4mcDomainTrackerTest {
    @BeforeEach
    void reset() {
        E4mcDomainTracker.clear();
    }

    private static Component clipboardMessage(String value) {
        MutableComponent component = Component.literal("join at " + value);
        return component.withStyle(style -> style.withClickEvent(link.sharedworld.versioned.ClickEventCompat.copyToClipboard(value)));
    }

    @Test
    void interceptSuppressesTheEchoOfAnInjectedJoinTargetExactlyOnce() {
        E4mcDomainTracker.captureAssignedDomain("play.example.net");

        assertTrue(E4mcDomainTracker.interceptMessage(clipboardMessage("play.example.net")),
                "the first echo of the captured target must be suppressed");
        assertFalse(E4mcDomainTracker.interceptMessage(clipboardMessage("play.example.net")),
                "later repeats are ordinary messages");
        assertEquals("play.example.net", E4mcDomainTracker.currentJoinTarget());
    }

    @Test
    void interceptObservesClipboardTargetsFromOrdinaryMessages() {
        assertFalse(E4mcDomainTracker.interceptMessage(clipboardMessage("relay.example.org")),
                "nothing pending, so the message must pass through");
        assertEquals("relay.example.org", E4mcDomainTracker.currentJoinTarget());
    }

    @Test
    void interceptIgnoresMessagesWithoutClipboardTargets() {
        assertFalse(E4mcDomainTracker.interceptMessage(Component.literal("plain chat line")));
        assertEquals(null, E4mcDomainTracker.currentJoinTarget());
    }

    @Test
    void pinnedJoinTargetSurvivesE4mcCaptureAndChatObservation() {
        E4mcDomainTracker.pinJoinTarget(" 100.64.0.12:25565 ");
        assertEquals("100.64.0.12:25565", E4mcDomainTracker.currentJoinTarget());

        E4mcDomainTracker.captureAssignedDomain("play.example.net");
        assertEquals("100.64.0.12:25565", E4mcDomainTracker.currentJoinTarget());

        assertFalse(E4mcDomainTracker.interceptMessage(clipboardMessage("play.example.net")),
                "an e4mc chat line while pinned is an ordinary message");
        assertEquals("100.64.0.12:25565", E4mcDomainTracker.currentJoinTarget());
    }

    @Test
    void clearUnpinsTheJoinTarget() {
        E4mcDomainTracker.pinJoinTarget("100.64.0.12:25565");
        E4mcDomainTracker.clear();

        assertEquals(null, E4mcDomainTracker.currentJoinTarget());
        E4mcDomainTracker.captureAssignedDomain("play.example.net");
        assertEquals("play.example.net", E4mcDomainTracker.currentJoinTarget());
    }
}
