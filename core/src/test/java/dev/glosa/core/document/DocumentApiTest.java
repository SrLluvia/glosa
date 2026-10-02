package dev.glosa.core.document;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.glosa.core.auth.AccessTokenIssuer;
import dev.glosa.core.auth.Role;
import dev.glosa.core.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Documents API")
class DocumentApiTest extends PostgresIntegrationTest {

    private static final UUID ACME = UUID.fromString("88888888-0000-0000-0000-000000000001");
    private static final UUID GLOBEX = UUID.fromString("99999999-0000-0000-0000-000000000002");
    private static final UUID USER = UUID.fromString("aaaa0000-0000-0000-0000-000000000003");

    private static final UUID ACME_COLLECTION = UUID.fromString("bbbb0000-0000-0000-0000-000000000004");
    private static final UUID GLOBEX_COLLECTION = UUID.fromString("cccc0000-0000-0000-0000-000000000005");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenIssuer issuer;

    @BeforeEach
    void seed() {
        asOwner().execute("truncate table collection, app_user, tenant cascade");
        asOwner().update("insert into tenant (id, slug, name) values (?, ?, ?)", ACME, "acme", "Acme Corp");
        asOwner().update("insert into tenant (id, slug, name) values (?, ?, ?)", GLOBEX, "globex", "Globex");
        asOwner().update("insert into collection (id, tenant_id, name) values (?, ?, ?)",
                ACME_COLLECTION, ACME, "Acme handbook");
        asOwner().update("insert into collection (id, tenant_id, name) values (?, ?, ?)",
                GLOBEX_COLLECTION, GLOBEX, "Globex handbook");
    }

    @Test
    @DisplayName("an editor uploads a document, which comes back queued")
    void uploadsADocument() throws Exception {
        mockMvc.perform(multipart("/v1/collections/{id}/documents", ACME_COLLECTION)
                        .file(markdown("handbook.md", "# Onboarding\n\nWelcome aboard."))
                        .header("Authorization", bearer(ACME, Role.EDITOR)))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.filename").value("handbook.md"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.failureReason").doesNotExist());
    }

    @Test
    @DisplayName("the upload is queued for ingestion in the same transaction")
    void queuesTheUploadForIngestion() throws Exception {
        upload(ACME, ACME_COLLECTION, markdown("handbook.md", "# Onboarding"));

        Integer queued = asOwner().queryForObject(
                "select count(*) from ingestion_job where state = 'QUEUED'", Integer.class);

        // Were storing and enqueueing split across transactions, a crash in
        // between would leave a document no worker ever picks up.
        org.assertj.core.api.Assertions.assertThat(queued).isEqualTo(1);
    }

    @Test
    @DisplayName("a viewer may not upload")
    void refusesUploadForAViewer() throws Exception {
        mockMvc.perform(multipart("/v1/collections/{id}/documents", ACME_COLLECTION)
                        .file(markdown("handbook.md", "# Onboarding"))
                        .header("Authorization", bearer(ACME, Role.VIEWER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a content type the pipeline cannot read is refused before it is stored")
    void refusesAnUnsupportedContentType() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "file", "diagram.png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'});

        mockMvc.perform(multipart("/v1/collections/{id}/documents", ACME_COLLECTION)
                        .file(image)
                        .header("Authorization", bearer(ACME, Role.EDITOR)))
                .andExpect(status().isUnsupportedMediaType());

        org.assertj.core.api.Assertions.assertThat(
                        asOwner().queryForObject("select count(*) from document", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("the same bytes uploaded twice are recognised rather than stored again")
    void rejectsADuplicateUpload() throws Exception {
        upload(ACME, ACME_COLLECTION, markdown("handbook.md", "# Onboarding"));

        // A different filename, identical bytes: deduplication is by content hash.
        mockMvc.perform(multipart("/v1/collections/{id}/documents", ACME_COLLECTION)
                        .file(markdown("copy-of-handbook.md", "# Onboarding"))
                        .header("Authorization", bearer(ACME, Role.EDITOR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Already exists"));
    }

    @Test
    @DisplayName("a path in the filename is stripped before it is stored")
    void stripsAPathFromTheFilename() throws Exception {
        mockMvc.perform(multipart("/v1/collections/{id}/documents", ACME_COLLECTION)
                        .file(markdown("../../../etc/passwd", "# Onboarding"))
                        .header("Authorization", bearer(ACME, Role.EDITOR)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.filename").value("passwd"));
    }

    @Test
    @DisplayName("uploading into another tenant's collection reports not found")
    void refusesUploadIntoAnotherTenantsCollection() throws Exception {
        mockMvc.perform(multipart("/v1/collections/{id}/documents", GLOBEX_COLLECTION)
                        .file(markdown("handbook.md", "# Onboarding"))
                        .header("Authorization", bearer(ACME, Role.EDITOR)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("another tenant's document is invisible")
    void hidesAnotherTenantsDocument() throws Exception {
        String location = upload(GLOBEX, GLOBEX_COLLECTION, markdown("secret.md", "# Globex only"))
                .replace("/v1/documents/", "");

        mockMvc.perform(get("/v1/documents/{id}", location)
                        .header("Authorization", bearer(ACME, Role.VIEWER)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("listing a collection shows only its own documents")
    void listsDocumentsOfTheCollection() throws Exception {
        upload(ACME, ACME_COLLECTION, markdown("one.md", "# One"));
        upload(GLOBEX, GLOBEX_COLLECTION, markdown("two.md", "# Two"));

        mockMvc.perform(get("/v1/collections/{id}/documents", ACME_COLLECTION)
                        .header("Authorization", bearer(ACME, Role.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].filename").value("one.md"));
    }

    private static MockMultipartFile markdown(String filename, String body) {
        return new MockMultipartFile(
                "file", filename, "text/markdown", body.getBytes(StandardCharsets.UTF_8));
    }

    /** Uploads and returns the Location header. */
    private String upload(UUID tenant, UUID collectionId, MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart("/v1/collections/{id}/documents", collectionId)
                        .file(file)
                        .header("Authorization", bearer(tenant, Role.EDITOR)))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getHeader("Location");
    }

    private String bearer(UUID tenantId, Role role) {
        return "Bearer " + issuer.issue(USER, tenantId, role).value();
    }
}
