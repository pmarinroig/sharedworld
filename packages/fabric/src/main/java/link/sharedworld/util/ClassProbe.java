package link.sharedworld.util;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Answers "does this class, as loaded from the classpath, define this method?"
 * without initializing it: the gate for mixins into other mods, whose
 * internals may move between their releases.
 */
public final class ClassProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClassProbe.class);

    private ClassProbe() {
    }

    /** {@code methodDescriptor} may be null to accept any overload of {@code methodName}. */
    public static boolean definesMethod(String className, ClassLoader classLoader, String methodName, String methodDescriptor) {
        String resourcePath = className.replace('.', '/') + ".class";
        try (InputStream inputStream = classLoader.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                return false;
            }
            return bytesDefineMethod(inputStream.readAllBytes(), methodName, methodDescriptor);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("SharedWorld could not inspect class {}", className, exception);
            return false;
        }
    }

    static boolean bytesDefineMethod(byte[] classBytes, String methodName, String methodDescriptor) {
        if (classBytes == null || classBytes.length == 0) {
            return false;
        }
        boolean[] found = {false};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (methodName.equals(name) && (methodDescriptor == null || methodDescriptor.equals(descriptor))) {
                    found[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }
}
