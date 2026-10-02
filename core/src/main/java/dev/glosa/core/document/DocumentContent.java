package dev.glosa.core.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * The uploaded bytes, keyed by the document they belong to.
 *
 * <p>Separate from {@link Document} so that listing documents never loads
 * payloads. Written once on upload and read once by the ingestion worker.
 */
@Entity
@Table(name = "document_content")
public class DocumentContent {

    /** Shares the document's identifier rather than having one of its own. */
    @Id
    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private byte[] bytes;

    /** For JPA only. */
    protected DocumentContent() {
    }

    public DocumentContent(UUID documentId, UUID tenantId, byte[] bytes) {
        this.documentId = documentId;
        this.tenantId = tenantId;
        this.bytes = bytes;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public byte[] getBytes() {
        return bytes;
    }
}
