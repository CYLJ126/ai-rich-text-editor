package com.arte.core.utils;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PdfUtilTest {

    @TempDir
    Path temporaryFolder;

    @Test
    public void parsePageSelectionSupportsRangesAndOpenRanges() {
        assertEquals(List.of(1, 3, 4, 5, 6, 7, 20),
                PdfUtil.parsePageSelection("1,3-7,20", 20));
        assertEquals(List.of(1, 2, 3), PdfUtil.parsePageSelection("-3", 8));
        assertEquals(List.of(6, 7, 8), PdfUtil.parsePageSelection("6-", 8));
    }

    @Test
    public void parsePageSelectionRejectsOutOfBoundsPage() {
        assertThrows(IllegalArgumentException.class,
                () -> PdfUtil.parsePageSelection("1,9", 8));
    }

    @Test
    public void extractAndMergeDocuments() throws Exception {
        Path source = createPdf("source.pdf", 3);
        Path extracted = temporaryFolder.resolve("extracted.pdf");
        PdfUtil.extractPages(source, extracted, List.of(3, 1));
        assertEquals(2, pageCount(extracted));

        Path other = createPdf("other.pdf", 2);
        Path merged = temporaryFolder.resolve("merged.pdf");
        PdfUtil.mergeDocuments(List.of(extracted, other), merged);
        assertEquals(4, pageCount(merged));
    }

    @Test
    public void convertSelectedPagesToImages() throws Exception {
        Path source = createPdf("images.pdf", 3);
        Path outputDir = Files.createDirectory(temporaryFolder.resolve("images"));
        PdfUtil.ImageOptions options = new PdfUtil.ImageOptions(
                PdfUtil.ImageFormat.PNG, 0.9F, 72F, false, PdfUtil.ColorMode.COLOR);

        List<Path> outputs = PdfUtil.convertPagesToImages(source, outputDir, "sample", List.of(2), options);

        assertEquals(1, outputs.size());
        assertTrue(Files.size(outputs.getFirst()) > 0);
        assertEquals("sample-2.png", outputs.getFirst().getFileName().toString());
    }

    private Path createPdf(String name, int pages) throws Exception {
        Path path = temporaryFolder.resolve(name);
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            document.save(path.toFile());
        }
        return path;
    }

    private int pageCount(Path path) throws Exception {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            return document.getNumberOfPages();
        }
    }
}
