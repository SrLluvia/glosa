package dev.glosa.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.glosa.core.support.PostgresIntegrationTest;
import dev.glosa.core.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Drives the ingestion pipeline directly, with the scheduled worker switched off
 * in the test profile, so each step is observed rather than raced.
 */
@SpringBootTest
@DisplayName("Ingestion")
class IngestionServiceTest extends PostgresIntegrationTest {

    private static final UUID ACME = UUID.fromString("dddd0000-0000-0000-0000-000000000001");
    private static final UUID GLOBEX = UUID.fromString("eeee0000-0000-0000-0000-000000000002");
    private static final UUID ACME_COLLECTION = UUID.fromString("dddd0000-0000-0000-0000-00000000000a");
    private static final UUID GLOBEX_COLLECTION = UUID.fromString("eeee0000-0000-0000-0000-00000000000b");

    private static final String HANDBOOK = """
            # Holidays

            Everyone gets twenty-three days of paid leave each year, and they must
            be taken before December ends or they are lost entirely.

            # Expenses

            Claims go in before the fifth of the following month, and anything
            later simply lands in the next cycle instead of this one.
            """;

    @Autowired
    private IngestionService ingestion;

    @BeforeEach
    void seed() {
        asOwner().execute("truncate table collection, app_user, tenant cascade");
        asOwner().update("insert into tenant (id, slug, name) values (?, ?, ?)", ACME, "acme", "Acme");
        asOwner().update("insert into tenant (id, slug, name) values (?, ?, ?)", GLOBEX, "globex", "Globex");
        asOwner().update("insert into collection (id, tenant_id, name) values (?, ?, ?)",
                ACME_COLLECTION, ACME, "Handbook");
        asOwner().update("insert into collection (id, tenant_id, name) values (?, ?, ?)",
                GLOBEX_COLLECTION, GLOBEX, "Handbook");
    }

    @Test
    @DisplayName("reads a queued document and stores its passages with their headings")
    void ingestsAQueuedDocument() {
        UUID document = queueDocument(ACME, ACME_COLLECTION, "text/markdown", HANDBOOK);

        runQueue(ACME);

        assertThat(statusOf(document)).isEqualTo("READY");
        List<String> headings = asOwner().queryForList(
                "select heading from chunk where document_id = ? order by ordinal", String.class, document);
        assertThat(headings).containsExactly("Holidays", "Expenses");
    }

    @Test
    @DisplayName("a claimed job is not handed to a second worker")
    void claimsEachJobOnce() {
        queueDocument(ACME, ACME_COLLECTION, "text/markdown", HANDBOOK);

        List<UUID> first = TenantContext.callWith(ACME, () -> ingestion.claimDueJobs(10));
        List<UUID> second = TenantContext.callWith(ACME, () -> ingestion.claimDueJobs(10));

        // The claim commits RUNNING before any work starts, which is what stops a
        // second worker taking the same row; the lock alone would not.
        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("the worker sees only the tenant it is bound to")
    void doesNotReachAcrossTenants() {
        queueDocument(GLOBEX, GLOBEX_COLLECTION, "text/markdown", HANDBOOK);

        List<UUID> claimedByAcme = TenantContext.callWith(ACME, () -> ingestion.claimDueJobs(10));

        assertThat(claimedByAcme).isEmpty();
    }

    @Test
    @DisplayName("an unreadable document is retried with a delay, not failed outright")
    void retriesBeforeGivingUp() {
        UUID document = queueDocument(ACME, ACME_COLLECTION, "application/x-unreadable", HANDBOOK);

        runQueue(ACME);

        assertThat(statusOf(document)).isEqualTo("PENDING");
        assertThat(jobStateOf(document)).isEqualTo("QUEUED");
        assertThat(attemptsFor(document)).isEqualTo(1);
        // Backoff pushed it into the future, so the next sweep steps over it.
        assertThat(TenantContext.callWith(ACME, () -> ingestion.claimDueJobs(10))).isEmpty();
    }

    @Test
    @DisplayName("gives up once the attempts are exhausted, and says why")
    void failsAfterExhaustingAttempts() {
        UUID document = queueDocument(ACME, ACME_COLLECTION, "application/x-unreadable", HANDBOOK);

        // Three attempts, each made due by clearing the backoff the previous one set.
        for (int attempt = 0; attempt < 3; attempt++) {
            asOwner().update("update ingestion_job set run_after = now() where document_id = ?", document);
            runQueue(ACME);
        }

        assertThat(statusOf(document)).isEqualTo("FAILED");
        assertThat(jobStateOf(document)).isEqualTo("FAILED");
        assertThat(failureReasonOf(document)).contains("application/x-unreadable");
    }

    @Test
    @DisplayName("a retry replaces the previous passages instead of adding to them")
    void doesNotDuplicateChunksOnRetry() {
        UUID document = queueDocument(ACME, ACME_COLLECTION, "text/markdown", HANDBOOK);
        runQueue(ACME);
        long afterFirst = chunkCountFor(document);

        // Queue the same document again, as a requeue would.
        asOwner().update("update ingestion_job set state = 'QUEUED', run_after = now() where document_id = ?",
                document);
        runQueue(ACME);

        assertThat(chunkCountFor(document)).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("returns a job abandoned by a worker that stopped responding")
    void requeuesAbandonedJobs() {
        UUID document = queueDocument(ACME, ACME_COLLECTION, "text/markdown", HANDBOOK);
        TenantContext.runWith(ACME, () -> ingestion.claimDueJobs(10));
        // Claimed, then nothing: exactly what a worker dying mid-flight leaves.
        // updated_at is maintained by a trigger, which would stamp now() over
        // any value written here, so the trigger is lifted just long enough to
        // put the claim an hour into the past.
        asOwner().execute("alter table ingestion_job disable trigger ingestion_job_set_updated_at");
        asOwner().update(
                "update ingestion_job set updated_at = now() - interval '1 hour' where document_id = ?",
                document);
        asOwner().execute("alter table ingestion_job enable trigger ingestion_job_set_updated_at");

        int requeued = TenantContext.callWith(ACME,
                () -> ingestion.requeueStaleJobs(Duration.ofMinutes(5)));

        assertThat(requeued).isEqualTo(1);
        assertThat(jobStateOf(document)).isEqualTo("QUEUED");
    }

    private void runQueue(UUID tenantId) {
        TenantContext.runWith(tenantId, () -> ingestion.claimDueJobs(10).forEach(ingestion::runJob));
    }

    /** Inserts a document, its bytes and a queued job, as an upload would. */
    private UUID queueDocument(UUID tenantId, UUID collectionId, String contentType, String body) {
        UUID id = UUID.randomUUID();
        String hash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        asOwner().update("""
                insert into document (id, tenant_id, collection_id, filename, content_type,
                                      byte_size, content_hash, status)
                values (?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """, id, tenantId, collectionId, "handbook.md", contentType,
                body.length(), hash);
        asOwner().update("insert into document_content (document_id, tenant_id, bytes) values (?, ?, ?)",
                id, tenantId, body.getBytes(StandardCharsets.UTF_8));
        asOwner().update("insert into ingestion_job (tenant_id, document_id) values (?, ?)", tenantId, id);
        return id;
    }

    private String statusOf(UUID document) {
        return asOwner().queryForObject("select status from document where id = ?", String.class, document);
    }

    private String failureReasonOf(UUID document) {
        return asOwner().queryForObject(
                "select failure_reason from document where id = ?", String.class, document);
    }

    private String jobStateOf(UUID document) {
        return asOwner().queryForObject(
                "select state from ingestion_job where document_id = ?", String.class, document);
    }

    private int attemptsFor(UUID document) {
        return asOwner().queryForObject(
                "select attempts from ingestion_job where document_id = ?", Integer.class, document);
    }

    private long chunkCountFor(UUID document) {
        return asOwner().queryForObject(
                "select count(*) from chunk where document_id = ?", Long.class, document);
    }
}
