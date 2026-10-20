package dev.glosa.core.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.glosa.core.auth.AccessTokenIssuer;
import dev.glosa.core.auth.Role;
import dev.glosa.core.ingestion.IngestionService;
import dev.glosa.core.support.PostgresIntegrationTest;
import dev.glosa.core.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Ask API")
class AskApiTest extends PostgresIntegrationTest {

    private static final UUID ACME = UUID.fromString("ab110000-0000-0000-0000-000000000001");
    private static final UUID GLOBEX = UUID.fromString("ab220000-0000-0000-0000-000000000002");
    private static final UUID ACME_COLLECTION = UUID.fromString("ab110000-0000-0000-0000-0000000000aa");
    private static final UUID EMPTY_COLLECTION = UUID.fromString("ab110000-0000-0000-0000-0000000000cc");
    private static final UUID GLOBEX_COLLECTION = UUID.fromString("ab220000-0000-0000-0000-0000000000bb");
    private static final UUID USER = UUID.fromString("ab330000-0000-0000-0000-000000000003");

    private static final String HANDBOOK = """
            # Holiday allowance

            Everyone receives twenty-three days of paid leave each year, and the
            days must be taken before December ends or they are simply lost.
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenIssuer issuer;

    @Autowired
    private IngestionService ingestion;

    @BeforeEach
    void seed() {
        asOwner().execute("truncate table collection, app_user, tenant cascade");
        tenant(ACME, "acme");
        tenant(GLOBEX, "globex");
        collection(ACME_COLLECTION, ACME, "Handbook");
        collection(EMPTY_COLLECTION, ACME, "Empty");
        collection(GLOBEX_COLLECTION, GLOBEX, "Handbook");

        queue(ACME, ACME_COLLECTION, HANDBOOK);
        TenantContext.runWith(ACME, () -> ingestion.claimDueJobs(10).forEach(ingestion::runJob));
    }

    @Test
    @DisplayName("sends citations before the answer text")
    void sendsCitationsFirst() throws Exception {
        String stream = ask(ACME, ACME_COLLECTION, "How much holiday leave?");

        // Order is part of the contract: a client renders the sources beside the
        // answer as it appears, which it cannot do if they arrive afterwards.
        assertThat(stream.indexOf("event:citations"))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(stream.indexOf("event:token"));
    }

    @Test
    @DisplayName("every citation carries enough to find the passage again")
    void citationsAreCheckable() throws Exception {
        String stream = ask(ACME, ACME_COLLECTION, "How much holiday leave?");

        assertThat(stream).contains("\"filename\":\"handbook.md\"");
        assertThat(stream).contains("\"heading\":\"Holiday allowance\"");
        // Numbered from one, matching the [1] the model is told to write.
        assertThat(stream).contains("\"number\":1");
    }

    @Test
    @DisplayName("closes the stream with a done event")
    void finishesWithDone() throws Exception {
        assertThat(ask(ACME, ACME_COLLECTION, "How much holiday leave?")).contains("event:done");
    }

    @Test
    @DisplayName("refuses to answer when nothing was retrieved")
    void refusesToAnswerWithoutEvidence() throws Exception {
        String stream = ask(ACME, EMPTY_COLLECTION, "How much holiday leave?");

        // Asking a model with no passages is exactly how a retrieval system
        // starts inventing, so it is not asked at all.
        assertThat(stream).contains("do not cover that question");
        assertThat(stream).doesNotContain("Answer drawn from");
    }

    @Test
    @DisplayName("another tenant's collection cannot be asked")
    void refusesAnotherTenantsCollection() throws Exception {
        mockMvc.perform(get("/v1/collections/{id}/ask", GLOBEX_COLLECTION)
                        .param("q", "How much holiday leave?")
                        .header("Authorization", bearer(ACME, Role.VIEWER)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("asking without a token is refused")
    void refusesAnonymousQuestions() throws Exception {
        mockMvc.perform(get("/v1/collections/{id}/ask", ACME_COLLECTION).param("q", "anything"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a viewer may ask")
    void allowsViewersToAsk() throws Exception {
        assertThat(ask(ACME, ACME_COLLECTION, "How much holiday leave?")).contains("event:token");
    }

    private String ask(UUID tenant, UUID collectionId, String question) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/collections/{id}/ask", collectionId)
                        .param("q", question)
                        .header("Authorization", bearer(tenant, Role.VIEWER)))
                .andExpect(status().isOk())
                .andReturn();

        // The generation runs on another thread, so the response is not finished
        // when the request returns.
        result.getAsyncResult(10_000);
        return result.getResponse().getContentAsString();
    }

    private void tenant(UUID id, String slug) {
        asOwner().update("insert into tenant (id, slug, name) values (?, ?, ?)", id, slug, slug);
    }

    private void collection(UUID id, UUID tenantId, String name) {
        asOwner().update("insert into collection (id, tenant_id, name) values (?, ?, ?)",
                id, tenantId, name);
    }

    private void queue(UUID tenantId, UUID collectionId, String body) {
        UUID id = UUID.randomUUID();
        String hash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        asOwner().update("""
                insert into document (id, tenant_id, collection_id, filename, content_type,
                                      byte_size, content_hash, status)
                values (?, ?, ?, 'handbook.md', 'text/markdown', ?, ?, 'PENDING')
                """, id, tenantId, collectionId, body.length(), hash);
        asOwner().update("insert into document_content (document_id, tenant_id, bytes) values (?, ?, ?)",
                id, tenantId, body.getBytes(StandardCharsets.UTF_8));
        asOwner().update("insert into ingestion_job (tenant_id, document_id) values (?, ?)", tenantId, id);
    }

    private String bearer(UUID tenantId, Role role) {
        return "Bearer " + issuer.issue(USER, tenantId, role).value();
    }
}
