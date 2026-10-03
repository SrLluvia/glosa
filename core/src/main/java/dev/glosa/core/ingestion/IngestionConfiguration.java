package dev.glosa.core.ingestion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the scheduler only when the worker is enabled, so a service running
 * with ingestion switched off starts no timer threads at all.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IngestionProperties.class)
@ConditionalOnProperty(prefix = "glosa.ingestion", name = "enabled", havingValue = "true")
@EnableScheduling
class IngestionConfiguration {
}
