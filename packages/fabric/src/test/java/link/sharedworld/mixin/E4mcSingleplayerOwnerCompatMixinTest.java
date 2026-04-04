package link.sharedworld.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class E4mcSingleplayerOwnerCompatMixinTest {
    private static final String INTERMEDIARY_DESCRIPTOR =
            "isSingleplayerOwner(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/class_3222;)Z";
    private static final String NAMED_DESCRIPTOR =
            "isSingleplayerOwner(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerPlayer;)Z";

    @Test
    void intermediaryMixinTargetsPackagedRuntimeDescriptor() throws NoSuchMethodException {
        assertInjectTarget(
                E4mcSingleplayerOwnerCompatIntermediaryMixin.class,
                INTERMEDIARY_DESCRIPTOR
        );
    }

    @Test
    void namedMixinTargetsLoomRuntimeDescriptor() throws NoSuchMethodException {
        assertInjectTarget(
                E4mcSingleplayerOwnerCompatNamedMixin.class,
                NAMED_DESCRIPTOR
        );
    }

    @Test
    void mixinsDeclareNoNonPrivateStaticFields() {
        assertEquals(0, countNonPrivateStaticFields(E4mcSingleplayerOwnerCompatIntermediaryMixin.class));
        assertEquals(0, countNonPrivateStaticFields(E4mcSingleplayerOwnerCompatNamedMixin.class));
    }

    private static void assertInjectTarget(Class<?> mixinClass, String expectedDescriptor) throws NoSuchMethodException {
        Method handler = mixinClass.getDeclaredMethod(
                "sharedworld$applySharedWorldOwnerCheck",
                MinecraftServer.class,
                ServerPlayer.class,
                CallbackInfoReturnable.class
        );

        Inject inject = handler.getDeclaredAnnotation(Inject.class);
        assertNotNull(inject);
        assertArrayEquals(new String[]{expectedDescriptor}, inject.method());
    }

    private static long countNonPrivateStaticFields(Class<?> mixinClass) {
        long count = 0;
        for (Field field : mixinClass.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && !Modifier.isPrivate(modifiers)) {
                count++;
            }
        }
        return count;
    }
}
