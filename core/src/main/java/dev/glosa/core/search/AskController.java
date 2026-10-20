package dev.glosa.core.search;

import dev.glosa.core.rag.AnswerClient;
import dev.glosa.core.rag.AnswerUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Asking a question of a collection.
 *
 * <p>Answered over server-sent events rather than as one response. Generation
 * takes seconds, and the citations have to reach the client before the prose
 * does so it can be rendered alongside the answer as it appears.
 */
@RestController
@RequestMapping("/v1/collections/{collectionId}")
@Tag(name = "Ask", description = "Questions answered from the documents, with citations")
class AskController {

    private static final Logger log = LoggerFactory.getLogger(AskController.class);

    /**
     * How many passages are handed to the model.
     *
     * <p>Small on purpose. Past a handful the prompt fills with material that is
     * only loosely related, and a model asked to answer from twenty passages
     * reliably picks worse evidence than one given five.
     */
    private static final int PASSAGES_FOR_ANSWER = 5;

    private static final long STREAM_TIMEOUT_MILLIS = 300_000L;

    /**
     * Beyond this cosine distance a passage is not worth answering from.
     *
     * <p>Vector search returns the nearest passages however far away they are,
     * and nearest is not relevant: with nothing about pensions in a collection,
     * the holiday policy is the closest match to a question about pensions.
     *
     * <p>Calibrated rather than guessed. On the evaluation set the furthest
     * passage that did answer its question sat at 0.507, so the line is drawn
     * just above it: everything that genuinely answered still gets through.
     * <p>The two populations overlap, so this alone cannot catch every
     * unanswerable question, and the prompt carries the rest of that load.
     *
     * <p>Configurable because the scale belongs to the embedding model: a
     * different model puts the same two texts at a different distance, and the
     * number would have to be measured again.
     */
    private final double maxUsefulDistance;

    private final HybridSearchService search;
    private final AnswerClient answers;

    AskController(HybridSearchService search, AnswerClient answers,
            @org.springframework.beans.factory.annotation.Value(
                    "${glosa.search.max-useful-distance}") double maxUsefulDistance) {
        this.search = search;
        this.answers = answers;
        this.maxUsefulDistance = maxUsefulDistance;
    }

    @Schema(name = "Citation", description = "A passage the answer was built from")
    record Citation(
            @Schema(description = "Number referred to in the answer text, as [1]") int number,
            UUID chunkId,
            UUID documentId,
            String filename,
            Integer pageNumber,
            String heading,
            String content) {
    }

    @GetMapping(path = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Ask a question of a collection",
            description = """
                    Retrieves the most relevant passages and has a model answer from those
                    alone, streaming the result.

                    Events arrive in this order: one `citations` event carrying the passages
                    the answer may draw on, then a series of `token` events with the text, then
                    one `done` event. Citations come first so they can be rendered beside the
                    answer as it appears rather than after it finishes.

                    The model is instructed to use nothing but the passages and to say when
                    they do not cover the question. An answer of "the documents do not say" is
                    a correct outcome, not a failure.
                    """)
    SseEmitter ask(
            @PathVariable UUID collectionId,
            @RequestParam @NotBlank @Size(max = 2000) String q,
            @RequestParam(defaultValue = "5") @Min(1) @Max(10) int passages) {

        List<HybridSearchService.RankedPassage> retrieved =
                search.search(collectionId, q, Math.min(passages, PASSAGES_FOR_ANSWER));

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);

        List<HybridSearchService.RankedPassage> relevant = retrieved.stream()
                .filter(this::isCloseEnough)
                .toList();

        if (relevant.isEmpty()) {
            // Nothing retrieved means nothing to ground an answer in, and asking
            // the model anyway is precisely how a system starts inventing.
            send(emitter, "citations", List.of());
            send(emitter, "token", "The documents in this collection do not cover that question.");
            send(emitter, "done", "");
            emitter.complete();
            return emitter;
        }

        send(emitter, "citations", toCitations(relevant));

        // Off the request thread, so the container is not held for the length of
        // a generation that can run into minutes.
        Thread.ofVirtual().name("answer-" + collectionId).start(() -> {
            try {
                answers.streamAnswer(q, relevant.stream()
                        .map(HybridSearchService.RankedPassage::content)
                        .toList(), token -> send(emitter, "token", token));
                send(emitter, "done", "");
                emitter.complete();
            } catch (AnswerUnavailableException e) {
                log.warn("Could not answer a question for collection {}", collectionId, e);
                // The stream is already open, so the status code is long gone.
                // Saying so beats stopping silently, which reads as a finished
                // answer that happens to be short.
                send(emitter, "error", "The answer could not be generated. Try again shortly.");
                emitter.complete();
            } catch (RuntimeException e) {
                log.error("Unexpected failure while answering for collection {}", collectionId, e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Whether a passage is near enough to be worth citing.
     *
     * <p>A passage found only by keyword search has no distance. It is kept:
     * matching the words of the question is its own evidence of relevance, and
     * discarding it would throw away the half of retrieval that handles exact
     * identifiers.
     */
    private boolean isCloseEnough(HybridSearchService.RankedPassage passage) {
        return passage.semanticDistance() == null
                || passage.semanticDistance() <= maxUsefulDistance;
    }

    private static List<Citation> toCitations(List<HybridSearchService.RankedPassage> passages) {
        return java.util.stream.IntStream.range(0, passages.size())
                .mapToObj(index -> {
                    HybridSearchService.RankedPassage passage = passages.get(index);
                    // Numbered from one, matching what the model is told to cite.
                    return new Citation(index + 1, passage.chunkId(), passage.documentId(),
                            passage.filename(), passage.pageNumber(), passage.heading(),
                            passage.content());
                })
                .toList();
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            // The client went away, or the stream is already finished. Neither
            // is worth an error: there is nobody left to tell.
            log.debug("Could not send a {} event; the client is gone", event);
        }
    }
}
