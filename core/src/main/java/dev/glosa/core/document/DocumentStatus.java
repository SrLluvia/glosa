package dev.glosa.core.document;

/**
 * Where a document is in the ingestion pipeline.
 *
 * <p>The names are persisted and constrained by a check constraint, so renaming
 * one needs a migration.
 */
public enum DocumentStatus {

    /** Stored and queued; nothing has been read from it yet. */
    PENDING,

    /** Text and structure are being extracted. */
    PARSING,

    /** Text is extracted; embeddings are being computed. */
    EMBEDDING,

    /** Indexed and answerable. */
    READY,

    /** Gave up after exhausting the retries. The reason is on the document. */
    FAILED
}
