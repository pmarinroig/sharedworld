package link.sharedworld;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SharedWorldE4mcCompatibility {
    public static final String QUICLIME_SESSION_HOOK_TARGET = "link.e4mc.QuiclimeSession$2$1";

    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld/e4mc-compat");
    private static final Pattern VERSION_TRIPLET_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final String E4MC_MOD_ID = "e4mc";

    private SharedWorldE4mcCompatibility() {
    }

    public static String detectedE4mcVersionOrMissing() {
        return FabricLoader.getInstance()
                .getModContainer(E4MC_MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("missing");
    }

    public static boolean shouldApplyServerboundKeyPacketCompatMixinForDetectedVersion() {
        return shouldApplyServerboundKeyPacketCompatMixin(detectedE4mcVersionOrMissing());
    }

    static boolean shouldApplyServerboundKeyPacketCompatMixin(String detectedVersion) {
        int[] triplet = parseVersionTriplet(detectedVersion);
        if (triplet == null) {
            return false;
        }
        return compareTriplet(triplet, 6, 1, 0) < 0;
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
        if (!FabricLoader.getInstance().isModLoaded(E4MC_MOD_ID)) {
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

    public static void logClientInitStarted() {
        LOGGER.info(
                "SharedWorld e4mc diagnostics [init-start]: detectedVersion={}, quiclimeHookTargetPresent={}",
                detectedE4mcVersionOrMissing(),
                isQuiclimeSessionHookTargetPresent()
        );
    }

    public static void logClientInitFinished() {
        LOGGER.info(
                "SharedWorld e4mc diagnostics [init-complete]: detectedVersion={}, quiclimeHookTargetPresent={}",
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

    public static void logDomainCaptureHookFired(String joinTarget) {
        LOGGER.info("SharedWorld e4mc diagnostics [domain-capture]: joinTarget={}", joinTarget);
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
}
