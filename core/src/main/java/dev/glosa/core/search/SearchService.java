package dev.glosa.core.search;

import dev.glosa.core.collection.DocumentCollectionService;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keyword search over the passages of a collection.
 *
 * <p>This is one half of the retrieval the finished system will do. Vector
 * similarity is the other, and the two will be merged with Reciprocal Rank
 * Fusion once embeddings exist. Keyword search is first on purpose: it is the
 * half that handles acronyms, product names and version numbers, which is much
 * of what people actually look for in documentation, and it needs no model.
 *
 * <p>Written as SQL rather than through JPA because the ranking belongs in the
 * database. Pulling rows out to score them in Java would mean fetching far more
 * than the handful that will be returned.
 */
@Service
public class SearchService {

    /**
     * Ranking is left to {@code ts_rank_cd}, which accounts for how close the
     * matched terms are to each other rather than only how often they occur.
     *
     * <p>No tenant predicate appears here. Row Level Security narrows {@code
     * chunk} and {@code document} on the connection, so the ranking is computed
     * over the caller's rows and nobody else's. That matters more than it looks:
     * were scoping applied after ranking, another tenant's text would still have
     * influenced which passages came back.
     */
    private static final String SEARCH_SQL = """
            select c.id,
                   c.document_id,
                   d.filename,
                   c.page_number,
                   c.heading,
                   c.content,
                   ts_rank_cd(c.content_tsv, websearch_to_tsquery('english', ?)) as score
            from chunk c
            join document d on d.id = c.document_id
            where d.collection_id = ?
              and c.content_tsv @@ websearch_to_tsquery('english', ?)
            order by score desc, c.ordinal
            limit ?
            """;

    private final JdbcTemplate jdbc;
    private final DocumentCollectionService collections;

    public SearchService(JdbcTemplate jdbc, DocumentCollectionService collections) {
        this.jdbc = jdbc;
        this.collections = collections;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER')")
    @Transactional(readOnly = true)
    public List<Passage> search(UUID collectionId, String query, int limit) {
        // Reuses the collection lookup, so another tenant's collection answers
        // "not found" here exactly as it does everywhere else.
        collections.get(collectionId);

        return jdbc.query(SEARCH_SQL,
                (rs, row) -> new Passage(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("document_id")),
                        rs.getString("filename"),
                        (Integer) rs.getObject("page_number"),
                        rs.getString("heading"),
                        rs.getString("content"),
                        rs.getDouble("score")),
                // websearch_to_tsquery accepts what a person would type, quoted
                // phrases and all, and cannot be made to produce invalid syntax,
                // unlike to_tsquery. The value is still bound, never spliced in.
                query, collectionId, query, limit);
    }

    /**
     * A passage that matched, with everything needed to cite it.
     *
     * @param score relative to this result set only; it is not comparable
     *              between queries
     */
    public record Passage(
            UUID chunkId,
            UUID documentId,
            String filename,
            Integer pageNumber,
            String heading,
            String content,
            double score) {
    }
}
