package dev.glosa.core.collection;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Collections of the current tenant.
 *
 * <p>Note what is absent: no method takes a tenant argument and no query carries
 * a tenant predicate. The connection these queries run on is scoped by Row Level
 * Security, so the database narrows every statement to the current tenant. A
 * predicate here would be a second, weaker copy of that rule.
 */
interface DocumentCollectionRepository extends JpaRepository<DocumentCollection, UUID> {

    /** Within the tenant, since the policy applies to this query as well. */
    boolean existsByName(String name);
}
