package org.jabref.logic.pdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jabref.logic.externalfiles.PdfLocationResolver;
import org.jabref.logic.util.StandardFileType;
import org.jabref.logic.util.io.FileNameUniqueness;
import org.jabref.logic.util.io.FileUtil;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.LinkedFile;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.types.EntryType;
import org.jabref.model.entry.types.StandardEntryType;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Splits a PDF into one file and one entry per paper, using the PDF outline.
///
/// Leaf bookmarks are the papers. A PDF whose outline does not mark at least two papers is left unchanged:
/// the outline is the only place the file itself says where each paper starts.
@NullMarked
public final class PdfOutlineSplitter {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfOutlineSplitter.class);

    private PdfOutlineSplitter() {
    }

    /// Writes one PDF per paper into `targetDirectory` and returns an entry for each.
    /// The file at `source` is not modified.
    ///
    /// @return empty when the outline does not mark at least two papers
    public static List<BibEntry> split(Path source, BibEntry parent, Path targetDirectory) throws IOException {
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            List<Bookmark> bookmarks = new ArrayList<>(leafBookmarks(document));
            bookmarks.sort(Comparator.comparingInt(Bookmark::page));
            List<Bookmark> papersInOrder = distinctPages(bookmarks);
            if (papersInOrder.size() < 2) {
                return List.of();
            }

            int pageCount = document.getNumberOfPages();
            int offset = PdfLocationResolver.pageOffset(parent);
            List<BibEntry> papers = new ArrayList<>();
            for (int index = 0; index < papersInOrder.size(); index++) {
                Bookmark bookmark = papersInOrder.get(index);
                int lastPdfPage = index + 1 < papersInOrder.size() ? papersInOrder.get(index + 1).page() - 1 : pageCount;
                if ((bookmark.page() < 1) || (lastPdfPage < bookmark.page()) || (lastPdfPage > pageCount)) {
                    continue;
                }
                int printedFirst = bookmark.page() - offset;
                int printedLast = lastPdfPage - offset;
                if ((printedFirst < 1) || (printedLast < printedFirst)) {
                    continue;
                }

                String fileName = FileUtil.getValidFileName(bookmark.title() + ".pdf");
                Path target = targetDirectory.resolve(FileNameUniqueness.generateUniqueFileName(targetDirectory, fileName));
                PdfPageCopier.copy(document, bookmark.page(), lastPdfPage, target);
                papers.add(paperEntry(parent, bookmark.title(), printedFirst, printedLast, target));
            }
            return papers;
        }
    }

    private static BibEntry paperEntry(BibEntry parent, String title, int printedFirst, int printedLast, Path file) {
        BibEntry paper = new BibEntry(childType(parent.getType()))
                .withField(StandardField.TITLE, title)
                .withField(StandardField.PAGES, pagesValue(printedFirst, printedLast))
                .withFiles(List.of(new LinkedFile(title, file.toAbsolutePath(), StandardFileType.PDF.getName())));
        parent.getCitationKey().ifPresent(key -> paper.withField(StandardField.CROSSREF, key));
        if (printedFirst != 1) {
            paper.withField(PdfLocationResolver.PAGE_OFFSET, Integer.toString(1 - printedFirst));
        }
        return paper;
    }

    private static String pagesValue(int first, int last) {
        if (first == last) {
            return Integer.toString(first);
        }
        return first + "--" + last;
    }

    private static EntryType childType(EntryType parent) {
        if (StandardEntryType.Book.equals(parent) || StandardEntryType.MvBook.equals(parent)) {
            return StandardEntryType.InBook;
        }
        if (StandardEntryType.Collection.equals(parent) || StandardEntryType.MvCollection.equals(parent)) {
            return StandardEntryType.InCollection;
        }
        if (StandardEntryType.Reference.equals(parent)) {
            return StandardEntryType.InReference;
        }
        return StandardEntryType.InProceedings;
    }

    private static List<Bookmark> leafBookmarks(PDDocument document) {
        PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline == null) {
            return List.of();
        }
        List<Bookmark> bookmarks = new ArrayList<>();
        collectLeaves(outline.getFirstChild(), document, Collections.newSetFromMap(new IdentityHashMap<>()), bookmarks);
        return bookmarks;
    }

    private static List<Bookmark> distinctPages(List<Bookmark> bookmarks) {
        List<Bookmark> distinct = new ArrayList<>();
        for (Bookmark bookmark : bookmarks) {
            if (distinct.isEmpty() || (distinct.getLast().page() != bookmark.page())) {
                distinct.add(bookmark);
            }
        }
        return distinct;
    }

    private static void collectLeaves(PDOutlineItem item, PDDocument document, Set<PDOutlineItem> seen, List<Bookmark> bookmarks) {
        while ((item != null) && seen.add(item)) {
            PDOutlineItem current = item;
            if (current.hasChildren()) {
                collectLeaves(current.getFirstChild(), document, seen, bookmarks);
            } else {
                pageNumber(current, document).ifPresent(page -> bookmarks.add(new Bookmark(title(current), page)));
            }
            item = current.getNextSibling();
        }
    }

    private static Optional<Integer> pageNumber(PDOutlineItem item, PDDocument document) {
        try {
            PDPage page = item.findDestinationPage(document);
            if (page == null) {
                return Optional.empty();
            }
            int index = document.getPages().indexOf(page);
            if (index < 0) {
                return Optional.empty();
            }
            return Optional.of(index + 1);
        } catch (IOException e) {
            LOGGER.debug("Skipping an outline item that has no page", e);
            return Optional.empty();
        }
    }

    private static String title(PDOutlineItem item) {
        String title = item.getTitle();
        if ((title == null) || title.isBlank()) {
            return "Untitled";
        }
        return title.trim();
    }

    private record Bookmark(String title, int page) {
    }
}
