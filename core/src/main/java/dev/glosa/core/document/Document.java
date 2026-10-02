package dev.glosa.core.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * An uploaded file and how far its ingestion has got.
 *
 * <p>The uploaded bytes live in a separate table. This row is read on every
 * listing and every status poll, and must stay cheap to fetch.
 */
@Entity
@Table(name = "document")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "collection_id", nullable = false, updatable = false)
    private UUID collectionId;

    @Column(nullable = false, updatable = false)
    private String filename;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "byte_size", nullable = false, updatable = false)
    private long byteSize;

    /** SHA-256 of the uploaded bytes, which is how a re-upload is recognised. */
    @Column(name = "content_hash", nullable = false, updatable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status = DocumentStatus.PENDING;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "failure_reason")
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** For JPA only. */
    protected Document() {
    }

    public Document(UUID tenantId, UUID collectionId, String filename, String contentType,
            long byteSize, String contentHash) {
        this.tenantId = tenantId;
        this.collectionId = collectionId;
        this.filename = filename;
        this.contentType = contentType;
        this.byteSize = byteSize;
        this.contentHash = contentHash;
    }

    /**
     * Moves the document on through the pipeline.
     *
     * <p>A check constraint ties the failure reason to the status, so clearing it
     * here is not tidiness: leaving a stale reason behind on a retry that
     * succeeds would be rejected by the database.
     */
    public void moveTo(DocumentStatus next) {
        this.status = next;
        this.failureReason = null;
    }

    public void markFailed(String reason) {
        this.status = DocumentStatus.FAILED;
        // The constraint requires a reason, and an empty one would be useless to
        // whoever has to work out what went wrong.
        this.failureReason = (reason == null || reason.isBlank()) ? "Ingestion failed" : reason;
    }

    public void recordPageCount(Integer pages) {
        this.pageCount = pages;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getCollectionId() {
        return collectionId;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getByteSize() {
        return byteSize;
    }

    public String getContentHash() {
        return contentHash;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
