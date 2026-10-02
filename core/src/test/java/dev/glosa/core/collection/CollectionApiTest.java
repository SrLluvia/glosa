package dev.glosa.core.collection;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.glosa.core.auth.AccessTokenIssuer;
import dev.glosa.core.auth.Role;
import dev.glosa.core.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Collections API")
class CollectionApiTest extends PostgresIntegrationTest {

    private static final UUID ACME = UUID.fromString("55555555-0000-0000-0000-000000000001");
    private static final UUID GLOBEX = UUID.fromString("66666666-0000-0000-0000-000000000002");
    private static final UUID USER = UUID.fromString("77777777-0000-0000-0000-000000000003");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenIssuer issuer;

    @BeforeEach
    void seedTenants() {
        asOwner().execute("truncate table collection, app_user, tenant cascade");
        asOwner().update("insert into tenant (id, slug, name) values (?, ?, ?)", ACME, "acme", "Acme Corp");
        asOwner().update("insert into tenant (id, slug, name) values (?, ?, ?)", GLOBEX, "globex", "Globex");
    }

    @Test
    @DisplayName("an editor creates a collection in their own tenant")
    void createsACollection() throws Exception {
        mockMvc.perform(post("/v1/collections")
                        .header("Authorization", bearer(ACME, Role.EDITOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Engineering handbook\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.name").value("Engineering handbook"))
                .andExpect(jsonPath("$.id").exists())
                // The tenant is an internal concern; it must not be published.
                .andExpect(jsonPath("$.tenantId").doesNotExist());
    }

    @Test
    @DisplayName("a viewer may not create one")
    void refusesCreationForAViewer() throws Exception {
        mockMvc.perform(post("/v1/collections")
                        .header("Authorization", bearer(ACME, Role.VIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Should not exist\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a blank name is rejected before it reaches the database")
    void rejectsABlankName() throws Exception {
        mockMvc.perform(post("/v1/collections")
                        .header("Authorization", bearer(ACME, Role.EDITOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a duplicate name reports a conflict as a problem document")
    void reportsADuplicateName() throws Exception {
        createIn(ACME, "Engineering handbook");

        mockMvc.perform(post("/v1/collections")
                        .header("Authorization", bearer(ACME, Role.EDITOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Engineering handbook\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Already exists"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("the same name is free in another tenant")
    void allowsTheSameNameInAnotherTenant() throws Exception {
        createIn(ACME, "Engineering handbook");

        mockMvc.perform(post("/v1/collections")
                        .header("Authorization", bearer(GLOBEX, Role.EDITOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Engineering handbook\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("listing shows only the caller's own tenant")
    void listsOnlyTheCallersTenant() throws Exception {
        createIn(ACME, "Acme handbook");
        createIn(GLOBEX, "Globex handbook");

        mockMvc.perform(get("/v1/collections").header("Authorization", bearer(ACME, Role.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Acme handbook"));

        mockMvc.perform(get("/v1/collections").header("Authorization", bearer(GLOBEX, Role.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Globex handbook"));
    }

    @Test
    @DisplayName("another tenant's collection is reported as not found, not as forbidden")
    void hidesAnotherTenantsCollection() throws Exception {
        UUID globexCollection = createIn(GLOBEX, "Globex handbook");

        // 404 rather than 403 on purpose: answering "forbidden" would confirm
        // that this id exists somewhere in the system.
        mockMvc.perform(get("/v1/collections/{id}", globexCollection)
                        .header("Authorization", bearer(ACME, Role.VIEWER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("an oversized page is rejected rather than served")
    void rejectsAnOversizedPage() throws Exception {
        mockMvc.perform(get("/v1/collections")
                        .param("size", "1000")
                        .header("Authorization", bearer(ACME, Role.VIEWER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a request with no token is refused")
    void refusesAnUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/v1/collections"))
                .andExpect(status().isUnauthorized());
    }

    /** Inserts directly, as the owner, to arrange state without going through the API. */
    private UUID createIn(UUID tenantId, String name) {
        UUID id = UUID.randomUUID();
        asOwner().update("insert into collection (id, tenant_id, name) values (?, ?, ?)", id, tenantId, name);
        return id;
    }

    private String bearer(UUID tenantId, Role role) {
        return "Bearer " + issuer.issue(USER, tenantId, role).value();
    }
}
