package dev.glosa.core.collection;

import dev.glosa.core.collection.CollectionDtos.CollectionPage;
import dev.glosa.core.collection.CollectionDtos.CollectionResponse;
import dev.glosa.core.collection.CollectionDtos.CreateCollectionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/collections")
@Tag(name = "Collections", description = "Groups of documents questions are asked against")
class CollectionController {

    // Constraints on the parameters below are enforced by Spring's built-in
    // method validation, which reports a violation as 400. Adding @Validated to
    // this class would route them through the AOP path instead, where the same
    // violation surfaces as an unhandled exception and a 500.

    /**
     * Upper bound on page size. Without one, a caller could ask for every row in
     * a single request and turn a listing into a denial of service.
     */
    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentCollectionService service;

    CollectionController(DocumentCollectionService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create a collection",
            description = "Requires the EDITOR or ADMIN role. The collection belongs to the "
                    + "tenant named in the access token.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "409", description = "A collection with that name already exists",
            content = @io.swagger.v3.oas.annotations.media.Content)
    ResponseEntity<CollectionResponse> create(@Valid @RequestBody CreateCollectionRequest request) {
        DocumentCollection created = service.create(request.name());
        return ResponseEntity
                .created(URI.create("/v1/collections/" + created.getId()))
                .body(CollectionResponse.from(created));
    }

    @GetMapping
    @Operation(summary = "List collections",
            description = "Only the caller's own tenant is ever listed.")
    CollectionPage list(
            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return CollectionPage.from(
                service.list(PageRequest.of(page, size, Sort.by("name").ascending())));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one collection",
            description = "A collection belonging to another tenant is reported as not found, "
                    + "because answering 'forbidden' would confirm the id exists.")
    @ApiResponse(responseCode = "404", description = "Not found in the caller's tenant",
            content = @io.swagger.v3.oas.annotations.media.Content)
    CollectionResponse get(@PathVariable UUID id) {
        return CollectionResponse.from(service.get(id));
    }
}
