package dev.glosa.core.support;

import dev.glosa.core.rag.AnswerClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Answers for tests, without a model.
 *
 * <p>Echoes back how many passages it was given and cites the first, which is
 * enough to prove the wiring: that retrieval ran, that passages reached the
 * generator in order, and that the stream is framed correctly. Whether a real
 * model stays faithful to its sources is measured by the evaluation suite, not
 * asserted here.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeAnswerConfiguration {

    @Bean
    @Primary
    AnswerClient fakeAnswerClient() {
        return (question, passages, onToken) -> {
            onToken.accept("Answer drawn from " + passages.size() + " passages");
            onToken.accept(" [1].");
        };
    }
}
