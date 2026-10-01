package dev.glosa.core.config;

import com.zaxxer.hikari.HikariDataSource;
import dev.glosa.core.tenant.TenantAwareDataSource;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Declares the application {@link DataSource} explicitly so that every
 * connection it hands out is scoped to a tenant.
 *
 * <p>Flyway is not wired through here. It is configured with the owner
 * credentials and builds its own connections, because migrations change schema,
 * which the application role is not allowed to do, and they are not scoped to
 * any single tenant.
 */
@Configuration(proxyBeanMethods = false)
class DataSourceConfiguration {

    /** The real pool, kept as its own bean so Hikari settings still apply. */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource pooledDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    DataSource tenantAwareDataSource(HikariDataSource pooledDataSource) {
        return new TenantAwareDataSource(pooledDataSource);
    }
}
