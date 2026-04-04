package link.sharedworld;

import link.sharedworld.host.SharedWorldHostPermissionPolicy;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
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
    private static final String E4MC_MOD_ID = "e4mc";
    private static final String E4MC_MIRROR_TARGET = "link.e4mc.Mirror";
    private static final String E4MC_SINGLEPLAYER_OWNER_METHOD_NAME = "isSingleplayerOwner";

    private SharedWorldE4mcCompatibility() {
    }

    public enum SingleplayerOwnerCompatTarget {
        INTERMEDIARY,
        NAMED,
        MISSING
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
        String resourcePath = targetClassName.replace('.', '/') + ".class";
        try (InputStream inputStream = classLoader.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                return false;
            }
            return classBytesDefineMethod(inputStream.readAllBytes(), methodName, methodDescriptor);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("SharedWorld could not inspect e4mc class {}", targetClassName, exception);
            return false;
        }
    }

    private static boolean classBytesDefineMethod(byte[] classBytes, String methodName, String methodDescriptor) {
        if (classBytes == null || classBytes.length == 0) {
            return false;
        }

        boolean[] found = {false};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String currentMethodName,
                    String currentMethodDescriptor,
                    String signature,
                    String[] exceptions
            ) {
                if (methodName.equals(currentMethodName) && methodDescriptor.equals(currentMethodDescriptor)) {
                    found[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }
}
