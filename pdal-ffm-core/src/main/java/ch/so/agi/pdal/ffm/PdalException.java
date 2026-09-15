package ch.so.agi.pdal.ffm;

/**
 * Thrown when a PDAL operation fails.
 *
 * <p>The message contains the native error message reported by PDAL. The
 * original message is also available through {@link #nativeMessage()}.
 */
public class PdalException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String nativeMessage;

    public PdalException(String message) {
        this(message, null);
    }

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
