package dev.glosa.core.rag;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the rag service is and how long to wait for it.
 *
 * @param baseUrl   root URL of the rag service
 * @param timeout   per-request ceiling; generous because a cold model is loaded
 *                  on the first call and that can take a while
 * @param batchSize passages embedded per call, bounded to match what the rag
 *                  service accepts
 */
@ConfigurationProperties("glosa.rag")
public record RagProperties(String baseUrl, Duration timeout, int batchSize) {
}
