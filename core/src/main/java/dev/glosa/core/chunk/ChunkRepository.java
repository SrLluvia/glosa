package dev.glosa.core.chunk;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Chunks of the current tenant. No tenant argument: Row Level Security narrows
 * every statement to the tenant carried on the connection.
 */
public interface ChunkRepository extends JpaRepository<Chunk, UUID> {

    List<Chunk> findByDocumentIdOrderByOrdinalAsc(UUID documentId);

    /** Clears a previous attempt, so a retry does not leave duplicates behind. */
    void deleteByDocumentId(UUID documentId);

    long countByDocumentId(UUID documentId);
}
