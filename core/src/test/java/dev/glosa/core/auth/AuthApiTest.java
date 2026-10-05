package dev.glosa.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.glosa.core.support.PostgresIntegrationTest;
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
@DisplayName("Authentication API")
class AuthApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void clean() {
        asOwner().execute("truncate table collection, app_user, tenant cascade");
    }

    @Test
    @DisplayName("registering creates the organisation and its first administrator")
    void registersATenant() throws Exception {
        register("acme", "ada@acme.test")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("acme"))
                .andExpect(jsonPath("$.administratorId").exists());

        assertThat(asOwner().queryForObject(
                "select role from app_user where email = ?", String.class, "ada@acme.test"))
                .isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("the password is never stored as given")
    void storesOnlyAHash() throws Exception {
        register("acme", "ada@acme.test").andExpect(status().isCreated());

        String stored = asOwner().queryForObject(
                "select password_hash from app_user where email = ?", String.class, "ada@acme.test");

        assertThat(stored).doesNotContain(PASSWORD);
        // The algorithm travels with the hash, so it can be migrated later
        // without invalidating everyone's password.
        assertThat(stored).startsWith("{bcrypt}$2");
    }

    @Test
    @DisplayName("a taken handle is refused")
    void refusesADuplicateSlug() throws Exception {
        register("acme", "ada@acme.test").andExpect(status().isCreated());

        register("acme", "grace@acme.test")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Already exists"));
    }

    @Test
    @DisplayName("a weak password is refused before anything is created")
    void refusesAShortPassword() throws Exception {
        mockMvc.perform(post("/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("acme", "Acme Corp", "ada@acme.test", "short")))
                .andExpect(status().isBadRequest());

        assertThat(asOwner().queryForObject("select count(*) from tenant", Integer.class)).isZero();
    }

    @Test
    @DisplayName("signing in returns a token that the API accepts")
    void signsInAndUsesTheToken() throws Exception {
        register("acme", "ada@acme.test").andExpect(status().isCreated());

        String token = login("acme", "ada@acme.test", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");

        // The round trip that matters: a token minted by login is accepted by the
        // very chain that guards the rest of the API.
        mockMvc.perform(get("/v1/collections").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a wrong password and an unknown account answer identically")
    void doesNotRevealWhichAccountsExist() throws Exception {
        register("acme", "ada@acme.test").andExpect(status().isCreated());

        String wrongPassword = login("acme", "ada@acme.test", "not-the-password")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownUser = login("acme", "nobody@acme.test", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownTenant = login("nowhere", "ada@acme.test", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Any difference here would turn the endpoint into a directory of who
        // has an account.
        assertThat(wrongPassword).isEqualTo(unknownUser).isEqualTo(unknownTenant);
    }

    @Test
    @DisplayName("an administrator adds a user, who signs in with their own role")
    void addsAUserWhoCanSignIn() throws Exception {
        register("acme", "ada@acme.test").andExpect(status().isCreated());
        String adminToken = tokenFor("acme", "ada@acme.test");

        mockMvc.perform(post("/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"reader@acme.test","password":"%s","role":"VIEWER"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("VIEWER"))
                // A hash must never travel outward, not even to an administrator.
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String viewerToken = tokenFor("acme", "reader@acme.test");
        mockMvc.perform(post("/v1/collections")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a viewer may not add users")
    void refusesUserCreationForNonAdministrators() throws Exception {
        register("acme", "ada@acme.test").andExpect(status().isCreated());
        String adminToken = tokenFor("acme", "ada@acme.test");
        mockMvc.perform(post("/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"reader@acme.test","password":"%s","role":"VIEWER"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/v1/users")
                        .header("Authorization", "Bearer " + tokenFor("acme", "reader@acme.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"sneak@acme.test","password":"%s","role":"ADMIN"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("two organisations may use the same email independently")
    void allowsTheSameEmailInDifferentTenants() throws Exception {
        register("acme", "ada@shared.test").andExpect(status().isCreated());
        register("globex", "ada@shared.test").andExpect(status().isCreated());

        // Each sign-in lands in its own tenant, so a collection made under one is
        // invisible to the other.
        mockMvc.perform(post("/v1/collections")
                        .header("Authorization", "Bearer " + tokenFor("acme", "ada@shared.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme only\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/collections")
                        .header("Authorization", "Bearer " + tokenFor("globex", "ada@shared.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    private org.springframework.test.web.servlet.ResultActions register(String slug, String email)
            throws Exception {
        return mockMvc.perform(post("/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(slug, "Organisation " + slug, email, PASSWORD)));
    }

    private org.springframework.test.web.servlet.ResultActions login(
            String slug, String email, String password) throws Exception {
        return mockMvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"tenantSlug":"%s","email":"%s","password":"%s"}
                        """.formatted(slug, email, password)));
    }

    private String tokenFor(String slug, String email) throws Exception {
        return login(slug, email, PASSWORD)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private static String body(String slug, String name, String email, String password) {
        return """
                {"slug":"%s","organisationName":"%s","email":"%s","password":"%s"}
                """.formatted(slug, name, email, password);
    }
}
