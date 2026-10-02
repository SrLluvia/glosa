package dev.glosa.core.document;

import dev.glosa.core.collection.DocumentCollectionService;
import dev.glosa.core.error.DuplicateResourceException;
import dev.glosa.core.error.ResourceNotFoundException;
import dev.glosa.core.error.UnsupportedDocumentException;
import dev.glosa.core.ingestion.IngestionJob;
import dev.glosa.core.ingestion.IngestionJobRepository;
import dev.glosa.core.tenant.TenantContext;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentService {

    /**
     * Types the ingestion pipeline can actually read. Anything else is refused at
     * upload rather than stored and failed later, which keeps unreadable payloads
     * out of the database entirely.
     */
    private static final Set<String> SUPPORTED_CONTENT_TYPES =
            Set.of("application/pdf", "text/markdown", "text/plain");

    private final DocumentRepository documents;
    private final DocumentContentRepository contents;
    private final IngestionJobRepository jobs;
    private final DocumentCollectionService collections;

    public DocumentService(DocumentRepository documents, DocumentContentRepository contents,
            IngestionJobRepository jobs, DocumentCollectionService collections) {
        this.documents = documents;
        this.contents = contents;
        this.jobs = jobs;
        this.collections = collections;
    }

    /**
     * Stores an upload and queues it for ingestion.
     *
     * <p>Storing the bytes and enqueueing the job happen in one transaction. Were
     * they split, a crash in between would leave a document that is visible,
     * permanently PENDING, and that no worker will ever pick up.
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    @Transactional
    public Document upload(UUID collectionId, String filename, String contentType, byte[] bytes) {
        UUID tenantId = TenantContext.requireTenantId();

        // Reuses the collection lookup, which already answers "not found" for
        // another tenant's collection rather than revealing that it exists.
        collections.get(collectionId);

        String type = normaliseContentType(contentType);
        if (!SUPPORTED_CONTENT_TYPES.contains(type)) {
            throw new UnsupportedDocumentException(
                    "Unsupported content type '" + type + "'. Supported: " + SUPPORTED_CONTENT_TYPES);
        }
        if (bytes == null || bytes.length == 0) {
            throw new UnsupportedDocumentException("The uploaded file is empty");
        }

        String hash = sha256(bytes);
        documents.findByCollectionIdAndContentHash(collectionId, hash).ifPresent(existing -> {
            throw new DuplicateResourceException(
                    "This file is already in the collection as '" + existing.getFilename() + "'");
        });

        Document document = documents.save(new Document(
                tenantId, collectionId, safeFilename(filename), type, bytes.length, hash));
        contents.save(new DocumentContent(document.getId(), tenantId, bytes));
        jobs.save(new IngestionJob(tenantId, document.getId()));

        return document;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER')")
    @Transactional(readOnly = true)
    public Page<Document> listInCollection(UUID collectionId, Pageable pageable) {
        collections.get(collectionId);
        return documents.findByCollectionIdOrderByCreatedAtDesc(collectionId, pageable);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER')")
    @Transactional(readOnly = true)
    public Document get(UUID id) {
        return documents.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No document with id " + id));
    }

    /** Drops any parameters, so "text/plain; charset=utf-8" matches "text/plain". */
    private static String normaliseContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        int separator = contentType.indexOf(';');
        return (separator < 0 ? contentType : contentType.substring(0, separator)).trim().toLowerCase();
    }

    /**
     * Keeps only the final name component.
     *
     * <p>The filename comes from the client and is echoed back in listings, so a
     * value like {@code ../../etc/passwd} must not survive. Nothing here writes to
     * the filesystem today, but a stored name outlives the code that stored it.
     */
    private static String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "untitled";
        }
        String candidate = filename.replace('\\', '/');
        try {
            java.nio.file.Path name = Paths.get(candidate).getFileName();
            String resolved = name == null ? "" : name.toString().trim();
            return resolved.isEmpty() || resolved.equals(".") || resolved.equals("..")
                    ? "untitled" : resolved;
        } catch (InvalidPathException e) {
            return "untitled";
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform; its absence is not a
            // condition callers can do anything about.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
