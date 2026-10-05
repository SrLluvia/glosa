package dev.glosa.core.chunk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A passage of a document, small enough to be retrieved and cited on its own.
 *
 * <p>Chunks carry where they came from, not just what they say. An answer has to
 * point back at a page and a heading, so losing that provenance during ingestion
 * would make the citation impossible to reconstruct later.
 *
 * <p>The embedding is absent until the rag service computes it. The column is
 * nullable on purpose: text extraction and vectorisation are separate steps, and
 * a chunk is already useful to keyword search without one.
 */
@Entity
@Table(name = "chunk")
public class Chunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    /** Position within the document, which is what restores reading order. */
    @Column(nullable = false, updatable = false)
    private int ordinal;

    @Column(name = "page_number")
    private Integer pageNumber;

    /** Nearest enclosing heading, shown alongside a citation. */
    private String heading;

    @Column(nullable = false, updatable = false)
    private String content;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For JPA only. */
    protected Chunk() {
    }

    public Chunk(UUID tenantId, UUID documentId, int ordinal, Integer pageNumber, String heading,
            String content, int tokenCount) {
        this.tenantId = tenantId;
        this.documentId = documentId;
        this.ordinal = ordinal;
        this.pageNumber = pageNumber;
        this.heading = heading;
        this.content = content;
        this.tokenCount = tokenCount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public Integer getPageNumber() {
        return pageNumber;
    }

    public String getHeading() {
        return heading;
    }

    public String getContent() {
        return content;
    }

    public int getTokenCount() {
        return tokenCount;
    }
}
