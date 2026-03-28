package com.buraktok.reportforge.ui;

import com.buraktok.reportforge.model.ApplicationEntry;
import com.buraktok.reportforge.model.ExecutionMetrics;
import com.buraktok.reportforge.model.ExecutionReportSnapshot;
import com.buraktok.reportforge.model.ExecutionRunRecord;
import com.buraktok.reportforge.model.ExecutionRunSnapshot;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ReportRecord;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public final class WorkspaceContentFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceContentFactory.class);
    private static final DateTimeFormatter SUMMARY_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault());

    private final WorkspaceHost host;
    private final ReportSectionEditors reportSectionEditors;

    public WorkspaceContentFactory(WorkspaceHost host) {
        this.host = host;
        this.reportSectionEditors = new ReportSectionEditors(host, new ReportSectionSupportAdapter());
    }

    public Node buildContent(WorkspaceNode selection) throws SQLException {
        Node content = switch (selection.type()) {
            case PROJECT -> buildProjectDetailsPane();
            case APPLICATIONS -> buildProjectApplicationsPane();
            case ENVIRONMENT -> buildEnvironmentPane((EnvironmentRecord) selection.payload());
            case REPORT -> buildReportEditor((ReportRecord) selection.payload());
            case EXECUTION_RUN -> buildExecutionRunWorkspacePane((ExecutionRunWorkspaceNode) selection.payload());
        };
        UiSupport.installTextInputContextMenus(host, content);
        return content;
    }

    private Node buildProjectDetailsPane() {
        VBox content = new VBox(12);
        content.setPadding(new Insets(20));

        Label heading = new Label("Project Overview");
        heading.getStyleClass().add("panel-heading");

        Label summary = new Label(
                "Applications: " + host.getCurrentWorkspace().getProjectApplications().size()
                        + " | Environments: " + host.getCurrentWorkspace().getEnvironments().size()
        );
        summary.getStyleClass().add("supporting-text");

        TextField projectNameField = new TextField(host.getCurrentWorkspace().getProject().getName());
        TextArea projectDescriptionArea = new TextArea(host.getCurrentWorkspace().getProject().getDescription());
        projectDescriptionArea.setPrefRowCount(6);

        commitOnFocusLost(projectNameField, value -> runWorkspaceOperation(
                "Unable to update project",
                () -> {
                host.getProjectService().updateProject(value, projectDescriptionArea.getText());
                host.reloadWorkspaceAndReselect(
                        WorkspaceNodeType.PROJECT,
                        host.getCurrentWorkspace().getProject().getId()
                );
                host.markDirty("Project updated.");
                },
                () -> projectNameField.setText(host.getCurrentWorkspace().getProject().getName())
        ));
        commitOnFocusLost(projectDescriptionArea, value -> runWorkspaceOperation(
                "Unable to update project",
                () -> {
                host.getProjectService().updateProject(projectNameField.getText(), value);
                host.markDirty("Project updated.");
                },
                () -> projectDescriptionArea.setText(host.getCurrentWorkspace().getProject().getDescription())
        ));

        GridPane form = createFormGrid();
        form.addRow(0, new Label("Project Name"), projectNameField);
        form.addRow(1, new Label("Project Description"), projectDescriptionArea);

        content.getChildren().addAll(heading, summary, form);
        return UiSupport.wrapInScrollPane(content);
    }

    private Node buildProjectApplicationsPane() {
        Label heading = new Label("Applications under Test");
        heading.getStyleClass().add("panel-heading");

        ListView<ApplicationEntry> listView = new ListView<>(
                FXCollections.observableArrayList(host.getCurrentWorkspace().getProjectApplications())
        );
        listView.setPrefHeight(320);
        listView.getStyleClass().add("entity-list");

        Button addButton = createSecondaryButton("Add", "fas-plus");
        Button editButton = createSecondaryButton("Edit", "fas-edit");
        Button removeButton = createSecondaryButton("Remove", "fas-trash");
        Button primaryButton = createSecondaryButton("Make Primary", "fas-star");
        HBox actions = createInlineActions(addButton, editButton, removeButton, primaryButton);

        addButton.setOnAction(event -> host.addProjectApplication());
        editButton.setOnAction(event -> {
            ApplicationEntry selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null) {
                host.showInformation("Applications under Test", "Select an application to edit.");
                return;
            }
            host.editProjectApplication(selected);
        });
        removeButton.setOnAction(event -> {
            ApplicationEntry selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null) {
                host.showInformation("Applications under Test", "Select an application to remove.");
                return;
            }
            runWorkspaceOperation("Unable to remove application", () -> {
                host.getProjectService().deleteProjectApplication(selected.getId());
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.APPLICATIONS, "project-applications");
                host.markDirty("Application removed.");
            });
        });
        primaryButton.setOnAction(event -> {
            ApplicationEntry selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null) {
                host.showInformation("Applications under Test", "Select an application to mark as primary.");
                return;
            }
            runWorkspaceOperation("Unable to update primary application", () -> {
                host.getProjectService().setPrimaryProjectApplication(selected.getId());
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.APPLICATIONS, "project-applications");
                host.markDirty("Primary application updated.");
            });
        });

        VBox content = new VBox(12, heading, listView, actions);
        content.setPadding(new Insets(20));
        return UiSupport.wrapInScrollPane(content);
    }

    private Node buildEnvironmentPane(EnvironmentRecord environment) {
        VBox content = new VBox(12);
        content.setPadding(new Insets(20));

        Label heading = new Label("Environment");
        heading.getStyleClass().add("panel-heading");

        TextField nameField = new TextField(environment.getName());
        TextField typeField = new TextField(environment.getType());
        TextField baseUrlField = new TextField(environment.getBaseUrl());
        TextField osField = new TextField(environment.getOsPlatform());
        TextField browserField = new TextField(environment.getBrowserClient());
        TextField backendField = new TextField(environment.getBackendVersion());
        TextArea notesArea = new TextArea(environment.getNotes());
        notesArea.setPrefRowCount(5);

        Consumer<Void> saveEnvironment = ignored -> runWorkspaceOperation(
                "Unable to update environment",
                () -> {
                host.getProjectService().updateEnvironment(new EnvironmentRecord(
                        environment.getId(),
                        nameField.getText(),
                        typeField.getText(),
                        baseUrlField.getText(),
                        osField.getText(),
                        browserField.getText(),
                        backendField.getText(),
                        notesArea.getText(),
                        environment.getSortOrder()
                ));
                host.markDirty("Environment updated.");
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.ENVIRONMENT, environment.getId());
                }
        );

        commitOnFocusLost(nameField, value -> saveEnvironment.accept(null));
        commitOnFocusLost(typeField, value -> saveEnvironment.accept(null));
        commitOnFocusLost(baseUrlField, value -> saveEnvironment.accept(null));
        commitOnFocusLost(osField, value -> saveEnvironment.accept(null));
        commitOnFocusLost(browserField, value -> saveEnvironment.accept(null));
        commitOnFocusLost(backendField, value -> saveEnvironment.accept(null));
        commitOnFocusLost(notesArea, value -> saveEnvironment.accept(null));

        GridPane gridPane = createFormGrid();
        gridPane.addRow(0, new Label("Environment Name"), nameField);
        gridPane.addRow(1, new Label("Environment Type"), typeField);
        gridPane.addRow(2, new Label("Base URL / Endpoint"), baseUrlField);
        gridPane.addRow(3, new Label("OS / Platform"), osField);
        gridPane.addRow(4, new Label("Browser / Client"), browserField);
        gridPane.addRow(5, new Label("Database / Backend Version"), backendField);
        gridPane.addRow(6, new Label("Environment Notes"), notesArea);

        Label reportsHeading = new Label("Reports in this Environment");
        reportsHeading.getStyleClass().add("subsection-heading");

        ListView<ReportRecord> reportsListView = new ListView<>(
                FXCollections.observableArrayList(host.getCurrentWorkspace().getReportsForEnvironment(environment.getId()))
        );
        reportsListView.setPrefHeight(220);
        reportsListView.getStyleClass().add("entity-list");
        reportsListView.setOnMouseClicked(event -> {
            ReportRecord selected = reportsListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                host.selectNode(WorkspaceNodeType.REPORT, selected.getId());
            }
        });

        Button createReportButton = createSecondaryButton("Create Test Execution Report", "fas-file-alt");
        createReportButton.setOnAction(event -> host.createReportForEnvironment(environment));

        content.getChildren().addAll(heading, gridPane, reportsHeading, reportsListView, createReportButton);
        return UiSupport.wrapInScrollPane(content);
    }

    private Node buildReportEditor(ReportRecord report) {
        return reportSectionEditors.buildReportEditor(report);
    }

    private Node buildExecutionRunEditor(ReportRecord report, ExecutionRunSnapshot runSnapshot) {
        ExecutionRunRecord run = runSnapshot.getRun();

        Label heading = new Label(report.getTitle() + " - " + run.getDisplayLabel());
        heading.getStyleClass().add("panel-heading");

        Label hint = createSupportLabel(
                "Each execution run owns its comments, notes, test steps, a single result, its defect summary, and any attached evidence images."
        );

        TextField executionIdField = new TextField(nullToEmpty(run.getExecutionKey()));
        TextField suiteNameField = new TextField(nullToEmpty(run.getSuiteName()));
        TextField executedByField = new TextField(nullToEmpty(run.getExecutedBy()));
        DatePicker executionDatePicker = new DatePicker(parseOptionalDate(run.getExecutionDate()));
        DatePicker startDatePicker = new DatePicker(parseOptionalDate(run.getStartDate()));
        DatePicker endDatePicker = new DatePicker(parseOptionalDate(run.getEndDate()));
        TextField durationField = new TextField(nullToEmpty(run.getDurationText()));
        TextField dataSourceField = new TextField(nullToEmpty(run.getDataSourceReference()));
        TextArea commentsArea = UiSupport.createArea(nullToEmpty(run.getComments()), 3);
        TextArea notesArea = UiSupport.createArea(nullToEmpty(run.getNotes()), 3);
        LineNumberedTextArea testStepsArea = UiSupport.createLineNumberedArea(nullToEmpty(run.getTestSteps()), 6);
        TextField testCaseIdField = new TextField(nullToEmpty(run.getTestCaseKey()));
        TextField sectionField = new TextField(nullToEmpty(run.getSectionName()));
        TextField subsectionField = new TextField(nullToEmpty(run.getSubsectionName()));
        TextField testCaseNameField = new TextField(nullToEmpty(run.getTestCaseName()));
        TextField priorityField = new TextField(nullToEmpty(run.getPriority()));
        TextField moduleField = new TextField(nullToEmpty(run.getModuleName()));
        ComboBox<String> statusCombo = new ComboBox<>(FXCollections.observableArrayList(
                "PASS",
                "FAIL",
                "BLOCKED",
                "NOT_RUN",
                "DEFERRED",
                "SKIPPED"
        ));
        statusCombo.setValue(normalizeResultStatus(run.getStatus()).isBlank() ? "NOT_RUN" : normalizeResultStatus(run.getStatus()));
        TextField executionTimeField = new TextField(nullToEmpty(run.getExecutionTime()));
        TextArea expectedSummaryArea = UiSupport.createArea(nullToEmpty(run.getExpectedResultSummary()), 2);
        TextArea actualResultArea = UiSupport.createArea(nullToEmpty(run.getActualResult()), 4);
        TextArea blockedReasonArea = UiSupport.createArea(nullToEmpty(run.getBlockedReason()), 2);
        TextArea remarksArea = UiSupport.createArea(nullToEmpty(run.getRemarks()), 3);
        TextField relatedIssueField = new TextField(nullToEmpty(run.getRelatedIssue()));
        TextArea defectSummaryArea = UiSupport.createArea(nullToEmpty(run.getDefectSummary()), 3);

        Runnable saveRun = () -> {
            ExecutionRunRecord updatedRun = run.toBuilder()
                    .reportId(report.getId())
                    .executionKey(executionIdField.getText())
                    .suiteName(suiteNameField.getText())
                    .executedBy(executedByField.getText())
                    .executionDate(dateValue(executionDatePicker))
                    .startDate(dateValue(startDatePicker))
                    .endDate(dateValue(endDatePicker))
                    .durationText(durationField.getText())
                    .dataSourceReference(dataSourceField.getText())
                    .notes(notesArea.getText())
                    .comments(commentsArea.getText())
                    .testSteps(testStepsArea.getText())
                    .testCaseKey(testCaseIdField.getText())
                    .sectionName(sectionField.getText())
                    .subsectionName(subsectionField.getText())
                    .testCaseName(testCaseNameField.getText())
                    .priority(priorityField.getText())
                    .moduleName(moduleField.getText())
                    .status(statusCombo.getValue())
                    .executionTime(executionTimeField.getText())
                    .expectedResultSummary(expectedSummaryArea.getText())
                    .actualResult(actualResultArea.getText())
                    .relatedIssue(relatedIssueField.getText())
                    .remarks(remarksArea.getText())
                    .blockedReason(blockedReasonArea.getText())
                    .defectSummary(defectSummaryArea.getText())
                    .build();
            runWorkspaceOperation("Unable to update execution run", () -> {
                host.getProjectService().updateExecutionRun(updatedRun);
                heading.setText(report.getTitle() + " - " + updatedRun.getDisplayLabel());
                host.updateNode(new WorkspaceNode(
                        WorkspaceNodeType.EXECUTION_RUN,
                        updatedRun.getId(),
                        updatedRun.getDisplayLabel(),
                        new ExecutionRunWorkspaceNode(
                                report,
                                new ExecutionRunSnapshot(updatedRun, runSnapshot.getEvidences(), runSnapshot.getMetrics())
                        )
                ));
                host.markDirty("Execution run updated.");
            });
        };

        commitOnFocusLost(executionIdField, value -> saveRun.run());
        commitOnFocusLost(suiteNameField, value -> saveRun.run());
        commitOnFocusLost(executedByField, value -> saveRun.run());
        commitOnFocusLost(durationField, value -> saveRun.run());
        commitOnFocusLost(dataSourceField, value -> saveRun.run());
        commitOnFocusLost(commentsArea, value -> saveRun.run());
        commitOnFocusLost(notesArea, value -> saveRun.run());
        commitOnFocusLost(testStepsArea.textArea(), value -> saveRun.run());
        commitOnFocusLost(testCaseIdField, value -> saveRun.run());
        commitOnFocusLost(sectionField, value -> saveRun.run());
        commitOnFocusLost(subsectionField, value -> saveRun.run());
        commitOnFocusLost(testCaseNameField, value -> saveRun.run());
        commitOnFocusLost(priorityField, value -> saveRun.run());
        commitOnFocusLost(moduleField, value -> saveRun.run());
        commitOnFocusLost(executionTimeField, value -> saveRun.run());
        commitOnFocusLost(expectedSummaryArea, value -> saveRun.run());
        commitOnFocusLost(actualResultArea, value -> saveRun.run());
        commitOnFocusLost(blockedReasonArea, value -> saveRun.run());
        commitOnFocusLost(remarksArea, value -> saveRun.run());
        commitOnFocusLost(relatedIssueField, value -> saveRun.run());
        commitOnFocusLost(defectSummaryArea, value -> saveRun.run());
        executionDatePicker.valueProperty().addListener((observable, previous, value) -> saveRun.run());
        startDatePicker.valueProperty().addListener((observable, previous, value) -> saveRun.run());
        endDatePicker.valueProperty().addListener((observable, previous, value) -> saveRun.run());
        statusCombo.setOnAction(event -> saveRun.run());

        GridPane runGrid = createFormGrid();
        runGrid.addRow(0, new Label("Execution ID"), executionIdField);
        runGrid.addRow(1, new Label("Suite / Cycle Name"), suiteNameField);
        runGrid.addRow(2, new Label("Executed By"), executedByField);
        runGrid.addRow(3, new Label("Execution Date"), executionDatePicker);
        runGrid.addRow(4, new Label("Execution Start Date"), startDatePicker);
        runGrid.addRow(5, new Label("Execution End Date"), endDatePicker);
        runGrid.addRow(6, new Label("Duration"), durationField);
        runGrid.addRow(7, new Label("Data Source / Reference"), dataSourceField);
        runGrid.addRow(8, new Label("Status"), statusCombo);

        GridPane communicationGrid = createFormGrid();
        communicationGrid.addRow(0, new Label("Comments"), commentsArea);
        communicationGrid.addRow(1, new Label("Notes"), notesArea);

        GridPane resultGrid = createFormGrid();
        resultGrid.addRow(0, new Label("Test Case ID"), testCaseIdField);
        resultGrid.addRow(1, new Label("Section"), sectionField);
        resultGrid.addRow(2, new Label("Subsection"), subsectionField);
        resultGrid.addRow(3, new Label("Test Case Name"), testCaseNameField);
        resultGrid.addRow(4, new Label("Priority"), priorityField);
        resultGrid.addRow(5, new Label("Module / Component"), moduleField);
        resultGrid.addRow(6, new Label("Execution Time"), executionTimeField);
        resultGrid.addRow(7, new Label("Expected Result Summary"), expectedSummaryArea);
        resultGrid.addRow(8, new Label("Test Steps"), testStepsArea);
        resultGrid.addRow(9, new Label("Actual Result"), actualResultArea);
        resultGrid.addRow(10, new Label("Blocked Reason"), blockedReasonArea);
        resultGrid.addRow(11, new Label("Remarks"), remarksArea);

        GridPane defectGrid = createFormGrid();
        defectGrid.addRow(0, new Label("Related Issue"), relatedIssueField);
        defectGrid.addRow(1, new Label("Defect Summary"), defectSummaryArea);

        // Declared early so the button handlers can close over it.
        FlowPane evidenceGallery = new FlowPane();
        evidenceGallery.setHgap(12);
        evidenceGallery.setVgap(12);
        evidenceGallery.getStyleClass().add("evidence-gallery");

        Button pasteEvidenceButton= createSecondaryButton("Paste Clipboard Image", "fas-paste");
        pasteEvidenceButton.setOnAction(event -> {
            javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            if (!clipboard.hasImage()) {
                host.showInformation("Test Evidence", "No image is currently available in the clipboard.");
                return;
            }
            // Clipboard must be read on the FX thread before handing off to background.
            java.awt.image.BufferedImage bufferedImage = javafx.embed.swing.SwingFXUtils.fromFXImage(clipboard.getImage(), null);
            boolean galleryWasEmpty = runSnapshot.getEvidences().isEmpty();
            VBox placeholder = createEvidencePlaceholderCard();
            if (galleryWasEmpty) {
                evidenceGallery.getChildren().clear();
            }
            evidenceGallery.getChildren().add(placeholder);

            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    var encoded = com.buraktok.reportforge.persistence.EvidenceMediaOptimizer.encodeClipboardImage(bufferedImage);
                    String clipboardFileName = switch (encoded.mediaType()) {
                        case "image/webp" -> "clipboard-evidence.webp";
                        case "image/jpeg", "image/jpg" -> "clipboard-evidence.jpg";
                        default -> "clipboard-evidence.png";
                    };
                    host.getProjectService().addExecutionRunEvidence(
                            report.getId(), run.getId(), clipboardFileName, encoded.mediaType(), encoded.bytes());
                    return null;
                }
            };
            task.setOnSucceeded(e -> {
                host.reloadWorkspaceAndReselectPreservingScroll(WorkspaceNodeType.EXECUTION_RUN, run.getId());
                host.markDirty("Evidence image added.");
            });
            task.setOnFailed(e -> {
                evidenceGallery.getChildren().remove(placeholder);
                if (galleryWasEmpty && evidenceGallery.getChildren().isEmpty()) {
                    evidenceGallery.getChildren().add(createPlaceholderLabel("No evidence images have been added to this run yet."));
                }
                Throwable ex = task.getException();
                showOperationError("Unable to add evidence", ex instanceof Exception ce ? ce : new Exception(ex));
            });
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            thread.start();
        });

        Button addEvidenceButton = createSecondaryButton("Add Image From File", "fas-image");
        addEvidenceButton.setOnAction(event -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("Add Evidence Image");
            chooser.getExtensionFilters().setAll(new javafx.stage.FileChooser.ExtensionFilter(
                    "Image Files", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"));
            java.io.File selectedFile = chooser.showOpenDialog(host.getPrimaryStage());
            if (selectedFile == null) {
                return;
            }
            boolean galleryWasEmpty = runSnapshot.getEvidences().isEmpty();
            VBox placeholder = createEvidencePlaceholderCard();
            if (galleryWasEmpty) {
                evidenceGallery.getChildren().clear();
            }
            evidenceGallery.getChildren().add(placeholder);

            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    host.getProjectService().addExecutionRunEvidenceFromFile(report.getId(), run.getId(), selectedFile.toPath());
                    return null;
                }
            };
            task.setOnSucceeded(e -> {
                host.reloadWorkspaceAndReselectPreservingScroll(WorkspaceNodeType.EXECUTION_RUN, run.getId());
                host.markDirty("Evidence image added.");
            });
            task.setOnFailed(e -> {
                evidenceGallery.getChildren().remove(placeholder);
                if (galleryWasEmpty && evidenceGallery.getChildren().isEmpty()) {
                    evidenceGallery.getChildren().add(createPlaceholderLabel("No evidence images have been added to this run yet."));
                }
                Throwable ex = task.getException();
                showOperationError("Unable to add evidence", ex instanceof Exception ce ? ce : new Exception(ex));
            });
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            thread.start();
        });

        HBox evidenceActions = createInlineActions(pasteEvidenceButton, addEvidenceButton);

        if (runSnapshot.getEvidences().isEmpty()) {
            evidenceGallery.getChildren().add(createPlaceholderLabel("No evidence images have been added to this run yet."));
        } else {
            for (var evidence : runSnapshot.getEvidences()) {
                VBox card = new VBox(8);
                card.getStyleClass().add("evidence-card");

                javafx.scene.image.ImageView preview = new javafx.scene.image.ImageView();
                preview.setFitWidth(180);
                preview.setFitHeight(110);
                preview.setPreserveRatio(true);
                preview.setSmooth(true);
                StackPane previewFrame = new StackPane(preview);
                previewFrame.getStyleClass().add("evidence-preview");

                if (evidence.isImage()) {
                    try {
                        java.io.File evidenceFile = host.getProjectService().resolveProjectPath(evidence.getStoredPath()).toFile();
                        if (evidenceFile.exists()) {
                            java.awt.image.BufferedImage bufferedImage = javax.imageio.ImageIO.read(evidenceFile);
                            if (bufferedImage != null) {
                                preview.setImage(javafx.embed.swing.SwingFXUtils.toFXImage(bufferedImage, null));
                            }
                        }
                    } catch (IOException exception) {
                        LOGGER.warn("Unable to load evidence preview for '{}'", evidence.getStoredPath(), exception);
                    }
                }

                Label nameLabel = createEvidenceFileNameLabel(evidence.getThumbnailDisplayName());
                Button removeEvidenceButton = createSecondaryButton("Remove", "fas-trash");
                removeEvidenceButton.setOnAction(event -> runWorkspaceOperation(
                        "Unable to remove evidence",
                        () -> {
                            host.getProjectService().deleteExecutionRunEvidence(report.getId(), evidence.getId());
                            host.reloadWorkspaceAndReselectPreservingScroll(WorkspaceNodeType.EXECUTION_RUN, run.getId());
                            host.markDirty("Evidence image removed.");
                        }
                ));

                card.getChildren().addAll(previewFrame, nameLabel, removeEvidenceButton);
                evidenceGallery.getChildren().add(card);
            }
        }

        VBox content = new VBox(
                16,
                heading,
                hint,
                createSectionSubheading("Execution Run Details"),
                runGrid,
                createSectionSubheading("Comments and Notes"),
                createSupportLabel("Capture run-level comments and notes that belong to this execution run rather than the report."),
                communicationGrid,
                createSectionSubheading("Detailed Test Result"),
                resultGrid,
                createSectionSubheading("Defect Summary"),
                createSupportLabel("Capture the defect narrative for this run, including any linked issue reference."),
                defectGrid,
                createSectionSubheading("Test Evidence"),
                createSupportLabel("Add one or more image evidences from the clipboard or a file. ReportForge copies them into the project."),
                evidenceActions,
                evidenceGallery
        );
        return UiSupport.wrapInScrollPane(content);
    }

    private Label createSupportLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("supporting-text");
        label.setWrapText(true);
        return label;
    }

    private Label createEvidenceFileNameLabel(String text) {
        Label label = createSupportLabel(text);
        label.getStyleClass().add("evidence-file-name");
        label.setWrapText(false);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private VBox createEvidencePlaceholderCard() {
        StackPane previewFrame = new StackPane();
        previewFrame.getStyleClass().add("evidence-preview");

        ProgressBar progressBar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(4);
        StackPane.setAlignment(progressBar, Pos.BOTTOM_CENTER);
        previewFrame.getChildren().add(progressBar);

        VBox card = new VBox(8, previewFrame, createEvidenceFileNameLabel("Processing…"));
        card.getStyleClass().add("evidence-card");
        return card;
    }

    private Label createSectionSubheading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("subsection-heading");
        return label;
    }

    private VBox createSummaryPanel(Node... children) {
        VBox panel = new VBox(12, children);
        panel.getStyleClass().add("summary-panel");
        return panel;
    }

    private Label createPlaceholderLabel(String text) {
        Label label = createSupportLabel(text);
        label.setMinHeight(120);
        return label;
    }

    private String calculatePassRate(ExecutionMetrics metrics) {
        return UiSupport.calculatePassRate(
                Integer.toString(metrics.getPassedCount()),
                Integer.toString(metrics.getTotalExecuted())
        );
    }

    private String formatPassRateValue(ExecutionMetrics metrics) {
        String passRate = calculatePassRate(metrics);
        return "N/A".equals(passRate) ? passRate : passRate + "%";
    }

    private String formatSummaryDate(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        try {
            return SUMMARY_DATE_FORMATTER.format(LocalDate.parse(value));
        } catch (DateTimeParseException exception) {
            return value;
        }
    }

    private String normalizeResultStatus(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return switch (value.trim().toUpperCase()) {
            case "PASSED" -> "PASS";
            case "FAILED" -> "FAIL";
            case "SKIP" -> "SKIPPED";
            default -> value.trim().toUpperCase();
        };
    }

    private String valueOrFallback(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private LocalDate parseOptionalDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String dateValue(DatePicker datePicker) {
        return datePicker.getValue() == null ? "" : datePicker.getValue().toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean booleanField(Map<String, String> fields, String fieldKey, boolean defaultValue) {
        String value = fields.get(fieldKey);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    private void bindReportField(String reportId, TextField textField, String fieldKey) {
        bindReportField(reportId, textField, fieldKey, null);
    }

    private void bindReportField(String reportId, TextField textField, String fieldKey, Runnable afterSave) {
        commitOnFocusLost(textField, value -> {
            saveReportField(reportId, fieldKey, value, "Report updated.");
            if (afterSave != null) {
                afterSave.run();
            }
        });
    }

    private void bindReportField(String reportId, TextArea textArea, String fieldKey) {
        commitOnFocusLost(textArea, value -> saveReportField(reportId, fieldKey, value, "Report updated."));
    }

    private void bindReportField(String reportId, CheckBox checkBox, String fieldKey) {
        checkBox.selectedProperty().addListener((observable, previous, selected) ->
                saveReportField(reportId, fieldKey, Boolean.toString(selected), "Export preset updated."));
    }

    private void bindReportDateField(String reportId, DatePicker datePicker, String fieldKey) {
        datePicker.valueProperty().addListener((observable, previous, value) -> {
            if (value != null) {
                saveReportField(reportId, fieldKey, value.toString(), "Report updated.");
            }
        });
    }

    private void saveReportField(String reportId, String fieldKey, String value, String successMessage) {
        runWorkspaceOperation("Unable to update report", () -> {
            host.getProjectService().updateReportField(reportId, fieldKey, value);
            host.markDirty(successMessage);
        });
    }

    private final class ReportSectionSupportAdapter implements ReportSectionEditors.Support {
        @Override
        public void runWorkspaceOperation(String title, ReportSectionEditors.WorkspaceOperation operation) {
            WorkspaceContentFactory.this.runWorkspaceOperation(title, operation::run);
        }

        @Override
        public void bindReportField(String reportId, TextField textField, String fieldKey) {
            WorkspaceContentFactory.this.bindReportField(reportId, textField, fieldKey);
        }

        @Override
        public void bindReportField(String reportId, TextField textField, String fieldKey, Runnable afterSave) {
            WorkspaceContentFactory.this.bindReportField(reportId, textField, fieldKey, afterSave);
        }

        @Override
        public void bindReportField(String reportId, TextArea textArea, String fieldKey) {
            WorkspaceContentFactory.this.bindReportField(reportId, textArea, fieldKey);
        }

        @Override
        public void bindReportField(String reportId, CheckBox checkBox, String fieldKey) {
            WorkspaceContentFactory.this.bindReportField(reportId, checkBox, fieldKey);
        }

        @Override
        public void bindReportDateField(String reportId, DatePicker datePicker, String fieldKey) {
            WorkspaceContentFactory.this.bindReportDateField(reportId, datePicker, fieldKey);
        }

        @Override
        public void commitOnFocusLost(TextField textField, Consumer<String> consumer) {
            WorkspaceContentFactory.this.commitOnFocusLost(textField, consumer);
        }

        @Override
        public void commitOnFocusLost(TextArea textArea, Consumer<String> consumer) {
            WorkspaceContentFactory.this.commitOnFocusLost(textArea, consumer);
        }

        @Override
        public GridPane createFormGrid() {
            return WorkspaceContentFactory.this.createFormGrid();
        }

        @Override
        public HBox createInlineActions(Button... buttons) {
            return WorkspaceContentFactory.this.createInlineActions(buttons);
        }

        @Override
        public Button createSecondaryButton(String text, String iconLiteral) {
            return WorkspaceContentFactory.this.createSecondaryButton(text, iconLiteral);
        }

        @Override
        public Label createSupportLabel(String text) {
            return WorkspaceContentFactory.this.createSupportLabel(text);
        }

        @Override
        public Label createSectionSubheading(String text) {
            return WorkspaceContentFactory.this.createSectionSubheading(text);
        }

        @Override
        public VBox createSummaryPanel(Node... children) {
            return WorkspaceContentFactory.this.createSummaryPanel(children);
        }

        @Override
        public String valueOrFallback(String value) {
            return WorkspaceContentFactory.this.valueOrFallback(value);
        }

        @Override
        public String formatPassRateValue(ExecutionMetrics metrics) {
            return WorkspaceContentFactory.this.formatPassRateValue(metrics);
        }

        @Override
        public String formatSummaryDate(String value) {
            return WorkspaceContentFactory.this.formatSummaryDate(value);
        }

        @Override
        public boolean booleanField(Map<String, String> fields, String fieldKey, boolean defaultValue) {
            return WorkspaceContentFactory.this.booleanField(fields, fieldKey, defaultValue);
        }
    }

    @FunctionalInterface
    private interface WorkspaceOperation {
        void run() throws IOException, SQLException;
    }

    private void runWorkspaceOperation(String title, WorkspaceOperation operation) {
        runWorkspaceOperation(title, operation, null);
    }

    private void runWorkspaceOperation(String title, WorkspaceOperation operation, Runnable onFailure) {
        try {
            operation.run();
        } catch (IOException | SQLException | IllegalArgumentException | IllegalStateException exception) {
            showOperationError(title, exception);
            if (onFailure != null) {
                onFailure.run();
            }
        }
    }

    private void showOperationError(String title, Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = "Operation failed.";
        }
        LOGGER.error("{}: {}", title, message, exception);
        host.showError(title, message);
    }

    private void commitOnFocusLost(TextField textField, Consumer<String> consumer) {
        textField.focusedProperty().addListener((observable, oldValue, focused) -> {
            if (!focused && oldValue) {
                consumer.accept(textField.getText());
            }
        });
    }

    private void commitOnFocusLost(TextArea textArea, Consumer<String> consumer) {
        textArea.focusedProperty().addListener((observable, oldValue, focused) -> {
            if (!focused && oldValue) {
                consumer.accept(textArea.getText());
            }
        });
    }

    private GridPane createFormGrid() {
        GridPane gridPane = new GridPane();
        gridPane.setHgap(10);
        gridPane.setVgap(10);
        gridPane.getStyleClass().add("form-grid");
        return gridPane;
    }

    private HBox createInlineActions(Button... buttons) {
        HBox actions = new HBox(8, buttons);
        actions.getStyleClass().add("inline-actions");
        return actions;
    }

    private Node buildExecutionRunWorkspacePane(ExecutionRunWorkspaceNode runNode) throws SQLException {
        ReportRecord report = runNode.report();
        ExecutionReportSnapshot executionSnapshot = host.getProjectService().loadExecutionReportSnapshot(report.getId());
        ExecutionRunSnapshot activeRun = executionSnapshot.getRuns().stream()
                .filter(snapshot -> java.util.Objects.equals(snapshot.getRun().getId(), runNode.runSnapshot().getRun().getId()))
                .findFirst()
                .orElse(runNode.runSnapshot());
        return buildExecutionRunEditor(report, activeRun);
    }

    private Button createSecondaryButton(String text, String iconLiteral) {
        Button button = new Button(text);
        button.getStyleClass().addAll("app-button", "secondary-button");
        button.setGraphic(IconSupport.createButtonIcon(iconLiteral));
        button.setContentDisplay(ContentDisplay.LEFT);
        button.setGraphicTextGap(10);
        return button;
    }
}
