package dev.glosa.core.support;

import dev.glosa.core.rag.EmbeddingClient;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Embeddings for tests, without a model.
 *
 * <p>Hashes words into a fixed number of buckets and normalises the result, so
 * the cosine similarity between two vectors reflects how much vocabulary the
 * texts share. That is far cruder than a real embedding, which also captures
 * meaning, but it is deterministic and it is enough to prove the plumbing: that
 * vectors are stored, that similarity ordering reaches the query, and that the
 * fusion of the two retrieval halves behaves.
 *
 * <p>How well real embeddings retrieve is a question for the evaluation suite,
 * which runs against an actual model. It is not something a unit test can
 * answer, and pretending otherwise would be the worst of both.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeEmbeddingConfiguration {

    /** Matches the width of the chunk.embedding column. */
    private static final int DIMENSIONS = 768;

    @Bean
    @Primary
    EmbeddingClient fakeEmbeddingClient() {
        return texts -> texts.stream().map(FakeEmbeddingConfiguration::hashIntoBuckets).toList();
    }

    private static float[] hashIntoBuckets(String text) {
        float[] vector = new float[DIMENSIONS];

        for (String word : text.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (word.isEmpty()) {
                continue;
            }
            int bucket = Math.floorMod(word.hashCode(), DIMENSIONS);
            vector[bucket] += 1f;
        }

        return normalise(vector);
    }

    /**
     * Scales to unit length, so cosine distance depends on which words appear
     * rather than on how long the passage is.
     */
    private static float[] normalise(float[] vector) {
        double sumOfSquares = 0;
        for (float value : vector) {
            sumOfSquares += (double) value * value;
        }
        if (sumOfSquares == 0) {
            // A passage with no words at all still needs a usable vector; an
            // all-zero one has no direction and cosine distance is undefined.
            vector[0] = 1f;
            return vector;
        }

        float length = (float) Math.sqrt(sumOfSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= length;
        }
        return vector;
    }

    /** Exposed so query embedding in tests matches how passages were embedded. */
    public static List<float[]> embed(List<String> texts) {
        return texts.stream().map(FakeEmbeddingConfiguration::hashIntoBuckets).toList();
    }
}
