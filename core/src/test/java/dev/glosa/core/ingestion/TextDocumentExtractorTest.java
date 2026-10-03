package dev.glosa.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Text extraction")
class TextDocumentExtractorTest {

    private final TextDocumentExtractor extractor = new TextDocumentExtractor();

    @Test
    @DisplayName("files each passage under the heading it sits beneath")
    void attachesTheEnclosingHeading() {
        ExtractionResult result = extract("""
                # Holidays

                Everyone gets twenty-three days of paid leave each year, and they
                have to be taken before the end of December or they are lost.

                # Expenses

                Claims go in before the fifth of the following month. Anything
                later lands in the next cycle instead.
                """);

        assertThat(result.chunks()).hasSize(2);
        assertThat(result.chunks().get(0).heading()).isEqualTo("Holidays");
        assertThat(result.chunks().get(0).content()).contains("twenty-three days");
        assertThat(result.chunks().get(1).heading()).isEqualTo("Expenses");
        // The heading is what lets an answer say where it read something, so it
        // must not bleed from one section into the next.
        assertThat(result.chunks().get(1).content()).doesNotContain("twenty-three days");
    }

    @Test
    @DisplayName("merges a scrap into the passage before it")
    void mergesTinyPassages() {
        ExtractionResult result = extract("""
                # Policy

                A paragraph with enough words in it to stand on its own as a
                retrievable passage, carrying a complete thought that somebody
                could reasonably ask a question about and get a useful answer.

                Yes.
                """);

        // A chunk holding only "Yes." would be retrievable and meaningless.
        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().getFirst().content()).endsWith("Yes.");
    }

    @Test
    @DisplayName("numbers passages so reading order survives")
    void keepsReadingOrder() {
        ExtractionResult result = extract("""
                # One

                First section body text that is long enough to stand alone as its
                own passage without being folded into anything else at all.

                # Two

                Second section body text that is also long enough to stand alone
                as its own passage without being merged into its neighbour.
                """);

        assertThat(result.chunks().get(0).content()).contains("First section");
        assertThat(result.chunks().get(1).content()).contains("Second section");
    }

    @Test
    @DisplayName("reports no page number for a format that has no pages")
    void reportsNoPageNumberForPlainText() {
        ExtractionResult result = extract("""
                Some text with no headings at all, just a paragraph long enough to
                survive as a passage of its own without being merged away.
                """);

        // Null means "unknown", which is honest. Inventing page one would put a
        // number into a citation that nobody could check.
        assertThat(result.pageCount()).isNull();
        assertThat(result.chunks().getFirst().pageNumber()).isNull();
    }

    @Test
    @DisplayName("refuses bytes that are not valid UTF-8")
    void refusesInvalidUtf8() {
        byte[] notUtf8 = {(byte) 0xFF, (byte) 0xFE, (byte) 0xFD, (byte) 0xFC};

        // Decoding leniently would store replacement characters, and those would
        // be indexed, retrieved and cited as though they meant something.
        assertThatThrownBy(() -> extractor.extract(notUtf8))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("not valid UTF-8");
    }

    @Test
    @DisplayName("refuses a document with nothing readable in it")
    void refusesAnEmptyDocument() {
        assertThatThrownBy(() -> extract("   \n\n   \n"))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("no readable text");
    }

    @Test
    @DisplayName("reads Markdown and plain text, and nothing else")
    void supportsOnlyTextualTypes() {
        assertThat(extractor.supports("text/markdown")).isTrue();
        assertThat(extractor.supports("text/plain")).isTrue();
        assertThat(extractor.supports("application/pdf")).isFalse();
    }

    private ExtractionResult extract(String text) {
        return extractor.extract(text.getBytes(StandardCharsets.UTF_8));
    }
}
