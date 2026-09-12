package link.sharedworld;

import link.sharedworld.host.SharedWorldHostPermissionPolicy;
import link.sharedworld.platform.SharedWorldPlatform;
import link.sharedworld.util.ClassProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SharedWorldE4mcCompatibility {
    public static final String QUICLIME_SESSION_HOOK_TARGET = "link.e4mc.QuiclimeSession$2$1";
    public static final String E4MC_SINGLEPLAYER_OWNER_INTERMEDIARY_DESCRIPTOR =
            "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/class_3222;)Z";
    public static final String E4MC_SINGLEPLAYER_OWNER_NAMED_DESCRIPTOR =
            "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerPlayer;)Z";

    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld/e4mc-compat");
    private static final Pattern VERSION_TRIPLET_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    /** Fabric ships e4mc under "e4mc"; the NeoForge artifact may use "e4mc_minecraft". */
    private static final String[] E4MC_MOD_IDS = {"e4mc", "e4mc_minecraft"};
    private static final String E4MC_MIRROR_TARGET = "link.e4mc.Mirror";
    private static final String E4MC_SINGLEPLAYER_OWNER_METHOD_NAME = "isSingleplayerOwner";

    private SharedWorldE4mcCompatibility() {
    }

    public static boolean isE4mcPresent() {
        for (String modId : E4MC_MOD_IDS) {
            if (SharedWorldPlatform.get().isModLoaded(modId)) {
                return true;
            }
        }
        return false;
    }

    public enum SingleplayerOwnerCompatTarget {
        INTERMEDIARY,
        NAMED,
        MISSING
    }

    public static String detectedE4mcVersionOrMissing() {
        for (String modId : E4MC_MOD_IDS) {
            java.util.Optional<String> version = SharedWorldPlatform.get().modVersion(modId);
            if (version.isPresent()) {
                return version.get();
            }
        }
        return "missing";
    }

    public static boolean shouldApplyServerboundKeyPacketCompatMixinForDetectedVersion() {
        return shouldApplyServerboundKeyPacketCompatMixin(detectedE4mcVersionOrMissing());
    }

    public static SingleplayerOwnerCompatTarget detectedSingleplayerOwnerCompatTargetForDetectedRuntime() {
        return detectSingleplayerOwnerCompatTarget(
                E4MC_MIRROR_TARGET,
                SharedWorldE4mcCompatibility.class.getClassLoader(),
                E4MC_SINGLEPLAYER_OWNER_INTERMEDIARY_DESCRIPTOR,
                E4MC_SINGLEPLAYER_OWNER_NAMED_DESCRIPTOR
        );
    }

    static boolean shouldApplyServerboundKeyPacketCompatMixin(String detectedVersion) {
        int[] triplet = parseVersionTriplet(detectedVersion);
        if (triplet == null) {
            return false;
        }
        return compareTriplet(triplet, 6, 1, 0) < 0;
    }

    static SingleplayerOwnerCompatTarget detectSingleplayerOwnerCompatTarget(
            String targetClassName,
            ClassLoader classLoader,
            String intermediaryDescriptor,
            String namedDescriptor
    ) {
        boolean hasIntermediaryHook = classResourceDefinesMethod(
                targetClassName,
                classLoader,
                E4MC_SINGLEPLAYER_OWNER_METHOD_NAME,
                intermediaryDescriptor
        );
        if (hasIntermediaryHook) {
            return SingleplayerOwnerCompatTarget.INTERMEDIARY;
        }

        boolean hasNamedHook = classResourceDefinesMethod(
                targetClassName,
                classLoader,
                E4MC_SINGLEPLAYER_OWNER_METHOD_NAME,
                namedDescriptor
        );
        if (hasNamedHook) {
            return SingleplayerOwnerCompatTarget.NAMED;
        }

        return SingleplayerOwnerCompatTarget.MISSING;
    }

    static int[] parseVersionTriplet(String detectedVersion) {
        if (detectedVersion == null || detectedVersion.isBlank()) {
            return null;
        }

        Matcher matcher = VERSION_TRIPLET_PATTERN.matcher(detectedVersion);
        if (!matcher.find()) {
            return null;
        }

        return new int[]{
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))
        };
    }

    public static boolean isQuiclimeSessionHookTargetPresent() {
        if (!isE4mcPresent()) {
            return false;
        }

        try {
            Class.forName(QUICLIME_SESSION_HOOK_TARGET, false, SharedWorldE4mcCompatibility.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError exception) {
            LOGGER.warn("SharedWorld could not resolve e4mc hook target {}", QUICLIME_SESSION_HOOK_TARGET, exception);
            return false;
        }
    }

    /** phase is "init-start" or "init-complete"; support greps for both tags. */
    public static void logClientInitPhase(String phase) {
        LOGGER.info(
                "SharedWorld e4mc diagnostics [{}]: detectedVersion={}, quiclimeHookTargetPresent={}",
                phase,
                detectedE4mcVersionOrMissing(),
                isQuiclimeSessionHookTargetPresent()
        );
    }

    public static void logServerboundKeyPacketCompatDecision(boolean shouldApply) {
        LOGGER.info(
                "SharedWorld e4mc diagnostics [mixin-gate]: detectedVersion={}, serverboundKeyPacketCompatMixinEnabled={}",
                detectedE4mcVersionOrMissing(),
                shouldApply
        );
    }

    public static void logSingleplayerOwnerCompatDecision(
            SingleplayerOwnerCompatTarget detectedTarget,
            String mixinName,
            boolean shouldApply
    ) {
        LOGGER.info(
                "SharedWorld e4mc diagnostics [mixin-gate]: detectedVersion={}, singleplayerOwnerCompatTarget={}, mixin={}, enabled={}",
                detectedE4mcVersionOrMissing(),
                detectedTarget,
                mixinName,
                shouldApply
        );
    }

    public static void logDomainCaptureHookFired(String joinTarget) {
        LOGGER.info("SharedWorld e4mc diagnostics [domain-capture]: joinTarget={}", joinTarget);
    }

    public static boolean shouldTreatPlayerAsSharedWorldOwnerForE4mc(String playerUuid) {
        return SharedWorldHostPermissionPolicy.hasSharedWorldOwnerPermissions(
                SharedWorldDevSessionBridge.isHostingSharedWorld(),
                playerUuid,
                SharedWorldDevSessionBridge.hostingSharedWorldOwnerUuid()
        );
    }

    private static int compareTriplet(int[] current, int major, int minor, int patch) {
        if (current[0] != major) {
            return Integer.compare(current[0], major);
        }
        if (current[1] != minor) {
            return Integer.compare(current[1], minor);
        }
        return Integer.compare(current[2], patch);
    }

    private static boolean classResourceDefinesMethod(
            String targetClassName,
            ClassLoader classLoader,
            String methodName,
            String methodDescriptor
    ) {
        return ClassProbe.definesMethod(targetClassName, classLoader, methodName, methodDescriptor);
    }

}
