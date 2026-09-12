package link.sharedworld.mixin;

import link.sharedworld.SharedWorldE4mcCompatibility;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class SharedWorldMixinConfigPlugin implements IMixinConfigPlugin {
    private static final String SERVERBOUND_KEY_PACKET_COMPAT_MIXIN = "link.sharedworld.mixin.ServerboundKeyPacketCompatMixin";
    private static final String SINGLEPLAYER_OWNER_INTERMEDIARY_COMPAT_MIXIN =
            "link.sharedworld.mixin.E4mcSingleplayerOwnerCompatIntermediaryMixin";
    private static final String SINGLEPLAYER_OWNER_NAMED_COMPAT_MIXIN =
            "link.sharedworld.mixin.E4mcSingleplayerOwnerCompatNamedMixin";
    private static final String XAERO_WORLD_MAP_ROOT_MIXIN = "link.sharedworld.mixin.XaeroWorldMapRootIdMixin";
    private static final String XAERO_MINIMAP_CONTAINER_MIXIN = "link.sharedworld.mixin.XaeroMinimapContainerMixin";
    private static final String XAERO_MINIMAP_NODE_MIXIN = "link.sharedworld.mixin.XaeroMinimapWorldNodeMixin";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (SERVERBOUND_KEY_PACKET_COMPAT_MIXIN.equals(mixinClassName)) {
            boolean shouldApply = SharedWorldE4mcCompatibility.shouldApplyServerboundKeyPacketCompatMixinForDetectedVersion();
            SharedWorldE4mcCompatibility.logServerboundKeyPacketCompatDecision(shouldApply);
            return shouldApply;
        }

        SharedWorldE4mcCompatibility.SingleplayerOwnerCompatTarget detectedTarget =
                SharedWorldE4mcCompatibility.detectedSingleplayerOwnerCompatTargetForDetectedRuntime();
        if (SINGLEPLAYER_OWNER_INTERMEDIARY_COMPAT_MIXIN.equals(mixinClassName)) {
            boolean shouldApply = shouldApplySingleplayerOwnerCompatMixin(mixinClassName, detectedTarget);
            SharedWorldE4mcCompatibility.logSingleplayerOwnerCompatDecision(detectedTarget, mixinClassName, shouldApply);
            return shouldApply;
        }

        if (SINGLEPLAYER_OWNER_NAMED_COMPAT_MIXIN.equals(mixinClassName)) {
            boolean shouldApply = shouldApplySingleplayerOwnerCompatMixin(mixinClassName, detectedTarget);
            SharedWorldE4mcCompatibility.logSingleplayerOwnerCompatDecision(detectedTarget, mixinClassName, shouldApply);
            return shouldApply;
        }

        // Only XaeroHookTargets here: it has no Minecraft references, so
        // consulting it while Mixin transforms Minecraft cannot re-enter.
        if (XAERO_WORLD_MAP_ROOT_MIXIN.equals(mixinClassName)) {
            return XaeroHookTargets.present(
                    XaeroHookTargets.WORLD_MAP_ROOT_TARGET, XaeroHookTargets.WORLD_MAP_ROOT_METHOD, XaeroHookTargets.WORLD_MAP_ROOT_DESCRIPTOR);
        }
        if (XAERO_MINIMAP_CONTAINER_MIXIN.equals(mixinClassName)) {
            return XaeroHookTargets.present(
                    XaeroHookTargets.MINIMAP_CONTAINER_TARGET, XaeroHookTargets.MINIMAP_CONTAINER_METHOD, XaeroHookTargets.MINIMAP_CONTAINER_DESCRIPTOR);
        }
        if (XAERO_MINIMAP_NODE_MIXIN.equals(mixinClassName)) {
            return XaeroHookTargets.present(XaeroHookTargets.MINIMAP_NODE_TARGET, XaeroHookTargets.MINIMAP_NODE_METHOD, null);
        }

        return true;
    }

    static boolean shouldApplySingleplayerOwnerCompatMixin(
            String mixinClassName,
            SharedWorldE4mcCompatibility.SingleplayerOwnerCompatTarget detectedTarget
    ) {
        return switch (detectedTarget) {
            case INTERMEDIARY -> SINGLEPLAYER_OWNER_INTERMEDIARY_COMPAT_MIXIN.equals(mixinClassName);
            case NAMED -> SINGLEPLAYER_OWNER_NAMED_COMPAT_MIXIN.equals(mixinClassName);
            case MISSING -> false;
        };
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
