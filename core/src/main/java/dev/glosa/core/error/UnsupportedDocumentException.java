package dev.glosa.core.error;

/** An upload the ingestion pipeline cannot read, refused before it is stored. */
public class UnsupportedDocumentException extends RuntimeException {

    public UnsupportedDocumentException(String message) {
        super(message);
    }
}
