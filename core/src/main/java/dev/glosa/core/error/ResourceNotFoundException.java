package dev.glosa.core.error;

/** A resource the caller may legitimately ask for, which does not exist. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
