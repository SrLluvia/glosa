package dev.glosa.core.ingestion;

import java.util.List;

/**
 * What reading a document produced.
 *
 * @param pageCount pages in the source, or null for formats without pages
 * @param chunks    passages in reading order
 */
public record ExtractionResult(Integer pageCount, List<ExtractedChunk> chunks) {
}
