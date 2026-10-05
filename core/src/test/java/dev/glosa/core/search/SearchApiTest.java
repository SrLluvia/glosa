package dev.glosa.core.search;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

/**
 * Exercises the whole path: a document is ingested, and the passages it produced
 * are then searchable and attributable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Search API")
class SearchApiTest extends PostgresIntegrationTest {

    private static final UUID ACME = UUID.fromString("f1110000-0000-0000-0000-000000000001");
    private static final UUID GLOBEX = UUID.fromString("f2220000-0000-0000-0000-000000000002");
    private static final UUID ACME_COLLECTION = UUID.fromString("f1110000-0000-0000-0000-0000000000aa");
    private static final UUID GLOBEX_COLLECTION = UUID.fromString("f2220000-0000-0000-0000-0000000000bb");
    private static final UUID USER = UUID.fromString("f3330000-0000-0000-0000-000000000003");

    private static final String ACME_HANDBOOK = """
            # Holiday allowance

            Everyone at Acme gets twenty-three days of paid leave each year, and
            the days must be taken before December ends or they are lost.

            # Expense claims

            Expenses go in before the fifth of the following month. Anything that
            arrives later is paid in the cycle after that one instead.
            """;

    private static final String GLOBEX_HANDBOOK = """
            # Holiday allowance

            Globex staff receive thirty-one days of paid leave every year, which
            is rather more generous than most of the competition manages.
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenIssuer issuer;

    @Autowired
    private IngestionService ingestion;

    @BeforeEach
    void seedAndIngest() {
        asOwner().execute("truncate table collection, app_user, tenant cascade");
        seedTenant(ACME, "acme", ACME_COLLECTION);
        seedTenant(GLOBEX, "globex", GLOBEX_COLLECTION);

        queue(ACME, ACME_COLLECTION, ACME_HANDBOOK);
        queue(GLOBEX, GLOBEX_COLLECTION, GLOBEX_HANDBOOK);

        ingestFor(ACME);
        ingestFor(GLOBEX);
    }

    @Test
    @DisplayName("finds the passage that answers the question, and says where it came from")
    void findsAndAttributesAPassage() throws Exception {
        mockMvc.perform(get("/v1/collections/{id}/search", ACME_COLLECTION)
                        .param("q", "holiday leave")
                        .header("Authorization", bearer(ACME, Role.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passages[0].content").value(
                        org.hamcrest.Matchers.containsString("twenty-three days")))
                // Without these an answer could not be checked by the person
                // reading it, which is the whole point of the product.
                .andExpect(jsonPath("$.passages[0].heading").value("Holiday allowance"))
                .andExpect(jsonPath("$.passages[0].filename").value("handbook.md"));
    }

    @Test
    @DisplayName("ranks the passage that is actually about the question first")
    void ranksTheRelevantPassageFirst() throws Exception {
        mockMvc.perform(get("/v1/collections/{id}/search", ACME_COLLECTION)
                        .param("q", "expenses claim month")
                        .header("Authorization", bearer(ACME, Role.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passages[0].heading").value("Expense claims"));
    }

    @Test
    @DisplayName("never reaches another tenant's passages, even for the same words")
    void doesNotSearchAcrossTenants() throws Exception {
        // Both tenants have a "Holiday allowance" section, with different numbers.
        mockMvc.perform(get("/v1/collections/{id}/search", ACME_COLLECTION)
                        .param("q", "holiday leave")
                        .header("Authorization", bearer(ACME, Role.VIEWER)))
                .andExpect(jsonPath("$.passages[*].content").value(
                        org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.not(
                                        org.hamcrest.Matchers.containsString("thirty-one")))));
    }

    @Test
    @DisplayName("another tenant's collection is not searchable at all")
    void refusesAnotherTenantsCollection() throws Exception {
        mockMvc.perform(get("/v1/collections/{id}/search", GLOBEX_COLLECTION)
                        .param("q", "holiday")
                        .header("Authorization", bearer(ACME, Role.VIEWER)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a query with no matches returns an empty result, not an error")
    void returnsNothingForAnUnmatchedQuery() throws Exception {
        mockMvc.perform(get("/v1/collections/{id}/search", ACME_COLLECTION)
                        .param("q", "submarine periscope")
                        .header("Authorization", bearer(ACME, Role.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("punctuation a person might type does not break the query")
    void toleratesOperatorsInTheQuery() throws Exception {
        // websearch_to_tsquery accepts what someone would actually type. The
        // older to_tsquery would raise a syntax error on this.
        mockMvc.perform(get("/v1/collections/{id}/search", ACME_COLLECTION)
                        .param("q", "\"holiday allowance\" -submarine &|!()")
                        .header("Authorization", bearer(ACME, Role.VIEWER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an oversized result limit is refused")
    void rejectsAnOversizedLimit() throws Exception {
        mockMvc.perform(get("/v1/collections/{id}/search", ACME_COLLECTION)
                        .param("q", "holiday")
                        .param("limit", "5000")
                        .header("Authorization", bearer(ACME, Role.VIEWER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("searching without a token is refused")
    void refusesAnUnauthenticatedSearch() throws Exception {
        mockMvc.perform(get("/v1/collections/{id}/search", ACME_COLLECTION).param("q", "holiday"))
                .andExpect(status().isUnauthorized());
    }

    private void seedTenant(UUID tenantId, String slug, UUID collectionId) {
        asOwner().update("insert into tenant (id, slug, name) values (?, ?, ?)",
                tenantId, slug, "Organisation " + slug);
        asOwner().update("insert into collection (id, tenant_id, name) values (?, ?, ?)",
                collectionId, tenantId, "Handbook");
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

    private void ingestFor(UUID tenantId) {
        TenantContext.runWith(tenantId, () -> ingestion.claimDueJobs(10).forEach(ingestion::runJob));
    }

    private String bearer(UUID tenantId, Role role) {
        return "Bearer " + issuer.issue(USER, tenantId, role).value();
    }
}
