package dev.glosa.core.ingestion;

/** A document that could not be read. Retried, then given up on. */
public class ExtractionException extends RuntimeException {

    public ExtractionException(String message) {
        super(message);
    }

    public ExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
