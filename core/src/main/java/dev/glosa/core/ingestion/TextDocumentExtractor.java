package dev.glosa.core.ingestion;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Reads plain text and Markdown.
 *
 * <p>Splits on structure rather than on a fixed character count. A passage that
 * begins mid-sentence, or that straddles two unrelated sections, retrieves badly
 * and cites worse: the heading a chunk is filed under is what a reader needs in
 * order to judge whether the answer really came from where it claims.
 *
 * <p>Oversized sections are divided on paragraph boundaries, with the heading
 * carried onto each piece so provenance survives the split.
 */
@Component
public class TextDocumentExtractor implements DocumentExtractor {

    private static final Set<String> SUPPORTED = Set.of("text/plain", "text/markdown");

    /**
     * Target size of a chunk. Small enough that several fit in a prompt, large
     * enough to carry a whole thought.
     */
    private static final int MAX_TOKENS_PER_CHUNK = 350;

    /** Below this a passage is folded into the next one rather than stored alone. */
    private static final int MIN_TOKENS_PER_CHUNK = 20;

    @Override
    public boolean supports(String contentType) {
        return SUPPORTED.contains(contentType);
    }

    @Override
    public ExtractionResult extract(byte[] content) {
        String text = decodeUtf8(content);

        List<ExtractedChunk> chunks = new ArrayList<>();
        String heading = null;
        StringBuilder buffer = new StringBuilder();

        for (String block : text.split("\\R{2,}")) {
            String trimmed = block.strip();
            if (trimmed.isEmpty()) {
                continue;
            }

            String blockHeading = headingOf(trimmed);
            if (blockHeading != null) {
                // A new heading closes the passage under the previous one.
                flush(chunks, heading, buffer);
                heading = blockHeading;
                continue;
            }

            if (estimateTokens(buffer.length() + trimmed.length()) > MAX_TOKENS_PER_CHUNK) {
                flush(chunks, heading, buffer);
            }
            if (!buffer.isEmpty()) {
                buffer.append("\n\n");
            }
            buffer.append(trimmed);
        }
        flush(chunks, heading, buffer);

        if (chunks.isEmpty()) {
            throw new ExtractionException("The document contains no readable text");
        }

        // Plain text and Markdown have no pages, so there is no page number to
        // cite. Null says "unknown" rather than inventing a page one.
        return new ExtractionResult(null, List.copyOf(chunks));
    }

    private static void flush(List<ExtractedChunk> chunks, String heading, StringBuilder buffer) {
        String content = buffer.toString().strip();
        buffer.setLength(0);
        if (content.isEmpty()) {
            return;
        }

        int tokens = estimateTokens(content.length());
        if (tokens < MIN_TOKENS_PER_CHUNK && !chunks.isEmpty()) {
            // Merge a scrap into the previous passage instead of storing a chunk
            // too small to answer anything on its own.
            ExtractedChunk previous = chunks.removeLast();
            String merged = previous.content() + "\n\n" + content;
            chunks.add(new ExtractedChunk(
                    previous.pageNumber(), previous.heading(), merged, estimateTokens(merged.length())));
            return;
        }

        chunks.add(new ExtractedChunk(null, heading, content, Math.max(tokens, 1)));
    }

    /** Markdown ATX heading, or null when the block is body text. */
    private static String headingOf(String block) {
        if (!block.startsWith("#")) {
            return null;
        }
        String firstLine = block.lines().findFirst().orElse("");
        String stripped = firstLine.replaceFirst("^#{1,6}\\s*", "").strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * Rough token count from character length.
     *
     * <p>Deliberately an estimate. The real count depends on the tokeniser of
     * whichever model is in use, and this only needs to be good enough to keep
     * chunks in a sensible size band. Four characters per token is the usual
     * approximation for English prose.
     */
    private static int estimateTokens(int characters) {
        return Math.max(1, characters / 4);
    }

    private static String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            // Refusing beats storing replacement characters: a chunk full of
            // U+FFFD would be indexed, retrieved and cited as if it meant
            // something.
            throw new ExtractionException("The document is not valid UTF-8 text", e);
        }
    }
}
