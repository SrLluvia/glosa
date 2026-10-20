package dev.glosa.core.rag;

/**
 * An answer could not be generated.
 *
 * <p>Separate from an embedding failure because it surfaces to a waiting person
 * rather than to a background worker, and so deserves a different response.
 */
public class AnswerUnavailableException extends RuntimeException {

    public AnswerUnavailableException(String message) {
        super(message);
    }

    public AnswerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
