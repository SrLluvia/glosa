package dev.glosa.core.ingestion;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> {

    /**
     * Locks and returns the ids of jobs that are due, skipping any another worker
     * already holds.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes several workers safe
     * without a broker: each row goes to exactly one of them, and the others step
     * over it rather than queueing behind it. The locks last for the surrounding
     * transaction, so this must be called inside one, and the claim must be
     * committed before the work begins.
     *
     * <p>Only ids are selected. Returning whole entities here would have Hibernate
     * manage rows that are about to be re-read anyway.
     */
    @Query(value = """
            select id
            from ingestion_job
            where state = 'QUEUED'
              and run_after <= now()
            order by run_after
            for update skip locked
            limit :limit
            """, nativeQuery = true)
    List<UUID> lockDueJobIds(@Param("limit") int limit);
}
