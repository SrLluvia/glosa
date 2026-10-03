package dev.glosa.core.ingestion;

import dev.glosa.core.tenant.TenantContext;
import dev.glosa.core.tenant.TenantDirectory;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps the ingestion queue.
 *
 * <p>Several instances of the service may run this at once. That is safe without
 * any coordination between them because claiming uses {@code SELECT ... FOR
 * UPDATE SKIP LOCKED}: each queued row goes to exactly one worker, and the
 * others step over it instead of queueing behind it.
 *
 * <p>The sweep visits one tenant at a time and binds each before touching the
 * queue. The alternative, a worker privileged to read every tenant's rows at
 * once, would have been less code and a hole in the only boundary this system
 * really has. The cost is a query per tenant per sweep, which is fine at this
 * size; past a few hundred tenants it would be worth keeping a small unscoped
 * table recording which tenants have work pending, so the sweep can skip the
 * quiet ones.
 */
@Component
@ConditionalOnProperty(prefix = "glosa.ingestion", name = "enabled", havingValue = "true")
class IngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);

    private final IngestionService ingestion;
    private final TenantDirectory tenants;
    private final IngestionProperties properties;

    IngestionWorker(IngestionService ingestion, TenantDirectory tenants, IngestionProperties properties) {
        this.ingestion = ingestion;
        this.tenants = tenants;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${glosa.ingestion.poll-interval}")
    void sweep() {
        for (UUID tenantId : tenants.allTenantIds()) {
            try {
                TenantContext.runWith(tenantId, this::drainTenant);
            } catch (RuntimeException e) {
                // One tenant's trouble must not stop the others being served.
                log.error("Ingestion sweep failed for tenant {}", tenantId, e);
            }
        }
    }

    private void drainTenant() {
        List<UUID> claimed = ingestion.claimDueJobs(properties.batchSize());
        for (UUID jobId : claimed) {
            ingestion.runJob(jobId);
        }
    }

    @Scheduled(fixedDelayString = "${glosa.ingestion.stale-after}")
    void requeueAbandonedJobs() {
        for (UUID tenantId : tenants.allTenantIds()) {
            try {
                TenantContext.runWith(tenantId,
                        () -> ingestion.requeueStaleJobs(properties.staleAfter()));
            } catch (RuntimeException e) {
                log.error("Could not requeue abandoned jobs for tenant {}", tenantId, e);
            }
        }
    }
}
