package dev.glosa.core.ingestion;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the ingestion worker runs.
 *
 * @param enabled      false turns the worker off, which is what tests do so they
 *                     can drive the pipeline step by step instead of racing it
 * @param pollInterval delay between sweeps of the queue
 * @param batchSize    jobs claimed per tenant per sweep
 * @param staleAfter   how long a RUNNING job may go untouched before it is
 *                     assumed abandoned and returned to the queue
 */
@ConfigurationProperties("glosa.ingestion")
public record IngestionProperties(
        boolean enabled,
        Duration pollInterval,
        int batchSize,
        Duration staleAfter) {
}
