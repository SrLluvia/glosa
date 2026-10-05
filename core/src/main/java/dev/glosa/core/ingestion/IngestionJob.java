package dev.glosa.core.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One unit of ingestion work, queued in the database.
 *
 * <p>There is no broker. Workers claim rows with {@code SELECT ... FOR UPDATE
 * SKIP LOCKED}, which hands each row to at most one worker and lets the others
 * walk straight past it instead of blocking. Retry backoff is {@code runAfter},
 * and a partial unique index allows only one open job per document, so
 * enqueueing twice cannot produce duplicate work.
 */
@Entity
@Table(name = "ingestion_job")
public class IngestionJob {

    /** How long to wait after the first failure; doubles with each attempt. */
    private static final Duration BASE_RETRY_DELAY = Duration.ofSeconds(30);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestionState state = IngestionState.QUEUED;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "run_after", nullable = false)
    private Instant runAfter = Instant.now();

    @Column(name = "last_error")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** For JPA only. */
    protected IngestionJob() {
    }

    public IngestionJob(UUID tenantId, UUID documentId) {
        this.tenantId = tenantId;
        this.documentId = documentId;
    }

    void markRunning() {
        this.state = IngestionState.RUNNING;
        this.attempts = attempts + 1;
    }

    /**
     * Returns an abandoned job to the queue.
     *
     * <p>The attempt already counted against it stays counted: a worker that
     * died may well have died because of this document, and resetting the count
     * would let it take the whole queue down over and over.
     */
    void requeue() {
        this.state = IngestionState.QUEUED;
        this.runAfter = Instant.now();
        this.lastError = "Requeued after the worker handling it stopped responding";
    }

    void markSucceeded() {
        this.state = IngestionState.SUCCEEDED;
        this.lastError = null;
    }

    /**
     * Records a failure, and either schedules another attempt or gives up.
     *
     * @return true when the job is exhausted and the document should be marked
     *         failed, false when it will be tried again
     */
    boolean markFailed(String error, Instant now) {
        this.lastError = error;
        if (attempts >= maxAttempts) {
            this.state = IngestionState.FAILED;
            return true;
        }
        this.state = IngestionState.QUEUED;
        // Exponential backoff, so a dependency that is down is not hammered by
        // every retry in the queue at once.
        this.runAfter = now.plus(BASE_RETRY_DELAY.multipliedBy(1L << (attempts - 1)));
        return false;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public IngestionState getState() {
        return state;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getRunAfter() {
        return runAfter;
    }

    public String getLastError() {
        return lastError;
    }
}
