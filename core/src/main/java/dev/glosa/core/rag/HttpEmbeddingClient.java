package dev.glosa.core.rag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Embeddings from the rag service.
 *
 * <p>Texts are sent in batches rather than one at a time. Ingesting a document
 * produces dozens of passages, and a round trip each would dominate the time
 * the whole pipeline takes.
 */
@Component
class HttpEmbeddingClient implements EmbeddingClient {

    private final RestClient restClient;
    private final RagProperties properties;

    /** Short enough that nothing waits forever on a service that has stalled. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    HttpEmbeddingClient(RagProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        // The read timeout is the generous one: a cold model is loaded on the
        // first call, and that is the slow case worth waiting out.
        factory.setReadTimeout(properties.timeout());

        // Built here rather than injected: this client needs its own timeouts
        // and base URL, and sharing an application-wide builder would make both
        // depend on configuration that belongs to somebody else.
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += properties.batchSize()) {
            int end = Math.min(start + properties.batchSize(), texts.size());
            vectors.addAll(embedBatch(texts.subList(start, end)));
        }
        return vectors;
    }

    private List<float[]> embedBatch(List<String> batch) {
        EmbedResponse response;
        try {
            response = restClient.post()
                    .uri("/v1/embed")
                    .body(new EmbedRequest(batch))
                    .retrieve()
                    .body(EmbedResponse.class);
        } catch (ResourceAccessException e) {
            throw new EmbeddingUnavailableException("The rag service could not be reached", e);
        } catch (RestClientResponseException e) {
            throw new EmbeddingUnavailableException(
                    "The rag service answered " + e.getStatusCode(), e);
        }

        if (response == null || response.embeddings() == null
                || response.embeddings().size() != batch.size()) {
            // Vectors are paired with passages by position, so a short or
            // reordered reply would attach the wrong meaning to the wrong text.
            throw new EmbeddingUnavailableException(
                    "The rag service returned a different number of vectors than texts sent");
        }

        return response.embeddings().stream().map(HttpEmbeddingClient::toFloats).toList();
    }

    private static float[] toFloats(List<Double> values) {
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            // pgvector stores single precision, so the narrowing happens either
            // way; doing it here keeps the stored vector and the one we hold in
            // memory identical.
            vector[i] = values.get(i).floatValue();
        }
        return vector;
    }

    private record EmbedRequest(List<String> texts) {
    }

    private record EmbedResponse(String model, int dimensions, List<List<Double>> embeddings) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RagProperties.class)
    static class RagConfiguration {
    }
}
