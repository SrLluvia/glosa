package dev.glosa.core.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The published API description.
 *
 * <p>Declaring the bearer scheme globally is what lets the generated UI hold a
 * token and actually exercise the endpoints, rather than only describe them.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI glosaOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Glosa API")
                        .version("v1")
                        .description("""
                                Multi-tenant retrieval over your own documents, with answers \
                                grounded in them and cited back to the page they came from.

                                Every request is scoped to the tenant named in the access \
                                token. The tenant is never taken from a header, a path or a \
                                body field, and the database enforces the boundary \
                                independently of this API."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token issued by this service")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
