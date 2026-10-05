package dev.glosa.core.auth;

/**
 * Sign-in refused.
 *
 * <p>Carries no detail on purpose. Which part was wrong is exactly what an
 * attacker probing for valid accounts wants to learn.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}
