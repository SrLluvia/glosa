package dev.glosa.core.search;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.glosa.core.auth.AccessTokenIssuer;
import dev.glosa.core.auth.Role;
import dev.glosa.core.ingestion.IngestionService;
import dev.glosa.core.support.PostgresIntegrationTest;
import dev.glosa.core.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The relevance threshold that stops the system answering from whatever passage
 * happened to be nearest.
 *
 * <p>A class of its own, with its own fixtures, because the property has to be
 * set when the context is built. Extending the main ask tests would have
 * dragged every one of them into this stricter configuration, where they
 * correctly fail.
 *
 * <p>The threshold is exercised at an artificial value. The stand-in embeddings
 * hash words into buckets, so their distances bear no relation to the scale a
 * trained model works on, and a realistic number here would prove nothing. What
 * this pins is the behaviour; the number itself is calibrated by the evaluation
 * suite against a real model.
 */
@SpringBootTest(properties = "glosa.search.max-useful-distance=0.0001")
@AutoConfigureMockMvc
@DisplayName("Ask API, relevance threshold")
class AskRelevanceThresholdTest extends PostgresIntegrationTest {

    private static final UUID TENANT = UUID.fromString("ac110000-0000-0000-0000-000000000001");
    private static final UUID COLLECTION = UUID.fromString("ac110000-0000-0000-0000-0000000000aa");
    private static final UUID USER = UUID.fromString("ac110000-0000-0000-0000-000000000003");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenIssuer issuer;

    @Autowired
    private IngestionService ingestion;

    @BeforeEach
    void seed() {
        asOwner().execute("truncate table collection, app_user, tenant cascade");
        asOwner().update("insert into tenant (id, slug, name) values (?, ?, ?)",
                TENANT, "threshold", "Threshold");
        asOwner().update("insert into collection (id, tenant_id, name) values (?, ?, ?)",
                COLLECTION, TENANT, "Handbook");

        String body = "# Holiday allowance\n\nEveryone receives twenty-three days of paid leave.\n";
        UUID document = UUID.randomUUID();
        String hash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        asOwner().update("""
                insert into document (id, tenant_id, collection_id, filename, content_type,
                                      byte_size, content_hash, status)
                values (?, ?, ?, 'handbook.md', 'text/markdown', ?, ?, 'PENDING')
                """, document, TENANT, COLLECTION, body.length(), hash);
        asOwner().update("insert into document_content (document_id, tenant_id, bytes) values (?, ?, ?)",
                document, TENANT, body.getBytes(StandardCharsets.UTF_8));
        asOwner().update("insert into ingestion_job (tenant_id, document_id) values (?, ?)",
                TENANT, document);

        TenantContext.runWith(TENANT, () -> ingestion.claimDueJobs(10).forEach(ingestion::runJob));
    }

    @Test
    @DisplayName("refuses rather than answering from whatever happened to be nearest")
    void refusesWhenNothingIsCloseEnough() throws Exception {
        // Vector search returns its nearest neighbours however far away they
        // are, so without this guard the model is handed irrelevant passages
        // and answers from them. That is how the system came to answer every
        // question its documents did not cover: it never saw an empty result.
        MvcResult result = mockMvc.perform(get("/v1/collections/{id}/ask", COLLECTION)
                        .param("q", "What is the pension scheme?")
                        .header("Authorization",
                                "Bearer " + issuer.issue(USER, TENANT, Role.VIEWER).value()))
                .andExpect(status().isOk())
                .andReturn();

        result.getAsyncResult(10_000);

        mockMvc.perform(get("/v1/collections/{id}/ask", COLLECTION)
                .param("q", "What is the pension scheme?")
                .header("Authorization",
                        "Bearer " + issuer.issue(USER, TENANT, Role.VIEWER).value()));

        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .contains("do not cover that question")
                .doesNotContain("Answer drawn from");
    }

    @Test
    @DisplayName("sends no citations when nothing was relevant")
    void citesNothingWhenNothingIsCloseEnough() throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/collections/{id}/ask", COLLECTION)
                        .param("q", "What is the pension scheme?")
                        .header("Authorization",
                                "Bearer " + issuer.issue(USER, TENANT, Role.VIEWER).value()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("event:citations")))
                .andReturn();

        result.getAsyncResult(10_000);

        // An empty citation list, not a list of irrelevant passages. Offering
        // sources for an answer that was refused would be worse than offering
        // none.
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .contains("data:[]");
    }
}
