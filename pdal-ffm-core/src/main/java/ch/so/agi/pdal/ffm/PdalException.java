package ch.so.agi.pdal.ffm;

/**
 * Thrown when a PDAL operation fails.
 *
 * <p>The message contains the native error message reported by PDAL. The
 * original message is also available through {@link #nativeMessage()}.
 */
public class PdalException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Native error message as reported by PDAL, never {@code null}. */
    private final String nativeMessage;

    /**
     * Creates the exception from a native error message.
     *
     * @param message native error message, may be {@code null}
     */
    public PdalException(String message) {
        this(message, null);
    }

    /**
     * Creates the exception from a native error message and a cause.
     *
     * @param message native error message, may be {@code null}
     * @param cause underlying cause, may be {@code null}
     */
    public PdalException(String message, Throwable cause) {
        super(buildMessage(message), cause);
        this.nativeMessage = message == null ? "" : message;
    }

    private static String buildMessage(String message) {
        if (message == null || message.isBlank()) {
            return "PDAL operation failed";
        }
        return "PDAL operation failed: " + message;
    }

    /**
     * The unmodified error message reported by the native PDAL runtime.
     *
     * @return native error message, never {@code null}
     */
    public String nativeMessage() {
        return nativeMessage;
    }
}
