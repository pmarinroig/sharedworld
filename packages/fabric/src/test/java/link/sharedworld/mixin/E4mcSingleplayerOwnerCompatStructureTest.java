package link.sharedworld.mixin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class E4mcSingleplayerOwnerCompatStructureTest {
    @Test
    void noCompatHelperClassRemainsInMixinPackage() {
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("link.sharedworld.mixin.E4mcSingleplayerOwnerCompatHooks")
        );
    }
}
