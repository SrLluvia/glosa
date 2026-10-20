package dev.glosa.core.rag;

import java.util.List;
import java.util.function.Consumer;

/**
 * Builds an answer from passages.
 *
 * <p>An interface we own, so the rest of the service never depends on which
 * model is behind it, and so tests can answer instantly without one.
 */
public interface AnswerClient {

    /**
     * Streams an answer, handing each piece of text to {@code onToken} as it
     * arrives.
     *
     * <p>Streaming rather than returning a string: a grounded answer takes
     * several seconds on a local model, and the difference between watching it
     * appear and staring at nothing is the difference between a product that
     * feels alive and one that feels broken.
     *
     * @param passages retrieved passages, most relevant first; citations in the
     *                 answer refer to their positions, counting from one
     * @throws AnswerUnavailableException if generation cannot start
     */
    void streamAnswer(String question, List<String> passages, Consumer<String> onToken);
}
