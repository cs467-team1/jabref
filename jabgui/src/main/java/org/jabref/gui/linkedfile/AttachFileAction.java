package org.jabref.gui.linkedfile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import org.jabref.gui.DialogService;
import org.jabref.gui.LibraryTab;
import org.jabref.gui.StateManager;
import org.jabref.gui.actions.ActionHelper;
import org.jabref.gui.actions.SimpleCommand;
import org.jabref.gui.fieldeditors.LinkedFilesEditorViewModel;
import org.jabref.gui.frame.ExternalApplicationsPreferences;
import org.jabref.gui.util.FileDialogConfiguration;
import org.jabref.logic.FilePreferences;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.pdf.PdfPaperExtractor;
import org.jabref.logic.util.StandardFileType;
import org.jabref.logic.util.io.FileUtil;
import org.jabref.model.FieldChange;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.LinkedFile;
import org.jabref.model.undo.UndoableFieldChange;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AttachFileAction extends SimpleCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(AttachFileAction.class);

    private final LibraryTab libraryTab;
    private final StateManager stateManager;
    private final DialogService dialogService;
    private final FilePreferences filePreferences;
    private final ExternalApplicationsPreferences externalApplicationsPreferences;

    public AttachFileAction(LibraryTab libraryTab,
                            DialogService dialogService,
                            StateManager stateManager,
                            FilePreferences filePreferences,
                            ExternalApplicationsPreferences externalApplicationsPreferences) {
        this.libraryTab = libraryTab;
        this.stateManager = stateManager;
        this.dialogService = dialogService;
        this.filePreferences = filePreferences;
        this.externalApplicationsPreferences = externalApplicationsPreferences;

        this.executable.bind(ActionHelper.needsEntriesSelected(1, stateManager));
    }

    @Override
    public void execute() {
        if (stateManager.getActiveDatabase().isEmpty()) {
            dialogService.notify(Localization.lang("This operation requires an open library."));
            return;
        }

        if (stateManager.getSelectedEntries().size() != 1) {
            dialogService.notify(Localization.lang("This operation requires exactly one item to be selected."));
            return;
        }

        BibDatabaseContext databaseContext = stateManager.getActiveDatabase().get();

        BibEntry entry = stateManager.getSelectedEntries().getFirst();

        Path initialDirectory;

        if (filePreferences.shouldOpenFileExplorerInFileDirectory()) {
            initialDirectory = databaseContext.getFirstExistingFileDir(filePreferences)
                                              .orElse(filePreferences.getWorkingDirectory());
        } else if (filePreferences.shouldOpenFileExplorerInLastUsedDirectory()) {
            initialDirectory = filePreferences.getLastUsedDirectory();
        } else {
            initialDirectory = filePreferences.getWorkingDirectory();
        }

        FileDialogConfiguration fileDialogConfiguration = new FileDialogConfiguration.Builder()
                .withInitialDirectory(initialDirectory)
                .build();

        dialogService.showFileOpenDialog(fileDialogConfiguration).ifPresent(newFile -> {
            filePreferences.setLastUsedDirectory(newFile.getParent());
            LinkedFile linkedFile = LinkedFilesEditorViewModel.fromFile(
                    newFile,
                    databaseContext.getFileDirectories(filePreferences),
                    externalApplicationsPreferences);

            LinkedFileEditDialog dialog = new LinkedFileEditDialog(linkedFile);

            dialogService.showCustomDialogAndWait(dialog)
                         .ifPresent(editedLinkedFile -> {
                             Optional<FieldChange> fieldChange = entry.addFile(editedLinkedFile);
                             fieldChange.ifPresent(change -> {
                                 libraryTab.getUndoManager().addEdit(new UndoableFieldChange(change));
                             });
                             attachPaperExcerpt(entry, newFile);
                         });
        });
    }

    /// When the chosen PDF holds more than the entry's pages, keep that file and also attach a copy of just those pages.
    private void attachPaperExcerpt(BibEntry entry, Path newFile) {
        if (!FileUtil.isPDFFile(newFile) || (newFile.getParent() == null)) {
            return;
        }
        try {
            PdfPaperExtractor.extractPaper(newFile, entry, newFile.getParent())
                             .ifPresent(extracted -> {
                                 LinkedFile paper = new LinkedFile(
                                         entry.getCitationKey().orElse("Paper"),
                                         extracted.toAbsolutePath(),
                                         StandardFileType.PDF.getName());
                                 entry.addFile(paper).ifPresent(change ->
                                         libraryTab.getUndoManager().addEdit(new UndoableFieldChange(change)));
                             });
        } catch (IOException e) {
            LOGGER.error("Could not copy the paper out of {}", newFile, e);
        }
    }
}
