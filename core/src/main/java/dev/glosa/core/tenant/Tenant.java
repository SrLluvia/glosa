package dev.glosa.core.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * An organisation using the system.
 *
 * <p>The identifier is assigned rather than generated, because registration has
 * to bind the tenant context before it writes anything: the first user is
 * inserted under a Row Level Security policy that compares against that very id,
 * so it must be known before the transaction starts.
 */
@Entity
@Table(name = "tenant")
public class Tenant {

    @Id
    private UUID id;

    /** Stable, human-readable handle used at login. */
    @Column(nullable = false, updatable = false)
    private String slug;

    @Column(nullable = false)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For JPA only. */
    protected Tenant() {
    }

    public Tenant(UUID id, String slug, String name) {
        this.id = id;
        this.slug = slug;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
