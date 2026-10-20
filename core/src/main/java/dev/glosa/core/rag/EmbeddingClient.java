package dev.glosa.core.rag;

import java.util.List;

/**
 * Turns text into vectors.
 *
 * <p>An interface we own, so the pipeline does not depend on how embeddings are
 * produced and can be tested without a model running.
 */
public interface EmbeddingClient {

    /**
     * Embeds several texts at once.
     *
     * @return one vector per input, in the same order
     * @throws EmbeddingUnavailableException when the provider cannot be reached
     *                                       or has no model, which is a
     *                                       temporary condition worth retrying
     */
    List<float[]> embed(List<String> texts);
}
