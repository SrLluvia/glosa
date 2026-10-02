package dev.glosa.core.ingestion;

/** Lifecycle of a queued ingestion job. */
public enum IngestionState {

    /** Waiting to be claimed, once run_after has passed. */
    QUEUED,

    /** Claimed by a worker. */
    RUNNING,

    /** Finished; the document is indexed. */
    SUCCEEDED,

    /** Out of attempts. */
    FAILED
}
