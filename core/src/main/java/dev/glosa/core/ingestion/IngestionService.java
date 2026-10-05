package dev.glosa.core.ingestion;

import dev.glosa.core.chunk.Chunk;
import dev.glosa.core.chunk.ChunkRepository;
import dev.glosa.core.document.Document;
import dev.glosa.core.document.DocumentContent;
import dev.glosa.core.document.DocumentContentRepository;
import dev.glosa.core.document.DocumentRepository;
import dev.glosa.core.document.DocumentStatus;
import dev.glosa.core.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs queued ingestion work for the tenant currently bound.
 *
 * <p>Every method here expects a tenant context, and relies on Row Level
 * Security for scoping exactly as the request path does. The worker is not
 * privileged: it walks tenants one at a time and binds each in turn, so no
 * statement in this class is ever able to see across the boundary.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final IngestionJobRepository jobs;
    private final DocumentRepository documents;
    private final DocumentContentRepository contents;
    private final ChunkRepository chunks;
    private final List<DocumentExtractor> extractors;
    private final Clock clock;

    public IngestionService(IngestionJobRepository jobs, DocumentRepository documents,
            DocumentContentRepository contents, ChunkRepository chunks,
            List<DocumentExtractor> extractors, Clock clock) {
        this.jobs = jobs;
        this.documents = documents;
        this.contents = contents;
        this.chunks = chunks;
        this.extractors = extractors;
        this.clock = clock;
    }

    /**
     * Takes ownership of up to {@code max} due jobs.
     *
     * <p>Its own transaction, and it must commit before any work starts. The row
     * locks that {@code SKIP LOCKED} takes only last for this transaction, so it
     * is the committed RUNNING state, not the lock, that stops a second worker
     * picking the same job up.
     *
     * <p>Keeping the claim separate from the work also protects the retry count:
     * were they one transaction, a failure would roll back the incremented
     * attempt along with everything else, and the job would be retried forever
     * without ever backing off.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> claimDueJobs(int max) {
        List<UUID> claimed = jobs.lockDueJobIds(max);
        claimed.forEach(id -> jobs.findById(id).ifPresent(IngestionJob::markRunning));
        return claimed;
    }

    /**
     * Reads a claimed document and stores its passages.
     *
     * <p>Failures are caught rather than propagated: a document that cannot be
     * read is this document's problem, and must not stop the worker from serving
     * every other tenant.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runJob(UUID jobId) {
        Optional<IngestionJob> found = jobs.findById(jobId);
        if (found.isEmpty()) {
            // Another worker finished it, or the document was deleted underneath
            // us. Neither is an error.
            return;
        }
        IngestionJob job = found.get();

        try {
            ingest(job);
            job.markSucceeded();
        } catch (ExtractionException e) {
            fail(job, e.getMessage(), e);
        } catch (RuntimeException e) {
            // Anything unexpected is still this job's failure; it must not escape
            // and take the polling loop down with it.
            fail(job, "Ingestion failed unexpectedly", e);
        }
    }

    private void ingest(IngestionJob job) {
        UUID tenantId = TenantContext.requireTenantId();
        Document document = documents.findById(job.getDocumentId())
                .orElseThrow(() -> new ExtractionException("The document no longer exists"));

        document.moveTo(DocumentStatus.PARSING);

        DocumentContent content = contents.findById(document.getId())
                .orElseThrow(() -> new ExtractionException("The uploaded content is missing"));

        DocumentExtractor extractor = extractors.stream()
                .filter(candidate -> candidate.supports(document.getContentType()))
                .findFirst()
                .orElseThrow(() -> new ExtractionException(
                        "Nothing can read " + document.getContentType()));

        ExtractionResult result = extractor.extract(content.getBytes());

        // A retry must not pile a second set of passages on top of the first.
        // The flush matters: Hibernate orders inserts before deletes within a
        // transaction, so without it the new rows collide with the old ones on
        // (document_id, ordinal) instead of replacing them.
        chunks.deleteByDocumentId(document.getId());
        chunks.flush();

        List<Chunk> extracted = new java.util.ArrayList<>(result.chunks().size());
        for (int ordinal = 0; ordinal < result.chunks().size(); ordinal++) {
            ExtractedChunk chunk = result.chunks().get(ordinal);
            extracted.add(new Chunk(tenantId, document.getId(), ordinal, chunk.pageNumber(),
                    chunk.heading(), chunk.content(), chunk.tokenCount()));
        }
        chunks.saveAll(extracted);

        document.recordPageCount(result.pageCount());
        // Text is stored and searchable by keyword. Embeddings are a separate
        // step, so the document is READY for keyword retrieval already.
        document.moveTo(DocumentStatus.READY);

        log.info("Ingested document {} into {} chunks", document.getId(), extracted.size());
    }

    private void fail(IngestionJob job, String reason, Exception cause) {
        log.warn("Ingestion attempt {} for document {} failed: {}",
                job.getAttempts(), job.getDocumentId(), reason, cause);

        boolean exhausted = job.markFailed(reason, clock.instant());
        documents.findById(job.getDocumentId()).ifPresent(document -> {
            if (exhausted) {
                document.markFailed(reason);
            } else {
                // Back to the queue; the document stays pending rather than
                // showing a failure that may yet resolve itself.
                document.moveTo(DocumentStatus.PENDING);
            }
        });
    }

    /**
     * Returns jobs abandoned by a worker that died mid-flight.
     *
     * <p>A claim commits RUNNING before the work begins, which is what makes the
     * claim safe but also means a crash leaves the row owned by nobody. Without
     * this, such a job would never be retried and its document would sit in
     * PARSING for good.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int requeueStaleJobs(Duration staleAfter) {
        Instant cutoff = clock.instant().minus(staleAfter);
        List<IngestionJob> stale = jobs.findStaleRunningJobs(cutoff);
        stale.forEach(job -> {
            log.warn("Requeueing ingestion job {} abandoned since {}", job.getId(), cutoff);
            job.requeue();
        });
        return stale.size();
    }
}
