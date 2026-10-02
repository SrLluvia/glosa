package dev.glosa.core.document;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Documents of the current tenant.
 *
 * <p>No method takes a tenant argument: Row Level Security narrows every
 * statement to the tenant carried on the connection.
 */
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Page<Document> findByCollectionIdOrderByCreatedAtDesc(UUID collectionId, Pageable pageable);

    /** Used to recognise a re-upload of bytes already held in this collection. */
    Optional<Document> findByCollectionIdAndContentHash(UUID collectionId, String contentHash);
}
