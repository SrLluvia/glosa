package dev.glosa.core.error;

/** A resource that cannot be created because an equivalent one already exists. */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
