package dev.glosa.core.error;

import dev.glosa.core.auth.InvalidCredentialsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns domain failures into RFC 7807 problem responses, in one place.
 *
 * <p>There is deliberately no handler for {@link Exception}. A catch-all would
 * also swallow {@code AccessDeniedException}, which Spring Security translates
 * into a 403 further up the chain, and would risk putting the text of an
 * unexpected internal error into a response body.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    ProblemDetail handleDuplicate(DuplicateResourceException e) {
        return problem(HttpStatus.CONFLICT, "Already exists", e.getMessage());
    }

    @ExceptionHandler(UnsupportedDocumentException.class)
    ProblemDetail handleUnsupportedDocument(UnsupportedDocumentException e) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported document", e.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail handleInvalidCredentials(InvalidCredentialsException e) {
        // Deliberately the same answer whatever was wrong with the attempt.
        return problem(HttpStatus.UNAUTHORIZED, "Invalid credentials",
                "The organisation, email or password did not match");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        // Messages here are written for the caller and name only what the caller
        // already supplied, so they carry nothing about other tenants or the
        // internals of the service.
        problem.setDetail(detail);
        return problem;
    }
}
