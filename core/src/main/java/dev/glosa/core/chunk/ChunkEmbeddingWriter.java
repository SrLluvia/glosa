package dev.glosa.core.chunk;

import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stores embeddings on chunks.
 *
 * <p>Written through JDBC rather than JPA because {@code vector} is a type
 * pgvector adds and Hibernate has no mapping for. Teaching Hibernate about it
 * would mean a custom type and a dependency for one column written in one
 * place; a cast in the statement is smaller and clearer.
 *
 * <p>The tenant predicate is absent on purpose, as everywhere else: Row Level
 * Security narrows the update to the tenant bound to the connection, so a chunk
 * belonging to anyone else simply is not there to update.
 */
@Component
public class ChunkEmbeddingWriter {

    private static final String UPDATE_SQL =
            "update chunk set embedding = cast(? as vector) where id = ?";

    private final JdbcTemplate jdbc;

    public ChunkEmbeddingWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Attaches one vector to each chunk, pairing them by position.
     *
     * @throws IllegalArgumentException if the counts disagree, which would mean
     *                                  attaching the meaning of one passage to
     *                                  another
     */
    public void write(List<UUID> chunkIds, List<float[]> embeddings) {
        if (chunkIds.size() != embeddings.size()) {
            throw new IllegalArgumentException(
                    "Got " + embeddings.size() + " embeddings for " + chunkIds.size() + " chunks");
        }

        // One round trip rather than one per chunk; a document routinely
        // produces dozens.
        jdbc.batchUpdate(UPDATE_SQL, new java.util.AbstractList<Object[]>() {
            @Override
            public Object[] get(int index) {
                return new Object[] {toVectorLiteral(embeddings.get(index)), chunkIds.get(index)};
            }

            @Override
            public int size() {
                return chunkIds.size();
            }
        });
    }

    /**
     * Formats a vector the way pgvector parses it: {@code [1,2,3]}.
     *
     * <p>Built from floats we produced, never from caller input, and still bound
     * as a parameter rather than spliced into the statement.
     */
    private static String toVectorLiteral(float[] vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            joiner.add(Float.toString(value));
        }
        return joiner.toString();
    }
}
