package dev.glosa.core.ingestion;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Reads PDF, one page at a time.
 *
 * <p>Pages are extracted separately rather than as one long string, because the
 * page number is what makes a citation checkable. A reader told the answer came
 * from "the handbook" has to take it on trust; told it came from page 14, they
 * can look.
 *
 * <p>Parsing lives in this service rather than behind the rag service, because it
 * needs no model. The split between the two is about what requires Python, not
 * about what counts as ingestion.
 */
@Component
public class PdfDocumentExtractor implements DocumentExtractor {

    /** Same size band as the text extractor, so retrieval sees comparable passages. */
    private static final int MAX_TOKENS_PER_CHUNK = 350;
    private static final int MIN_TOKENS_PER_CHUNK = 20;

    @Override
    public boolean supports(String contentType) {
        return "application/pdf".equals(contentType);
    }

    @Override
    public ExtractionResult extract(byte[] content) {
        try (PDDocument pdf = Loader.loadPDF(content)) {
            int pages = pdf.getNumberOfPages();
            List<ExtractedChunk> chunks = new ArrayList<>();

            PDFTextStripper stripper = new PDFTextStripper();
            // Reading order as laid out on the page, not as stored in the file.
            stripper.setSortByPosition(true);

            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                appendPage(chunks, page, stripper.getText(pdf));
            }

            if (chunks.isEmpty()) {
                // Almost always a scan: pages of images with no text layer. OCR
                // would be the answer, and is out of scope, so say so plainly
                // rather than storing an empty document that silently answers
                // nothing.
                throw new ExtractionException(
                        "No text could be read from this PDF. Scanned documents need OCR, "
                                + "which is not supported yet.");
            }

            return new ExtractionResult(pages, List.copyOf(chunks));
        } catch (InvalidPasswordException e) {
            throw new ExtractionException("This PDF is password protected", e);
        } catch (IOException e) {
            throw new ExtractionException("This PDF could not be read", e);
        }
    }

    /** Splits one page's text into passages, each tagged with that page. */
    private static void appendPage(List<ExtractedChunk> chunks, int page, String text) {
        StringBuilder buffer = new StringBuilder();

        for (String block : text.split("\\R{2,}")) {
            String trimmed = normaliseWhitespace(block);
            if (trimmed.isEmpty()) {
                continue;
            }
            if (estimateTokens(buffer.length() + trimmed.length()) > MAX_TOKENS_PER_CHUNK) {
                flush(chunks, page, buffer);
            }
            if (!buffer.isEmpty()) {
                buffer.append("\n\n");
            }
            buffer.append(trimmed);
        }
        flush(chunks, page, buffer);
    }

    private static void flush(List<ExtractedChunk> chunks, int page, StringBuilder buffer) {
        String content = buffer.toString().strip();
        buffer.setLength(0);
        if (content.isEmpty()) {
            return;
        }

        int tokens = estimateTokens(content.length());
        if (tokens < MIN_TOKENS_PER_CHUNK && !chunks.isEmpty()
                && chunks.getLast().pageNumber() != null
                && chunks.getLast().pageNumber() == page) {
            // Merge a scrap into the previous passage, but only when that passage
            // is on the same page. Merging across a page boundary would attach
            // text to a page it did not come from, and the citation would lie.
            ExtractedChunk previous = chunks.removeLast();
            String merged = previous.content() + "\n\n" + content;
            chunks.add(new ExtractedChunk(
                    page, previous.heading(), merged, estimateTokens(merged.length())));
            return;
        }

        // PDF carries no reliable heading structure, so there is none to record.
        // The page number is the provenance instead.
        chunks.add(new ExtractedChunk(page, null, content, Math.max(tokens, 1)));
    }

    /**
     * Collapses the line breaks PDF extraction leaves mid-sentence.
     *
     * <p>Text in a PDF is positioned, not flowed, so a paragraph arrives broken at
     * whatever width it was typeset to. Left alone, those breaks end up inside
     * stored passages and make quoted citations look mangled.
     */
    private static String normaliseWhitespace(String block) {
        return block.replaceAll("\\R", " ").replaceAll("\\s{2,}", " ").strip();
    }

    private static int estimateTokens(int characters) {
        return Math.max(1, characters / 4);
    }
}
