package com.buraktok.reportforge;

import com.buraktok.reportforge.export.HtmlReportExporter;
import com.buraktok.reportforge.model.ApplicationEntry;
import com.buraktok.reportforge.model.ExecutionReportSnapshot;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ProjectWorkspace;
import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.ReportStatus;
import com.buraktok.reportforge.ui.ExecutionRunWorkspaceNode;
import com.buraktok.reportforge.ui.IconSupport;
import com.buraktok.reportforge.ui.UiSupport;
import com.buraktok.reportforge.ui.WorkspaceHost;
import com.buraktok.reportforge.ui.WorkspaceNode;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * UI Coordinator responsible for managing the top toolbar and application export functionality.
 * Coordinates toolbar state changes and delegates actions back to the main application.
 */
final class ToolbarAndExportCoordinator {
    /**
     * Callback interface to interact with primary application workflows.
     */
    interface Support {
        /** Initiates the new project workflow. */
        void handleNewProject();

        /** Initiates the open project workflow. */
        void handleOpenProject();

        /** Flushes any pending autosaves to disk. */
        void flushAutosave();

        /** Closes the active project workspace. */
        void closeCurrentProject();

        /** Initiates adding a new environment. */
        void handleAddEnvironment();

        /** Selects the global applications structural node. */
        void selectApplicationsNode();

        /** Initiates adding an application entry. */
        void addProjectApplication();

        /** Creates a new report record for the active environment. */
        void createReportForCurrentEnvironment();

        /** Deletes the active environment entity. */
        void deleteCurrentEnvironment();

        /**
         * Requests a status change for a specific report.
         *
         * @param report          the report record
         * @param requestedStatus the new target status
         */
        void handleReportStatusChange(ReportRecord report, ReportStatus requestedStatus);

        /**
         * Builds a button capable of toggling the UI color theme.
         *
         * @param compact whether the button should use a compact layout
         * @return the constructed theme toggle button
         */
        Button createThemeToggleButton(boolean compact);

        /**
         * Pushes an informational status message to the UI.
         *
         * @param message the message to display
         */
        void setInfoStatus(String message);

        /**
         * Displays a formatted error dialog to the user.
         *
         * @param title     the title of the error dialog
         * @param exception the root exception to detail
         */
        void showHandledUiError(String title, Exception exception);
    }

    private final WorkspaceHost workspaceHost;
    private final Support support;
    private final ToolBar leftToolBar;
    private final HBox centerToolbarBox;
    private final ToolBar rightToolBar;

    /**
     * Initializes the toolbar coordinator.
     *
     * @param workspaceHost the primary UI workspace host interface
     * @param support       the callback support mechanism
     */
    ToolbarAndExportCoordinator(WorkspaceHost workspaceHost, Support support) {
        this.workspaceHost = workspaceHost;
        this.support = support;
        this.leftToolBar = new ToolBar();
        this.leftToolBar.getStyleClass().add("chrome-toolbar");
        this.centerToolbarBox = new HBox(8);
        this.centerToolbarBox.setAlignment(Pos.CENTER);
        HBox.setHgrow(centerToolbarBox, Priority.ALWAYS);
        this.centerToolbarBox.getStyleClass().add("toolbar-center-box");
        this.rightToolBar = new ToolBar();
        this.rightToolBar.getStyleClass().add("chrome-toolbar");
    }

    /**
     * Constructs and styles the top horizontal toolbar container.
     *
     * @return the bordered layout pane housing the toolbars
     */
    Node buildToolbarContainer() {
        BorderPane toolbarPane = new BorderPane();
        toolbarPane.setPadding(new Insets(10, 16, 10, 16));
        toolbarPane.getStyleClass().add("top-chrome");
        toolbarPane.setLeft(leftToolBar);
        toolbarPane.setCenter(centerToolbarBox);
        toolbarPane.setRight(rightToolBar);
        return toolbarPane;
    }

    /**
     * Synchronizes the state, visibility, and context of toolbars against the active selection node.
     *
     * @param selection the currently focused node in the workspace tree
     */
    void syncToolbars(WorkspaceNode selection) {
        ProjectWorkspace currentWorkspace = workspaceHost.getCurrentWorkspace();
        leftToolBar.getItems().setAll(
                createToolbarButton("New", "fas-plus", event -> support.handleNewProject()),
                createToolbarButton("Open", "far-folder-open", event -> support.handleOpenProject()),
                createToolbarButton("Save", "fas-save", event -> support.flushAutosave(), currentWorkspace != null),
                createToolbarButton("Close Project", "fas-times", event -> support.closeCurrentProject(), currentWorkspace != null)
        );

        centerToolbarBox.getChildren().clear();
        if (currentWorkspace == null) {
            rightToolBar.getItems().setAll(createToolbarButton(
                    "Export",
                    "fas-file-export",
                    event -> workspaceHost.showInformation("Export", "Open a report to export it."),
                    false
            ));
            return;
        }

        updateCenterToolbar(selection);
        updateRightToolbar(selection);
    }

    /**
     * Resolves an underlying report record entity from a given tree node selection.
     *
     * @param selection the selected node
     * @return the extracted report record if applicable, or null
     */
    static ReportRecord resolveReportSelection(WorkspaceNode selection) {
        if (selection == null) {
            return null;
        }
        return switch (selection.type()) {
            case REPORT -> selection.payload() instanceof ReportRecord report ? report : null;
            case EXECUTION_RUN -> selection.payload() instanceof ExecutionRunWorkspaceNode executionRunNode
                    ? executionRunNode.report()
                    : null;
            default -> null;
        };
    }

    /**
     * Ensures an export filesystem path inherently possesses an HTML file extension.
     *
     * @param exportPath the raw target path
     * @return the corrected path containing a suitable extension
     */
    static Path ensureHtmlExtension(Path exportPath) {
        if (exportPath == null) {
            return null;
        }
        String fileName = exportPath.getFileName() == null ? "" : exportPath.getFileName().toString();
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".html")) {
            return exportPath;
        }
        return exportPath.resolveSibling(fileName + ".html");
    }

    /**
     * Strips and replaces invalid filesystem characters from a suggested export title string.
     *
     * @param reportTitle the raw text report topic
     * @return a strictly safe string viable for naming files
     */
    static String sanitizeExportFileName(String reportTitle) {
        String baseName = reportTitle == null || reportTitle.isBlank() ? "report-export" : reportTitle.trim();
        String sanitized = baseName.replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
        return sanitized.isBlank() ? "report-export" : sanitized;
    }

    /**
     * Rebuilds the dynamic context-sensitive controls located within the center toolbar boundary.
     *
     * @param selection the currently focal element
     */
    private void updateCenterToolbar(WorkspaceNode selection) {
        if (selection == null) {
            return;
        }

        switch (selection.type()) {
            case PROJECT -> {
                centerToolbarBox.getChildren().add(createToolbarButton("Add Environment", "fas-plus", event -> support.handleAddEnvironment()));
                centerToolbarBox.getChildren().add(createToolbarButton("Applications", "fas-list", event -> support.selectApplicationsNode()));
            }
            case APPLICATIONS -> centerToolbarBox.getChildren().add(createToolbarButton("Add Application", "fas-plus", event -> support.addProjectApplication()));
            case ENVIRONMENT -> {
                centerToolbarBox.getChildren().add(createToolbarButton("New Report", "fas-file-alt", event -> support.createReportForCurrentEnvironment()));
                centerToolbarBox.getChildren().add(createToolbarButton("Delete Environment", "fas-trash", event -> support.deleteCurrentEnvironment()));
            }
            case REPORT, EXECUTION_RUN -> {
                ReportRecord reportRecord = resolveReportSelection(selection);
                if (reportRecord == null) {
                    return;
                }
                ComboBox<ReportStatus> statusComboBox = new ComboBox<>(FXCollections.observableArrayList(ReportStatus.values()));
                statusComboBox.setConverter(UiSupport.reportStatusConverter());
                statusComboBox.setValue(reportRecord.getStatus());
                statusComboBox.setOnAction(event -> support.handleReportStatusChange(reportRecord, statusComboBox.getValue()));
                centerToolbarBox.getChildren().add(statusComboBox);
            }
        }
    }

    /**
     * Regenerates the secondary state controls found on the trailing right side of the toolbar.
     *
     * @param selection the currently focal tree element
     */
    private void updateRightToolbar(WorkspaceNode selection) {
        ReportRecord selectedReport = resolveReportSelection(selection);
        boolean reportSelected = selectedReport != null;
        Button themeButton = support.createThemeToggleButton(true);
        Button exportButton = createToolbarButton(
                "Export",
                "fas-file-export",
                event -> showExportMenu((Button) event.getSource(), selectedReport),
                reportSelected
        );
        exportButton.getStyleClass().add("accent-button");
        rightToolBar.getItems().setAll(themeButton, exportButton);
    }

    /**
     * Constructs an always-enabled standard action button for the toolbar.
     *
     * @param text        the display string
     * @param iconLiteral the icon literal mapping
     * @param action      the click handler listener
     * @return the constructed button
     */
    private Button createToolbarButton(String text, String iconLiteral, java.util.function.Consumer<javafx.event.ActionEvent> action) {
        return createToolbarButton(text, iconLiteral, action, true);
    }

    /**
     * Constructs a standard action button for the toolbar with an explicit active state.
     *
     * @param text        the display string
     * @param iconLiteral the graphical icon identifier
     * @param action      the action handler map
     * @param enabled     whether the element is interactive
     * @return the fully structured button item
     */
    private Button createToolbarButton(
            String text,
            String iconLiteral,
            java.util.function.Consumer<javafx.event.ActionEvent> action,
            boolean enabled
    ) {
        Button button = new Button(text);
        button.setDisable(!enabled);
        button.setOnAction(action::accept);
        button.getStyleClass().addAll("app-button", "toolbar-button");
        button.setGraphic(IconSupport.createButtonIcon(iconLiteral));
        button.setContentDisplay(ContentDisplay.LEFT);
        button.setGraphicTextGap(10);
        return button;
    }

    /**
     * Presents the dropdown export options menu relative to a source trigger button.
     *
     * @param exportButton   the anchor mapping element
     * @param selectedReport the active data limit contextual record
     */
    private void showExportMenu(Button exportButton, ReportRecord selectedReport) {
        if (selectedReport == null || exportButton == null || exportButton.isDisabled()) {
            return;
        }

        MenuItem htmlItem = UiSupport.createMenuItem("HTML Report", "fas-file-code", () -> exportReportAsHtml(selectedReport));
        MenuItem pdfItem = UiSupport.createMenuItem("PDF Report (Coming Soon)", "fas-file-pdf", () -> { });
        pdfItem.setDisable(true);
        MenuItem excelItem = UiSupport.createMenuItem("Excel Export (Coming Soon)", "fas-file-excel", () -> { });
        excelItem.setDisable(true);

        ContextMenu exportMenu = UiSupport.themedContextMenu(
                workspaceHost,
                htmlItem,
                new SeparatorMenuItem(),
                pdfItem,
                excelItem
        );
        exportMenu.show(exportButton, javafx.geometry.Side.BOTTOM, 0, 6);
    }

    /**
     * Orchestrates the workflow to generate and save an HTML report file to disk.
     *
     * @param report the record to export
     */
    private void exportReportAsHtml(ReportRecord report) {
        ProjectWorkspace currentWorkspace = workspaceHost.getCurrentWorkspace();
        if (report == null || currentWorkspace == null) {
            return;
        }
        try {
            Map<String, String> fields = workspaceHost.getProjectService().loadReportFields(report.getId());
            List<ApplicationEntry> applications = workspaceHost.getProjectService().loadReportApplications(report.getId());
            EnvironmentRecord environment = workspaceHost.getProjectService().loadReportEnvironmentSnapshot(report.getId());
            ExecutionReportSnapshot executionSnapshot = workspaceHost.getProjectService().loadExecutionReportSnapshot(report.getId());

            FileChooser chooser = new FileChooser();
            chooser.setTitle("Export HTML Report");
            chooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("HTML Files", "*.html"));
            chooser.setInitialFileName(sanitizeExportFileName(report.getTitle()) + ".html");

            java.io.File selectedFile = chooser.showSaveDialog(workspaceHost.getPrimaryStage());
            if (selectedFile == null) {
                return;
            }

            Path exportPath = ensureHtmlExtension(selectedFile.toPath());
            HtmlReportExporter.writeReport(
                    exportPath,
                    currentWorkspace.getProject(),
                    report,
                    fields,
                    applications,
                    environment,
                    executionSnapshot,
                    workspaceHost.getProjectService()::resolveProjectPath
            );
            support.setInfoStatus("HTML report exported.");
            if (UiSupport.showExportComplete(workspaceHost, exportPath)) {
                openExportedHtml(exportPath);
            }
        } catch (IOException | java.sql.SQLException | IllegalArgumentException | IllegalStateException exception) {
            support.showHandledUiError("Unable to export HTML report", exception);
        }
    }

    /**
     * Requests the operating system to open the exported HTML file in the default browser.
     *
     * @param exportPath the path to the exported report
     */
    private void openExportedHtml(Path exportPath) {
        if (exportPath == null) {
            return;
        }
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IllegalStateException("Opening files is not supported on this system.");
            }
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(exportPath.toUri());
                return;
            }
            if (desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(exportPath.toFile());
                return;
            }
            throw new IllegalStateException("Opening files is not supported on this system.");
        } catch (IOException | IllegalStateException | UnsupportedOperationException | SecurityException exception) {
            support.showHandledUiError("Unable to open exported HTML", exception);
        }
    }
}
