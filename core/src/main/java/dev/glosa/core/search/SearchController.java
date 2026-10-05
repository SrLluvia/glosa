package dev.glosa.core.search;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/collections/{collectionId}")
@Tag(name = "Search", description = "Finding the passages that answer a question")
class SearchController {

    private static final int MAX_RESULTS = 50;

    private final SearchService service;

    SearchController(SearchService service) {
        this.service = service;
    }

    @Schema(name = "Passage", description = "A matching passage, with where it came from")
    record PassageResponse(
            UUID chunkId,
            UUID documentId,
            @Schema(description = "File the passage came from") String filename,
            @Schema(description = "Null for formats without pages, such as Markdown")
            Integer pageNumber,
            @Schema(description = "Nearest enclosing heading") String heading,
            String content,
            @Schema(description = "Relative within this result set only")
            double score) {

        static PassageResponse from(SearchService.Passage passage) {
            return new PassageResponse(passage.chunkId(), passage.documentId(), passage.filename(),
                    passage.pageNumber(), passage.heading(), passage.content(), passage.score());
        }
    }

    @Schema(name = "SearchResults")
    record SearchResponse(String query, int count, List<PassageResponse> passages) {
    }

    @GetMapping("/search")
    @Operation(summary = "Search the passages of a collection",
            description = "Keyword search for now; vector similarity joins it once embeddings "
                    + "exist, and the two will be merged. Results carry the document and heading "
                    + "they came from, so an answer built on them can be checked.")
    @ApiResponse(responseCode = "404", description = "No such collection in the caller's tenant",
            content = @Content)
    SearchResponse search(
            @PathVariable UUID collectionId,
            @Parameter(description = "What to look for. Quoted phrases work, as in a search engine.",
                    example = "holiday allowance")
            @RequestParam @NotBlank @Size(max = 500) String q,
            @RequestParam(defaultValue = "10") @Min(1) @Max(MAX_RESULTS) int limit) {

        List<PassageResponse> passages = service.search(collectionId, q, limit).stream()
                .map(PassageResponse::from)
                .toList();

        return new SearchResponse(q, passages.size(), passages);
    }
}
