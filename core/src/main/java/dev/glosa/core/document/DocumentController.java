package dev.glosa.core.document;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1")
@Tag(name = "Documents", description = "Files uploaded into a collection and indexed for retrieval")
class DocumentController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentService service;

    DocumentController(DocumentService service) {
        this.service = service;
    }

    @Schema(name = "Document")
    record DocumentResponse(
            UUID id,
            UUID collectionId,
            String filename,
            String contentType,
            long byteSize,
            @Schema(description = "Where the file is in the ingestion pipeline")
            DocumentStatus status,
            @Schema(description = "Known once parsing finishes") Integer pageCount,
            @Schema(description = "Present only when the status is FAILED") String failureReason,
            Instant createdAt,
            Instant updatedAt) {

        static DocumentResponse from(Document document) {
            return new DocumentResponse(
                    document.getId(), document.getCollectionId(), document.getFilename(),
                    document.getContentType(), document.getByteSize(), document.getStatus(),
                    document.getPageCount(), document.getFailureReason(),
                    document.getCreatedAt(), document.getUpdatedAt());
        }
    }

    @Schema(name = "DocumentPage")
    record DocumentPage(List<DocumentResponse> items, int page, int size, long totalItems, int totalPages) {

        static DocumentPage from(Page<Document> page) {
            return new DocumentPage(
                    page.getContent().stream().map(DocumentResponse::from).toList(),
                    page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
        }
    }

    @PostMapping(path = "/collections/{collectionId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a document",
            description = "Requires the EDITOR or ADMIN role. The file is stored and queued for "
                    + "ingestion; the response comes back before any of it has been read, with "
                    + "status PENDING. Poll the document to follow its progress.")
    @ApiResponse(responseCode = "202", description = "Stored and queued")
    @ApiResponse(responseCode = "409", description = "These exact bytes are already in the collection",
            content = @Content)
    @ApiResponse(responseCode = "415", description = "Content type the pipeline cannot read",
            content = @Content)
    ResponseEntity<DocumentResponse> upload(
            @PathVariable UUID collectionId,
            @RequestPart("file") MultipartFile file) throws IOException {

        Document document = service.upload(
                collectionId, file.getOriginalFilename(), file.getContentType(), file.getBytes());

        // 202 rather than 201: the resource exists, but the work it represents has
        // not happened yet.
        return ResponseEntity
                .accepted()
                .location(URI.create("/v1/documents/" + document.getId()))
                .body(DocumentResponse.from(document));
    }

    @GetMapping("/collections/{collectionId}/documents")
    @Operation(summary = "List the documents in a collection")
    DocumentPage list(
            @PathVariable UUID collectionId,
            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return DocumentPage.from(service.listInCollection(collectionId, PageRequest.of(page, size)));
    }

    @GetMapping("/documents/{id}")
    @Operation(summary = "Fetch one document and its ingestion status")
    @ApiResponse(responseCode = "404", description = "Not found in the caller's tenant",
            content = @Content)
    DocumentResponse get(@PathVariable UUID id) {
        return DocumentResponse.from(service.get(id));
    }
}
