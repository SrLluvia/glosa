package dev.glosa.core.collection;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;

/**
 * What the collection endpoints accept and return.
 *
 * <p>Separate from the entity on purpose: the API shape should be free to differ
 * from the table, and serialising the entity would publish whatever column is
 * added to it next, {@code tenant_id} included.
 */
final class CollectionDtos {

    private CollectionDtos() {
    }

    @Schema(name = "CreateCollectionRequest", description = "A new collection within the caller's tenant")
    record CreateCollectionRequest(
            @Schema(example = "Engineering handbook")
            @NotBlank
            @Size(max = 120)
            String name) {
    }

    @Schema(name = "Collection")
    record CollectionResponse(
            UUID id,
            String name,
            Instant createdAt) {

        static CollectionResponse from(DocumentCollection collection) {
            return new CollectionResponse(
                    collection.getId(), collection.getName(), collection.getCreatedAt());
        }
    }

    @Schema(name = "CollectionPage")
    record CollectionPage(
            List<CollectionResponse> items,
            @Schema(description = "Zero-based index of this page") int page,
            int size,
            long totalItems,
            int totalPages) {

        static CollectionPage from(Page<DocumentCollection> page) {
            return new CollectionPage(
                    page.getContent().stream().map(CollectionResponse::from).toList(),
                    page.getNumber(),
                    page.getSize(),
                    page.getTotalElements(),
                    page.getTotalPages());
        }
    }
}
