package dev.glosa.core.rag;

/**
 * Embeddings could not be produced right now.
 *
 * <p>Distinct from a document that cannot be read: nothing is wrong with the
 * document, so the job should be retried rather than failed.
 */
public class EmbeddingUnavailableException extends RuntimeException {

    public EmbeddingUnavailableException(String message) {
        super(message);
    }

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
