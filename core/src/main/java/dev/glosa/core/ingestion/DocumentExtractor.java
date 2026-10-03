package dev.glosa.core.ingestion;

/**
 * Turns raw uploaded bytes into passages.
 *
 * <p>An interface we own, so the pipeline does not depend on any particular
 * parsing library and can be exercised in tests without one. PDF handling will
 * arrive as another implementation, backed by the rag service, without the
 * worker needing to change.
 */
public interface DocumentExtractor {

    /** Whether this extractor can read the given content type. */
    boolean supports(String contentType);

    /**
     * Reads the document.
     *
     * @throws ExtractionException when the bytes cannot be read, which is a
     *                             failure of this document rather than of the
     *                             worker
     */
    ExtractionResult extract(byte[] content);
}
