package org.jabref.gui.linkedfile;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.jabref.gui.DialogService;
import org.jabref.gui.actions.SimpleCommand;
import org.jabref.gui.preferences.GuiPreferences;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.pdf.PdfOutlineSplitter;
import org.jabref.logic.undo.UndoManager;
import org.jabref.logic.util.BackgroundTask;
import org.jabref.logic.util.TaskExecutor;
import org.jabref.logic.util.io.FileUtil;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.LinkedFile;
import org.jabref.model.undo.UndoableInsertEntries;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Splits the selected PDF on its outline and adds one library entry per paper. The original file stays where it is.
public class SplitPdfAction extends SimpleCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(SplitPdfAction.class);

    private final LinkedFile linkedFile;
    private final Optional<BibEntry> entry;
    private final BibDatabaseContext databaseContext;
    private final DialogService dialogService;
    private final GuiPreferences preferences;
    private final TaskExecutor taskExecutor;
    private final UndoManager undoManager;

    public SplitPdfAction(LinkedFile linkedFile,
                          Optional<BibEntry> entry,
                          BibDatabaseContext databaseContext,
                          DialogService dialogService,
                          GuiPreferences preferences,
                          TaskExecutor taskExecutor,
                          UndoManager undoManager) {
        this.linkedFile = linkedFile;
        this.entry = entry;
        this.databaseContext = databaseContext;
        this.dialogService = dialogService;
        this.preferences = preferences;
        this.taskExecutor = taskExecutor;
        this.undoManager = undoManager;
    }

    @Override
    public void execute() {
        if (entry.isEmpty()) {
            dialogService.notify(Localization.lang("This operation requires exactly one item to be selected."));
            return;
        }

        Optional<Path> pdfPath = linkedFile.findIn(databaseContext, preferences.getFilePreferences());
        if (pdfPath.isEmpty() || !FileUtil.isPDFFile(pdfPath.get())) {
            dialogService.notify(Localization.lang("File not found"));
            return;
        }

        Path source = pdfPath.get();
        Path directory = source.getParent();
        if (directory == null) {
            dialogService.notify(Localization.lang("File not found"));
            return;
        }

        BibEntry parent = entry.get();
        BackgroundTask<List<BibEntry>> task = BackgroundTask.wrap(() -> PdfOutlineSplitter.split(source, parent, directory));
        task.titleProperty().set(Localization.lang("Split PDF"));
        task.showToUser(true);
        task.onSuccess(papers -> showSplitResult(papers));
        task.onFailure(exception -> {
            LOGGER.error("Could not split PDF {}", source, exception);
            dialogService.showErrorDialogAndWait(Localization.lang("Could not split the PDF."), exception);
        });
        taskExecutor.execute(task);
    }

    private void showSplitResult(List<BibEntry> papers) {
        if (papers.isEmpty()) {
            dialogService.showInformationDialogAndWait(
                    Localization.lang("Split PDF"),
                    Localization.lang("This PDF has no bookmarked papers to split."));
            return;
        }
        undoManager.applyEdit(new UndoableInsertEntries(databaseContext.getDatabase(), papers));
        dialogService.notify(Localization.lang("Created %0 entries from the PDF.", String.valueOf(papers.size())));
    }
}
