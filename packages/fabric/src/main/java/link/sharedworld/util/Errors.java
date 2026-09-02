package link.sharedworld.util;

public final class Errors {
    private Errors() {
    }

    /** The innermost cause of a throwable (the throwable itself when it has none). */
    public static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
