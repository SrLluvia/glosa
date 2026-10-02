package dev.glosa.core.collection;

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
 * A named group of documents within one tenant, and the unit questions are asked
 * against.
 *
 * <p>Named {@code DocumentCollection} rather than {@code Collection} so the type
 * does not collide with {@link java.util.Collection} in every file that touches
 * it. The table keeps the shorter name.
 *
 * <p>{@code tenantId} is stored rather than derived, because the Row Level
 * Security policy compares it on both read and write. On insert it must match the
 * tenant bound to the connection or the database rejects the row.
 */
@Entity
@Table(name = "collection")
public class DocumentCollection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For JPA only. */
    protected DocumentCollection() {
    }

    public DocumentCollection(UUID tenantId, String name) {
        this.tenantId = tenantId;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
