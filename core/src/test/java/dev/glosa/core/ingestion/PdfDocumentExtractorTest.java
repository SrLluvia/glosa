package dev.glosa.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.glosa.core.support.TestPdfs;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PDF extraction")
class PdfDocumentExtractorTest {

    private final PdfDocumentExtractor extractor = new PdfDocumentExtractor();

    @Test
    @DisplayName("tags every passage with the page it came from")
    void recordsThePageOfEachPassage() throws IOException {
        byte[] pdf = TestPdfs.withPages(
                "Holiday allowance. Everyone receives twenty-three days of paid leave each year.",
                "Expense claims. Submit expenses before the fifth of the following month please.");

        ExtractionResult result = extractor.extract(pdf);

        assertThat(result.pageCount()).isEqualTo(2);
        // Page numbers are what make a citation checkable, so losing them during
        // extraction would make the answer unverifiable.
        assertThat(result.chunks()).allSatisfy(chunk -> assertThat(chunk.pageNumber()).isNotNull());
        assertThat(result.chunks().getFirst().pageNumber()).isEqualTo(1);
        assertThat(result.chunks().getFirst().content()).contains("twenty-three days");
        assertThat(result.chunks().getLast().pageNumber()).isEqualTo(2);
        assertThat(result.chunks().getLast().content()).contains("fifth of the following month");
    }

    @Test
    @DisplayName("never attributes text to a page it did not come from")
    void keepsPassagesWithinTheirPage() throws IOException {
        byte[] pdf = TestPdfs.withPages(
                "Page one talks at some length about the holiday allowance and nothing else.",
                "Short.");

        ExtractionResult result = extractor.extract(pdf);

        // The second page is a scrap. It must not be folded into the passage from
        // page one, or the citation would point at the wrong page.
        assertThat(result.chunks())
                .filteredOn(chunk -> chunk.content().contains("holiday allowance"))
                .allSatisfy(chunk -> assertThat(chunk.pageNumber()).isEqualTo(1));
        assertThat(result.chunks())
                .filteredOn(chunk -> chunk.content().contains("Short"))
                .allSatisfy(chunk -> assertThat(chunk.pageNumber()).isEqualTo(2));
    }

    @Test
    @DisplayName("says plainly when a PDF has no text layer")
    void refusesAScannedPdf() throws IOException {
        // A page with no text at all is what a scan looks like to a parser.
        byte[] imageOnly;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            imageOnly = out.toByteArray();
        }

        // Storing it would produce a document that silently answers nothing.
        assertThatThrownBy(() -> extractor.extract(imageOnly))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("OCR");
    }

    @Test
    @DisplayName("refuses bytes that are not a PDF")
    void refusesSomethingThatIsNotAPdf() {
        assertThatThrownBy(() -> extractor.extract("not a pdf at all".getBytes()))
                .isInstanceOf(ExtractionException.class);
    }

    @Test
    @DisplayName("reads PDF, and leaves other formats to someone else")
    void supportsOnlyPdf() {
        assertThat(extractor.supports("application/pdf")).isTrue();
        assertThat(extractor.supports("text/markdown")).isFalse();
    }

}
