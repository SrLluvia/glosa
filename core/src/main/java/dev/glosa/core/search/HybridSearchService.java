package dev.glosa.core.search;

import dev.glosa.core.collection.DocumentCollectionService;
import dev.glosa.core.rag.EmbeddingClient;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retrieval over both halves of the index at once.
 *
 * <p>Keyword search finds passages that use the words asked about; vector search
 * finds passages that mean the same thing in different words. Neither is
 * sufficient on its own. Keyword search misses "deadline" when the document says
 * "before the fifth of the month"; vector search is unreliable on the acronyms,
 * product names and version numbers that make up much of what anyone actually
 * looks up in documentation.
 *
 * <p>The two are merged with Reciprocal Rank Fusion, which combines them by
 * <em>rank</em> rather than by score. That matters because the two scores are
 * not comparable: {@code ts_rank_cd} and cosine distance measure different
 * things on different scales, and any attempt to weigh one against the other
 * directly amounts to inventing a conversion. Rank has no units.
 */
@Service
public class HybridSearchService {

    /**
     * The constant in the fusion formula, {@code 1 / (k + rank)}.
     *
     * <p>60 is the value from the paper that introduced the method, and it is
     * chosen to stop the top one or two results of either list from dominating:
     * a passage ranked first by one half and twentieth by the other should not
     * automatically beat one ranked third by both.
     */
    private final int fusionConstant;

    /**
     * Weight of the semantic half. Fixed at one; the keyword half is expressed
     * relative to it, so there is only one number to reason about.
     */
    private static final double SEMANTIC_WEIGHT = 1.0;

    /**
     * How deep each half goes before fusion. Wider than the result set, because
     * a passage ranked poorly by one half and well by the other is exactly the
     * kind the fusion exists to rescue.
     */
    private static final int CANDIDATES_PER_HALF = 40;

    /**
     * One statement, so the two halves are ranked and fused inside the database.
     *
     * <p>No tenant predicate appears anywhere in it. Row Level Security narrows
     * {@code chunk} and {@code document} on the connection, which means the
     * ranking is computed over the caller's rows alone. Scoping afterwards would
     * still have let another tenant's text influence which passages surfaced,
     * even though none of it was returned.
     */
    /**
     * Keyword matching requires only <em>some</em> of the query's terms.
     *
     * <p>{@code websearch_to_tsquery} joins terms with AND, which suits a search
     * box and is useless for a question: "how many days of paid leave does
     * everyone get" matches nothing, because no single passage contains every
     * one of those words. Measured on the evaluation set, the keyword half
     * found the right passage for 17% of questions and for none of the
     * paraphrased ones, which meant hybrid search was quietly just semantic
     * search.
     *
     * <p>Rewriting the operator to OR keeps quoted phrases and negations intact,
     * because those are expressed with different operators, and lets ranking do
     * the work of sorting many weak matches instead of a filter throwing them
     * all away. The function itself is defined in a migration.
     */
    private static final String HYBRID_SQL = """
            with keyword as (
                select c.id,
                       row_number() over (
                           order by ts_rank_cd(c.content_tsv, any_term(?)) desc,
                                    c.ordinal
                       ) as rank
                from chunk c
                join document d on d.id = c.document_id
                where d.collection_id = ?
                  and c.content_tsv @@ any_term(?)
                  and cast(? as boolean)
                limit ?
            ),
            semantic as (
                select c.id,
                       row_number() over (order by c.embedding <=> cast(? as vector)) as rank,
                       c.embedding <=> cast(? as vector) as distance
                from chunk c
                join document d on d.id = c.document_id
                where d.collection_id = ?
                  and c.embedding is not null
                  and cast(? as boolean)
                order by c.embedding <=> cast(? as vector)
                limit ?
            ),
            fused as (
                select coalesce(k.id, s.id) as id,
                       coalesce(? / (? + k.rank), 0) + coalesce(? / (? + s.rank), 0) as score,
                       k.rank as keyword_rank,
                       s.rank as semantic_rank,
                       s.distance as semantic_distance
                from keyword k
                full outer join semantic s on s.id = k.id
            )
            select f.id,
                   f.score,
                   f.keyword_rank,
                   f.semantic_rank,
                   f.semantic_distance,
                   c.document_id,
                   d.filename,
                   c.page_number,
                   c.heading,
                   c.content
            from fused f
            join chunk c on c.id = f.id
            join document d on d.id = c.document_id
            order by f.score desc, c.ordinal
            limit ?
            """;

    private final JdbcTemplate jdbc;
    private final DocumentCollectionService collections;
    private final EmbeddingClient embeddings;
    private final double keywordWeight;

    public HybridSearchService(JdbcTemplate jdbc, DocumentCollectionService collections,
            EmbeddingClient embeddings,
            @org.springframework.beans.factory.annotation.Value("${glosa.search.keyword-weight}")
            double keywordWeight,
            @org.springframework.beans.factory.annotation.Value("${glosa.search.fusion-constant}")
            int fusionConstant) {
        this.jdbc = jdbc;
        this.collections = collections;
        this.embeddings = embeddings;
        this.keywordWeight = keywordWeight;
        this.fusionConstant = fusionConstant;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER')")
    @Transactional(readOnly = true)
    public List<RankedPassage> search(UUID collectionId, String query, int limit) {
        return search(collectionId, query, limit, SearchMode.HYBRID);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER')")
    @Transactional(readOnly = true)
    public List<RankedPassage> search(UUID collectionId, String query, int limit, SearchMode mode) {
        // Reuses the collection lookup, so another tenant's collection answers
        // "not found" here exactly as it does everywhere else.
        collections.get(collectionId);

        String queryVector = toVectorLiteral(embeddings.embed(List.of(query)).getFirst());

        return jdbc.query(HYBRID_SQL,
                (rs, row) -> new RankedPassage(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("document_id")),
                        rs.getString("filename"),
                        (Integer) rs.getObject("page_number"),
                        rs.getString("heading"),
                        rs.getString("content"),
                        rs.getDouble("score"),
                        rankOf(rs.getObject("keyword_rank")),
                        rankOf(rs.getObject("semantic_rank")),
                        (Double) rs.getObject("semantic_distance")),
                query, collectionId, query, mode.usesKeyword(), CANDIDATES_PER_HALF,
                queryVector, queryVector, collectionId, mode.usesSemantic(), queryVector,
                CANDIDATES_PER_HALF,
                keywordWeight, fusionConstant, SEMANTIC_WEIGHT, fusionConstant,
                limit);
    }

    /**
     * Reads a rank column, which {@code row_number()} produces as a bigint.
     *
     * <p>Null means the passage was absent from that half of the search, which
     * is information worth keeping rather than flattening to zero.
     */
    private static Integer rankOf(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    /** Formats a vector the way pgvector parses it. Bound as a parameter, never spliced in. */
    private static String toVectorLiteral(float[] vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            joiner.add(Float.toString(value));
        }
        return joiner.toString();
    }

    /**
     * A passage that matched, and how each half of the search rated it.
     *
     * @param keywordRank  position in the keyword half, or null if it was not
     *                     found there at all
     * @param semanticRank position in the vector half, or null likewise
     * @param semanticDistance cosine distance from the query, where 0 is
     *                         identical and larger is less alike. Null when the
     *                         passage came only from the keyword half. This is
     *                         the only signal of whether a result is actually
     *                         relevant, as opposed to merely the closest thing
     *                         present
     */
    public record RankedPassage(
            UUID chunkId,
            UUID documentId,
            String filename,
            Integer pageNumber,
            String heading,
            String content,
            double score,
            Integer keywordRank,
            Integer semanticRank,
            Double semanticDistance) {
    }
}
