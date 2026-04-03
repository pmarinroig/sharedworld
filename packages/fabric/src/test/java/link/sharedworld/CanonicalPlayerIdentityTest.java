package link.sharedworld;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CanonicalPlayerIdentityTest {
    @Test
    void acceptsMatchingAssignedAndCurrentBackendUuid() {
        String resolved = CanonicalPlayerIdentity.canonicalUuidForAssignment(
                "11111111111111111111111111111111",
                "11111111-1111-1111-1111-111111111111"
        );

        assertEquals("11111111-1111-1111-1111-111111111111", resolved);
    }

    @Test
    void rejectsDivergentAssignedAndCurrentBackendUuid() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> CanonicalPlayerIdentity.canonicalUuidForAssignment(
                        "11111111111111111111111111111111",
                        "22222222-2222-2222-2222-222222222222"
                )
        );

        assertEquals(
                "SharedWorld backend assigned a host/player UUID that does not match the current authenticated SharedWorld identity. SharedWorld will not continue because using another player's UUID can corrupt playerdata and handoff state.",
                error.getMessage()
        );
    }
}
