package org.jabref.logic.pdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.jabref.logic.externalfiles.PdfLocationResolver;
import org.jabref.model.database.BibDatabase;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.types.StandardEntryType;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfOutlineSplitterTest {

    @TempDir
    private Path tempDir;

    @Test
    void createsOneEntryPerLeafBookmarkAndKeepsTheOriginal() throws IOException {
        Path source = proceedingsWithPapers();
        BibEntry proceedings = new BibEntry(StandardEntryType.Proceedings).withCitationKey("Proc2024");

        List<BibEntry> papers = PdfOutlineSplitter.split(source, proceedings, tempDir);

        assertEquals(2, papers.size());
        assertEquals(StandardEntryType.InProceedings, papers.getFirst().getType());
        assertEquals("Alpha", papers.getFirst().getField(StandardField.TITLE).orElseThrow());
        assertEquals("1--2", papers.getFirst().getField(StandardField.PAGES).orElseThrow());
        assertEquals("Proc2024", papers.getFirst().getField(StandardField.CROSSREF).orElseThrow());
        assertEquals("Beta", papers.get(1).getField(StandardField.TITLE).orElseThrow());
        assertEquals("3--5", papers.get(1).getField(StandardField.PAGES).orElseThrow());
        assertEquals("-2", papers.get(1).getField(PdfLocationResolver.PAGE_OFFSET).orElseThrow());
        assertEquals(2, pageCount(Path.of(papers.getFirst().getFiles().getFirst().getLink())));
        assertEquals(3, pageCount(Path.of(papers.get(1).getFiles().getFirst().getLink())));
        assertEquals(5, pageCount(source));

        BibDatabase database = new BibDatabase();
        database.insertEntries(papers);
        assertEquals(1, PdfLocationResolver.resolve(papers.get(1), database).orElseThrow().page().orElseThrow());
    }

    @Test
    void usesTheChildTypeThatMatchesTheParent() throws IOException {
        Path source = proceedingsWithPapers();
        BibEntry book = new BibEntry(StandardEntryType.Book).withCitationKey("Book2024");

        List<BibEntry> chapters = PdfOutlineSplitter.split(source, book, tempDir);

        assertEquals(StandardEntryType.InBook, chapters.getFirst().getType());
    }

    @Test
    void usesNestedBookmarksRatherThanTheirParent() throws IOException {
        Path source = tempDir.resolve("nested.pdf");
        try (PDDocument document = new PDDocument()) {
            addPages(document, 4);
            PDOutlineItem part = bookmark("Part", document.getPage(0));
            part.addLast(bookmark("Alpha", document.getPage(0)));
            part.addLast(bookmark("Beta", document.getPage(2)));
            PDDocumentOutline outline = new PDDocumentOutline();
            outline.addLast(part);
            document.getDocumentCatalog().setDocumentOutline(outline);
            document.save(source.toFile());
        }

        List<BibEntry> papers = PdfOutlineSplitter.split(source, new BibEntry(StandardEntryType.Proceedings), tempDir);

        assertEquals(List.of("Alpha", "Beta"), papers.stream().map(paper -> paper.getField(StandardField.TITLE).orElseThrow()).toList());
    }

    @Test
    void returnsNothingWhenThePdfHasNoOutline() throws IOException {
        Path source = tempDir.resolve("plain.pdf");
        try (PDDocument document = new PDDocument()) {
            addPages(document, 3);
            document.save(source.toFile());
        }

        assertTrue(PdfOutlineSplitter.split(source, new BibEntry(StandardEntryType.Proceedings), tempDir).isEmpty());
        assertEquals(3, pageCount(source));
    }

    private Path proceedingsWithPapers() throws IOException {
        Path source = tempDir.resolve("proceedings.pdf");
        try (PDDocument document = new PDDocument()) {
            addPages(document, 5);
            PDDocumentOutline outline = new PDDocumentOutline();
            outline.addLast(bookmark("Alpha", document.getPage(0)));
            outline.addLast(bookmark("Beta", document.getPage(2)));
            document.getDocumentCatalog().setDocumentOutline(outline);
            document.save(source.toFile());
        }
        return source;
    }

    private static void addPages(PDDocument document, int count) {
        for (int page = 0; page < count; page++) {
            document.addPage(new PDPage());
        }
    }

    private static PDOutlineItem bookmark(String title, PDPage page) {
        PDOutlineItem item = new PDOutlineItem();
        item.setTitle(title);
        item.setDestination(page);
        return item;
    }

    private static int pageCount(Path path) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            return document.getNumberOfPages();
        }
    }
}
