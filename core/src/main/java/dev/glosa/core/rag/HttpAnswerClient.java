package dev.glosa.core.rag;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Answers from the rag service, relayed as they are produced. */
@Component
class HttpAnswerClient implements AnswerClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Size of a read, not of the answer: small enough that text appears promptly. */
    private static final int READ_BUFFER = 256;

    private final RestClient restClient;

    HttpAnswerClient(RagProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(properties.timeout());

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public void streamAnswer(String question, List<String> passages, Consumer<String> onToken) {
        try {
            restClient.post()
                    .uri("/v1/answer")
                    .body(new AnswerRequest(question, passages))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new AnswerUnavailableException(
                                    "The rag service answered " + response.getStatusCode());
                        }
                        relay(response.getBody(), onToken);
                        return null;
                    });
        } catch (ResourceAccessException e) {
            throw new AnswerUnavailableException("The rag service could not be reached", e);
        } catch (RestClientResponseException e) {
            throw new AnswerUnavailableException("The rag service answered " + e.getStatusCode(), e);
        }
    }

    /**
     * Forwards text as it arrives rather than collecting it first.
     *
     * <p>Buffering the whole answer here would undo the streaming the rag
     * service went to the trouble of providing.
     */
    private static void relay(java.io.InputStream body, Consumer<String> onToken) {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            char[] buffer = new char[READ_BUFFER];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (read > 0) {
                    onToken.accept(new String(buffer, 0, read));
                }
            }
        } catch (IOException e) {
            throw new AnswerUnavailableException("The answer stream ended unexpectedly", e);
        }
    }

    private record AnswerRequest(String question, List<String> passages) {
    }
}
