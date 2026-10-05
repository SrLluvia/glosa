package dev.glosa.core.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@Tag(name = "Authentication", description = "Registering an organisation and signing in")
class AuthController {

    /**
     * Long enough that the bcrypt cost is doing useful work. Length is the
     * requirement that actually helps; composition rules mostly produce
     * predictable substitutions.
     */
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final AuthenticationService service;

    AuthController(AuthenticationService service) {
        this.service = service;
    }

    @Schema(name = "RegisterTenantRequest")
    record RegisterRequest(
            @Schema(description = "Handle used at sign-in", example = "acme")
            @NotBlank @Size(max = 39)
            @Pattern(regexp = "^[a-z0-9][a-z0-9-]*$",
                    message = "must be lowercase letters, digits and hyphens")
            String slug,

            @Schema(example = "Acme Corp") @NotBlank @Size(max = 200) String organisationName,

            @Schema(description = "Email of the first administrator", example = "ada@acme.test")
            @NotBlank @Email @Size(max = 320) String email,

            @NotBlank @Size(min = MIN_PASSWORD_LENGTH, max = 200) String password) {
    }

    @Schema(name = "RegisteredTenant")
    record RegisteredResponse(UUID tenantId, String slug, UUID administratorId) {
    }

    @Schema(name = "LoginRequest")
    record LoginRequest(
            @Schema(example = "acme") @NotBlank String tenantSlug,
            @Schema(example = "ada@acme.test") @NotBlank String email,
            @NotBlank String password) {
    }

    @Schema(name = "AccessToken")
    record TokenResponse(
            @Schema(description = "Send as: Authorization: Bearer <token>") String accessToken,
            String tokenType,
            Instant expiresAt) {
    }

    @Schema(name = "CreateUserRequest")
    record CreateUserRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = MIN_PASSWORD_LENGTH, max = 200) String password,
            @NotNull Role role) {
    }

    @Schema(name = "User")
    record UserResponse(UUID id, String email, Role role, Instant createdAt) {

        static UserResponse from(AppUser user) {
            return new UserResponse(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
        }
    }

    @PostMapping("/tenants")
    @SecurityRequirements // Open by definition: there is no tenant to belong to yet.
    @Operation(summary = "Register an organisation",
            description = "Creates a tenant and its first administrator. Sign in afterwards to "
                    + "get a token.")
    @ApiResponse(responseCode = "409", description = "The handle is taken", content = @Content)
    @ResponseStatus(HttpStatus.CREATED)
    RegisteredResponse register(@Valid @RequestBody RegisterRequest request) {
        AuthenticationService.Registered registered = service.register(
                request.slug(), request.organisationName(), request.email(), request.password());
        return new RegisteredResponse(
                registered.tenantId(), registered.slug(), registered.administratorId());
    }

    @PostMapping("/auth/login")
    @SecurityRequirements // The endpoint that produces the token cannot require one.
    @Operation(summary = "Sign in",
            description = "Returns an access token carrying the tenant and role. Every rejection "
                    + "answers the same way, so the endpoint cannot be used to discover which "
                    + "accounts exist.")
    @ApiResponse(responseCode = "401", description = "Invalid credentials", content = @Content)
    TokenResponse login(@Valid @RequestBody LoginRequest request) {
        AccessToken token = service.login(request.tenantSlug(), request.email(), request.password());
        return new TokenResponse(token.value(), "Bearer", token.expiresAt());
    }

    @PostMapping("/users")
    @Operation(summary = "Add a user to your organisation",
            description = "Requires the ADMIN role.")
    @ApiResponse(responseCode = "409", description = "That email is already in use", content = @Content)
    ResponseEntity<UserResponse> addUser(@Valid @RequestBody CreateUserRequest request) {
        AppUser created = service.addUser(request.email(), request.password(), request.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(created));
    }
}
