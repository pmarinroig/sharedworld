package link.sharedworld.mixin;

import link.sharedworld.SharedWorldE4mcCompatibility;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldMixinConfigPluginTest {
    @Test
    void intermediaryTargetOnlyEnablesIntermediaryMixin() {
        assertTrue(SharedWorldMixinConfigPlugin.shouldApplySingleplayerOwnerCompatMixin(
                "link.sharedworld.mixin.E4mcSingleplayerOwnerCompatIntermediaryMixin",
                SharedWorldE4mcCompatibility.SingleplayerOwnerCompatTarget.INTERMEDIARY
        ));
        assertFalse(SharedWorldMixinConfigPlugin.shouldApplySingleplayerOwnerCompatMixin(
                "link.sharedworld.mixin.E4mcSingleplayerOwnerCompatNamedMixin",
                SharedWorldE4mcCompatibility.SingleplayerOwnerCompatTarget.INTERMEDIARY
        ));
    }

    @Test
    void namedTargetOnlyEnablesNamedMixin() {
        assertFalse(SharedWorldMixinConfigPlugin.shouldApplySingleplayerOwnerCompatMixin(
                "link.sharedworld.mixin.E4mcSingleplayerOwnerCompatIntermediaryMixin",
                SharedWorldE4mcCompatibility.SingleplayerOwnerCompatTarget.NAMED
        ));
        assertTrue(SharedWorldMixinConfigPlugin.shouldApplySingleplayerOwnerCompatMixin(
                "link.sharedworld.mixin.E4mcSingleplayerOwnerCompatNamedMixin",
                SharedWorldE4mcCompatibility.SingleplayerOwnerCompatTarget.NAMED
        ));
    }

    @Test
    void missingTargetDisablesBothMixins() {
        assertFalse(SharedWorldMixinConfigPlugin.shouldApplySingleplayerOwnerCompatMixin(
                "link.sharedworld.mixin.E4mcSingleplayerOwnerCompatIntermediaryMixin",
                SharedWorldE4mcCompatibility.SingleplayerOwnerCompatTarget.MISSING
        ));
        assertFalse(SharedWorldMixinConfigPlugin.shouldApplySingleplayerOwnerCompatMixin(
                "link.sharedworld.mixin.E4mcSingleplayerOwnerCompatNamedMixin",
                SharedWorldE4mcCompatibility.SingleplayerOwnerCompatTarget.MISSING
        ));
    }
}
