package dev.glosa.core.ingestion;

/**
 * One passage pulled out of a document, before it is persisted.
 *
 * @param pageNumber page it came from, or null for formats without pages
 * @param heading    nearest enclosing heading, or null
 * @param content    the text itself
 * @param tokenCount approximate size, used to keep prompts within budget
 */
public record ExtractedChunk(Integer pageNumber, String heading, String content, int tokenCount) {
}
