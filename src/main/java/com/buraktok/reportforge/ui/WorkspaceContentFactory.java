package com.buraktok.reportforge.ui;

import com.buraktok.reportforge.model.ApplicationEntry;
import com.buraktok.reportforge.model.ExecutionMetrics;
import com.buraktok.reportforge.model.ExecutionReportSnapshot;
import com.buraktok.reportforge.model.ExecutionRunRecord;
import com.buraktok.reportforge.model.ExecutionRunSnapshot;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.TestExecutionSection;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public final class WorkspaceContentFactory {
    private static final DateTimeFormatter SUMMARY_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault());

    private final WorkspaceHost host;

    public WorkspaceContentFactory(WorkspaceHost host) {
        this.host = host;
    }

    public Node buildContent(WorkspaceNode selection) throws Exception {
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

        commitOnFocusLost(projectNameField, value -> {
            try {
                host.getProjectService().updateProject(value, projectDescriptionArea.getText());
                host.reloadWorkspaceAndReselect(
                        WorkspaceNodeType.PROJECT,
                        host.getCurrentWorkspace().getProject().getId()
                );
                host.markDirty("Project updated.");
            } catch (Exception exception) {
                host.showError("Unable to update project", exception.getMessage());
                projectNameField.setText(host.getCurrentWorkspace().getProject().getName());
            }
        });
        commitOnFocusLost(projectDescriptionArea, value -> {
            try {
                host.getProjectService().updateProject(projectNameField.getText(), value);
                host.markDirty("Project updated.");
            } catch (Exception exception) {
                host.showError("Unable to update project", exception.getMessage());
                projectDescriptionArea.setText(host.getCurrentWorkspace().getProject().getDescription());
            }
        });

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
            try {
                host.getProjectService().deleteProjectApplication(selected.getId());
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.APPLICATIONS, "project-applications");
                host.markDirty("Application removed.");
            } catch (Exception exception) {
                host.showError("Unable to remove application", exception.getMessage());
            }
        });
        primaryButton.setOnAction(event -> {
            ApplicationEntry selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null) {
                host.showInformation("Applications under Test", "Select an application to mark as primary.");
                return;
            }
            try {
                host.getProjectService().setPrimaryProjectApplication(selected.getId());
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.APPLICATIONS, "project-applications");
                host.markDirty("Primary application updated.");
            } catch (Exception exception) {
                host.showError("Unable to update primary application", exception.getMessage());
            }
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

        Consumer<Void> saveEnvironment = ignored -> {
            try {
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
            } catch (Exception exception) {
                host.showError("Unable to update environment", exception.getMessage());
            }
        };

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

    private Node buildReportEditor(ReportRecord report) throws Exception {
        Label title = new Label(report.getTitle());
        title.getStyleClass().add("panel-heading");

        ListView<TestExecutionSection> sectionsList = new ListView<>(
                FXCollections.observableArrayList(TestExecutionSection.defaultOrder())
        );
        sectionsList.setPrefWidth(260);
        sectionsList.getStyleClass().add("section-list");

        TestExecutionSection selectedSection = TestExecutionSection.fromKey(report.getLastSelectedSection());
        if (selectedSection == null) {
            selectedSection = TestExecutionSection.defaultOrder().getFirst();
        }
        sectionsList.getSelectionModel().select(selectedSection);

        javafx.scene.layout.BorderPane sectionPane = new javafx.scene.layout.BorderPane();
        sectionPane.setPadding(new Insets(0, 0, 0, 16));
        sectionPane.getStyleClass().add("section-pane");

        Consumer<TestExecutionSection> sectionRenderer = section -> {
            try {
                host.getProjectService().updateLastSelectedSection(report.getId(), section);
                Map<String, String> fields = host.getProjectService().loadReportFields(report.getId());
                List<ApplicationEntry> reportApplications = host.getProjectService().loadReportApplications(report.getId());
                EnvironmentRecord environmentSnapshot = host.getProjectService().loadReportEnvironmentSnapshot(report.getId());
                ExecutionReportSnapshot executionSnapshot = host.getProjectService().loadExecutionReportSnapshot(report.getId());
                sectionPane.setCenter(buildSectionEditor(
                        report,
                        section,
                        fields,
                        reportApplications,
                        environmentSnapshot,
                        executionSnapshot
                ));
                UiSupport.installTextInputContextMenus(host, sectionPane);
            } catch (Exception exception) {
                host.showError("Unable to update report navigation", exception.getMessage());
            }
        };

        sectionsList.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (selected != null) {
                sectionRenderer.accept(selected);
            }
        });
        sectionRenderer.accept(sectionsList.getSelectionModel().getSelectedItem());

        SplitPane editorSplit = new SplitPane(sectionsList, sectionPane);
        editorSplit.setDividerPositions(0.28);
        editorSplit.getStyleClass().add("editor-split-pane");

        VBox content = new VBox(12, title, editorSplit);
        VBox.setVgrow(editorSplit, Priority.ALWAYS);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("content-card");
        return content;
    }

    private Node buildSectionEditor(
            ReportRecord report,
            TestExecutionSection section,
            Map<String, String> fields,
            List<ApplicationEntry> reportApplications,
            EnvironmentRecord environmentSnapshot,
            ExecutionReportSnapshot executionSnapshot
    ) throws Exception {
        return switch (section) {
            case PROJECT_OVERVIEW -> buildProjectOverviewSection(report, fields, environmentSnapshot);
            case APPLICATIONS_UNDER_TEST -> buildReportApplicationsSection(report, reportApplications);
            case TEST_ENVIRONMENT -> buildReportEnvironmentSection(report, environmentSnapshot);
            case TEST_OBJECTIVES_SCOPE -> buildScopeSection(report, fields);
            case BUILD_RELEASE_INFORMATION -> buildSimpleTextSection(report, fields, section, "buildReleaseInfo.content", "Build / Release Information");
            case EXECUTION_SUMMARY -> buildExecutionSummarySection(report, fields, executionSnapshot);
            case TEST_COVERAGE -> buildSimpleTextSection(report, fields, section, "testCoverage.content", "Test Coverage");
            case RISK_ASSESSMENT -> buildSimpleTextSection(report, fields, section, "riskAssessment.content", "Risk Assessment");
            case COMMENTS_AND_NOTES -> buildCommentsAndNotesSection(report, fields);
            case CONCLUSION_AND_RECOMMENDATIONS -> buildConclusionSection(report, fields);
        };
    }

    private Node buildProjectOverviewSection(ReportRecord report, Map<String, String> fields, EnvironmentRecord environmentSnapshot) {
        VBox content = new VBox(12);
        content.getChildren().add(UiSupport.sectionHeading(TestExecutionSection.PROJECT_OVERVIEW));

        TextField projectNameField = UiSupport.readOnlyField(
                fields.getOrDefault("projectOverview.projectName", host.getCurrentWorkspace().getProject().getName())
        );
        TextField reportTitleField = new TextField(report.getTitle());
        TextField reportTypeField = UiSupport.readOnlyField(
                fields.getOrDefault("projectOverview.reportType", "Test Execution Report")
        );
        TextField environmentField = UiSupport.readOnlyField(environmentSnapshot.getName());
        TextField reportStatusField = UiSupport.readOnlyField(report.getStatus().getDisplayName());
        TextArea projectDescriptionArea = new TextArea(fields.getOrDefault("projectOverview.projectDescription", ""));
        projectDescriptionArea.setPrefRowCount(4);
        TextField releaseIterationField = new TextField(fields.getOrDefault("projectOverview.releaseIterationName", ""));
        TextField preparedByField = new TextField(fields.getOrDefault("projectOverview.preparedBy", ""));
        DatePicker preparedDatePicker = new DatePicker(
                UiSupport.parseDate(fields.getOrDefault("projectOverview.preparedDate", java.time.LocalDate.now().toString()))
        );
        TextField audienceField = new TextField(fields.getOrDefault("projectOverview.stakeholderAudience", ""));
        TextField revisionNoteField = new TextField(fields.getOrDefault("projectOverview.revisionNote", ""));

        commitOnFocusLost(reportTitleField, value -> {
            try {
                host.getProjectService().updateReportTitle(report.getId(), value);
                host.markDirty("Report title updated.");
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
            } catch (Exception exception) {
                host.showError("Unable to update report title", exception.getMessage());
            }
        });
        bindReportField(report.getId(), projectDescriptionArea, "projectOverview.projectDescription");
        bindReportField(report.getId(), releaseIterationField, "projectOverview.releaseIterationName");
        bindReportField(report.getId(), preparedByField, "projectOverview.preparedBy");
        bindReportDateField(report.getId(), preparedDatePicker, "projectOverview.preparedDate");
        bindReportField(report.getId(), audienceField, "projectOverview.stakeholderAudience");
        bindReportField(report.getId(), revisionNoteField, "projectOverview.revisionNote");

        GridPane gridPane = createFormGrid();
        gridPane.addRow(0, new Label("Project Name"), projectNameField);
        gridPane.addRow(1, new Label("Report Title"), reportTitleField);
        gridPane.addRow(2, new Label("Report Type"), reportTypeField);
        gridPane.addRow(3, new Label("Environment Name"), environmentField);
        gridPane.addRow(4, new Label("Report Status"), reportStatusField);
        gridPane.addRow(5, new Label("Project Description"), projectDescriptionArea);
        gridPane.addRow(6, new Label("Release / Iteration Name"), releaseIterationField);
        gridPane.addRow(7, new Label("Prepared By"), preparedByField);
        gridPane.addRow(8, new Label("Prepared Date"), preparedDatePicker);
        gridPane.addRow(9, new Label("Stakeholder Audience"), audienceField);
        gridPane.addRow(10, new Label("Revision Note"), revisionNoteField);

        content.getChildren().add(gridPane);
        return UiSupport.wrapInScrollPane(content);
    }

    private Node buildReportApplicationsSection(ReportRecord report, List<ApplicationEntry> reportApplications) {
        VBox content = new VBox(12);
        content.getChildren().add(UiSupport.sectionHeading(TestExecutionSection.APPLICATIONS_UNDER_TEST));

        ListView<ApplicationEntry> listView = new ListView<>(FXCollections.observableArrayList(reportApplications));
        listView.setPrefHeight(260);
        listView.getStyleClass().add("entity-list");

        Button addButton = createSecondaryButton("Add", "fas-plus");
        Button editButton = createSecondaryButton("Edit", "fas-edit");
        Button removeButton = createSecondaryButton("Remove", "fas-trash");
        Button primaryButton = createSecondaryButton("Make Primary", "fas-star");
        HBox actions = createInlineActions(addButton, editButton, removeButton, primaryButton);

        addButton.setOnAction(event -> {
            var input = ApplicationEntryDialog.show(host, "Add Application", null);
            input.ifPresent(application -> {
                try {
                    host.getProjectService().upsertReportApplication(report.getId(), application);
                    host.reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                    host.markDirty("Report application added.");
                } catch (Exception exception) {
                    host.showError("Unable to add report application", exception.getMessage());
                }
            });
        });
        editButton.setOnAction(event -> {
            ApplicationEntry selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null) {
                host.showInformation("Applications under Test", "Select an application to edit.");
                return;
            }
            var input = ApplicationEntryDialog.show(host, "Edit Application", selected);
            input.ifPresent(application -> {
                try {
                    host.getProjectService().upsertReportApplication(report.getId(), application);
                    host.reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                    host.markDirty("Report application updated.");
                } catch (Exception exception) {
                    host.showError("Unable to update report application", exception.getMessage());
                }
            });
        });
        removeButton.setOnAction(event -> {
            ApplicationEntry selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null) {
                host.showInformation("Applications under Test", "Select an application to remove.");
                return;
            }
            try {
                host.getProjectService().deleteReportApplication(selected.getId(), report.getId());
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                host.markDirty("Report application removed.");
            } catch (Exception exception) {
                host.showError("Unable to remove report application", exception.getMessage());
            }
        });
        primaryButton.setOnAction(event -> {
            ApplicationEntry selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null) {
                host.showInformation("Applications under Test", "Select an application to mark as primary.");
                return;
            }
            try {
                host.getProjectService().setPrimaryReportApplication(report.getId(), selected.getId());
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                host.markDirty("Primary report application updated.");
            } catch (Exception exception) {
                host.showError("Unable to update primary report application", exception.getMessage());
            }
        });

        content.getChildren().addAll(listView, actions);
        return UiSupport.wrapInScrollPane(content);
    }

    private Node buildReportEnvironmentSection(ReportRecord report, EnvironmentRecord environmentSnapshot) {
        VBox content = new VBox(12);
        content.getChildren().add(UiSupport.sectionHeading(TestExecutionSection.TEST_ENVIRONMENT));

        TextField nameField = new TextField(environmentSnapshot.getName());
        TextField typeField = new TextField(environmentSnapshot.getType());
        TextField urlField = new TextField(environmentSnapshot.getBaseUrl());
        TextField osField = new TextField(environmentSnapshot.getOsPlatform());
        TextField browserField = new TextField(environmentSnapshot.getBrowserClient());
        TextField backendField = new TextField(environmentSnapshot.getBackendVersion());
        TextArea notesArea = new TextArea(environmentSnapshot.getNotes());
        notesArea.setPrefRowCount(5);

        Consumer<Void> saveAction = ignored -> {
            try {
                host.getProjectService().updateReportEnvironmentSnapshot(report.getId(), new EnvironmentRecord(
                        report.getId(),
                        nameField.getText(),
                        typeField.getText(),
                        urlField.getText(),
                        osField.getText(),
                        browserField.getText(),
                        backendField.getText(),
                        notesArea.getText(),
                        0
                ));
                host.markDirty("Report environment updated.");
            } catch (Exception exception) {
                host.showError("Unable to update report environment", exception.getMessage());
            }
        };

        commitOnFocusLost(nameField, value -> saveAction.accept(null));
        commitOnFocusLost(typeField, value -> saveAction.accept(null));
        commitOnFocusLost(urlField, value -> saveAction.accept(null));
        commitOnFocusLost(osField, value -> saveAction.accept(null));
        commitOnFocusLost(browserField, value -> saveAction.accept(null));
        commitOnFocusLost(backendField, value -> saveAction.accept(null));
        commitOnFocusLost(notesArea, value -> saveAction.accept(null));

        GridPane gridPane = createFormGrid();
        gridPane.addRow(0, new Label("Environment Name"), nameField);
        gridPane.addRow(1, new Label("Environment Type"), typeField);
        gridPane.addRow(2, new Label("Base URL / Endpoint"), urlField);
        gridPane.addRow(3, new Label("OS / Platform"), osField);
        gridPane.addRow(4, new Label("Browser / Client"), browserField);
        gridPane.addRow(5, new Label("Database / Backend Version"), backendField);
        gridPane.addRow(6, new Label("Environment Notes"), notesArea);
        content.getChildren().add(gridPane);
        return UiSupport.wrapInScrollPane(content);
    }

    private Node buildScopeSection(ReportRecord report, Map<String, String> fields) {
        VBox content = new VBox(12);
        content.getChildren().add(UiSupport.sectionHeading(TestExecutionSection.TEST_OBJECTIVES_SCOPE));

        TextArea objectiveSummary = UiSupport.createArea(fields.getOrDefault("scope.objectiveSummary", ""), 4);
        TextArea inScopeItems = UiSupport.createArea(fields.getOrDefault("scope.inScopeItems", ""), 3);
        TextArea outOfScopeItems = UiSupport.createArea(fields.getOrDefault("scope.outOfScopeItems", ""), 3);
        TextArea strategyNote = UiSupport.createArea(fields.getOrDefault("scope.testStrategyNote", ""), 3);
        TextArea assumptions = UiSupport.createArea(fields.getOrDefault("scope.assumptions", ""), 3);
        TextArea dependencies = UiSupport.createArea(fields.getOrDefault("scope.dependencies", ""), 3);
        TextArea entryCriteria = UiSupport.createArea(fields.getOrDefault("scope.entryCriteria", ""), 3);
        TextArea exitCriteria = UiSupport.createArea(fields.getOrDefault("scope.exitCriteria", ""), 3);

        bindReportField(report.getId(), objectiveSummary, "scope.objectiveSummary");
        bindReportField(report.getId(), inScopeItems, "scope.inScopeItems");
        bindReportField(report.getId(), outOfScopeItems, "scope.outOfScopeItems");
        bindReportField(report.getId(), strategyNote, "scope.testStrategyNote");
        bindReportField(report.getId(), assumptions, "scope.assumptions");
        bindReportField(report.getId(), dependencies, "scope.dependencies");
        bindReportField(report.getId(), entryCriteria, "scope.entryCriteria");
        bindReportField(report.getId(), exitCriteria, "scope.exitCriteria");

        GridPane gridPane = createFormGrid();
        gridPane.addRow(0, new Label("Test Objective Summary"), objectiveSummary);
        gridPane.addRow(1, new Label("In-Scope Items"), inScopeItems);
        gridPane.addRow(2, new Label("Out-of-Scope Items"), outOfScopeItems);
        gridPane.addRow(3, new Label("Test Strategy Note"), strategyNote);
        gridPane.addRow(4, new Label("Assumptions"), assumptions);
        gridPane.addRow(5, new Label("Dependencies"), dependencies);
        gridPane.addRow(6, new Label("Entry Criteria"), entryCriteria);
        gridPane.addRow(7, new Label("Exit Criteria"), exitCriteria);
        content.getChildren().add(gridPane);
        return UiSupport.wrapInScrollPane(content);
    }

    private Node buildExecutionSummarySection(
            ReportRecord report,
            Map<String, String> fields,
            ExecutionReportSnapshot executionSnapshot
    ) {
        VBox content = new VBox(18);
        content.getChildren().add(UiSupport.sectionHeading(TestExecutionSection.EXECUTION_SUMMARY));

        Label summaryHint = new Label(
                "This dashboard is derived from the execution runs attached to this report. Open execution runs from the workspace tree to edit run-specific details."
        );
        summaryHint.getStyleClass().add("supporting-text");

        TextArea narrativeArea = UiSupport.createArea(fields.getOrDefault("executionSummary.summaryNarrative", ""), 4);
        narrativeArea.setWrapText(true);
        bindReportField(report.getId(), narrativeArea, "executionSummary.summaryNarrative");
        VBox narrativePanel = createSummaryPanel(
                createSectionSubheading("Execution Narrative"),
                createSupportLabel("Summarize release readiness, notable risks, and the key takeaways across all execution runs."),
                narrativeArea
        );

        content.getChildren().addAll(
                summaryHint,
                createExecutionMetricsDashboard(executionSnapshot.getMetrics()),
                narrativePanel
        );
        content.setFillWidth(true);
        return UiSupport.wrapInScrollPane(content);
    }

    private Node buildExecutionRunEditor(ReportRecord report, ExecutionRunSnapshot runSnapshot) {
        ExecutionRunRecord run = runSnapshot.getRun();

        Label heading = new Label(report.getTitle() + " - " + run.getDisplayLabel());
        heading.getStyleClass().add("panel-heading");

        Label hint = createSupportLabel(
                "Each execution run owns a single result, its defect summary, and any attached evidence images."
        );

        TextField executionIdField = new TextField(nullToEmpty(run.getExecutionKey()));
        TextField suiteNameField = new TextField(nullToEmpty(run.getSuiteName()));
        TextField executedByField = new TextField(nullToEmpty(run.getExecutedBy()));
        DatePicker executionDatePicker = new DatePicker(parseOptionalDate(run.getExecutionDate()));
        DatePicker startDatePicker = new DatePicker(parseOptionalDate(run.getStartDate()));
        DatePicker endDatePicker = new DatePicker(parseOptionalDate(run.getEndDate()));
        TextField durationField = new TextField(nullToEmpty(run.getDurationText()));
        TextField dataSourceField = new TextField(nullToEmpty(run.getDataSourceReference()));
        TextArea notesArea = UiSupport.createArea(nullToEmpty(run.getNotes()), 3);
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
            ExecutionRunRecord updatedRun = new ExecutionRunRecord(
                    run.getId(),
                    report.getId(),
                    executionIdField.getText(),
                    suiteNameField.getText(),
                    executedByField.getText(),
                    dateValue(executionDatePicker),
                    dateValue(startDatePicker),
                    dateValue(endDatePicker),
                    durationField.getText(),
                    dataSourceField.getText(),
                    notesArea.getText(),
                    testCaseIdField.getText(),
                    sectionField.getText(),
                    subsectionField.getText(),
                    testCaseNameField.getText(),
                    priorityField.getText(),
                    moduleField.getText(),
                    statusCombo.getValue(),
                    executionTimeField.getText(),
                    expectedSummaryArea.getText(),
                    actualResultArea.getText(),
                    relatedIssueField.getText(),
                    remarksArea.getText(),
                    blockedReasonArea.getText(),
                    defectSummaryArea.getText(),
                    run.getLegacyTotalExecuted(),
                    run.getLegacyPassedCount(),
                    run.getLegacyFailedCount(),
                    run.getLegacyBlockedCount(),
                    run.getLegacyNotRunCount(),
                    run.getLegacyDeferredCount(),
                    run.getLegacySkippedCount(),
                    run.getLegacyLinkedDefectCount(),
                    run.getLegacyOverallOutcome(),
                    run.getSortOrder(),
                    run.getCreatedAt(),
                    run.getUpdatedAt()
            );
            try {
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
            } catch (Exception exception) {
                host.showError("Unable to update execution run", exception.getMessage());
            }
        };

        commitOnFocusLost(executionIdField, value -> saveRun.run());
        commitOnFocusLost(suiteNameField, value -> saveRun.run());
        commitOnFocusLost(executedByField, value -> saveRun.run());
        commitOnFocusLost(durationField, value -> saveRun.run());
        commitOnFocusLost(dataSourceField, value -> saveRun.run());
        commitOnFocusLost(notesArea, value -> saveRun.run());
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
        runGrid.addRow(9, new Label("Run Notes"), notesArea);

        GridPane resultGrid = createFormGrid();
        resultGrid.addRow(0, new Label("Test Case ID"), testCaseIdField);
        resultGrid.addRow(1, new Label("Section"), sectionField);
        resultGrid.addRow(2, new Label("Subsection"), subsectionField);
        resultGrid.addRow(3, new Label("Test Case Name"), testCaseNameField);
        resultGrid.addRow(4, new Label("Priority"), priorityField);
        resultGrid.addRow(5, new Label("Module / Component"), moduleField);
        resultGrid.addRow(6, new Label("Execution Time"), executionTimeField);
        resultGrid.addRow(7, new Label("Expected Result Summary"), expectedSummaryArea);
        resultGrid.addRow(8, new Label("Actual Result"), actualResultArea);
        resultGrid.addRow(9, new Label("Blocked Reason"), blockedReasonArea);
        resultGrid.addRow(10, new Label("Remarks"), remarksArea);

        GridPane defectGrid = createFormGrid();
        defectGrid.addRow(0, new Label("Related Issue"), relatedIssueField);
        defectGrid.addRow(1, new Label("Defect Summary"), defectSummaryArea);

        Button pasteEvidenceButton = createSecondaryButton("Paste Clipboard Image", "fas-paste");
        pasteEvidenceButton.setOnAction(event -> {
            javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            if (!clipboard.hasImage()) {
                host.showInformation("Test Evidence", "No image is currently available in the clipboard.");
                return;
            }
            try (java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream()) {
                java.awt.image.BufferedImage bufferedImage = javafx.embed.swing.SwingFXUtils.fromFXImage(clipboard.getImage(), null);
                if (bufferedImage == null || !javax.imageio.ImageIO.write(bufferedImage, "png", outputStream)) {
                    host.showError("Unable to add evidence", "Clipboard image could not be converted to PNG.");
                    return;
                }
                host.getProjectService().addExecutionRunEvidence(
                        report.getId(),
                        run.getId(),
                        "clipboard-evidence.png",
                        "image/png",
                        outputStream.toByteArray()
                );
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.EXECUTION_RUN, run.getId());
                host.markDirty("Evidence image added.");
            } catch (Exception exception) {
                host.showError("Unable to add evidence", exception.getMessage());
            }
        });

        Button addEvidenceButton = createSecondaryButton("Add Image From File", "fas-image");
        addEvidenceButton.setOnAction(event -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("Add Evidence Image");
            chooser.getExtensionFilters().setAll(new javafx.stage.FileChooser.ExtensionFilter(
                    "Image Files",
                    "*.png",
                    "*.jpg",
                    "*.jpeg",
                    "*.gif",
                    "*.bmp",
                    "*.webp"
            ));
            java.io.File selectedFile = chooser.showOpenDialog(host.getPrimaryStage());
            if (selectedFile == null) {
                return;
            }
            try {
                host.getProjectService().addExecutionRunEvidenceFromFile(report.getId(), run.getId(), selectedFile.toPath());
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.EXECUTION_RUN, run.getId());
                host.markDirty("Evidence image added.");
            } catch (Exception exception) {
                host.showError("Unable to add evidence", exception.getMessage());
            }
        });

        HBox evidenceActions = createInlineActions(pasteEvidenceButton, addEvidenceButton);

        javafx.scene.layout.FlowPane evidenceGallery = new javafx.scene.layout.FlowPane();
        evidenceGallery.setHgap(12);
        evidenceGallery.setVgap(12);
        evidenceGallery.getStyleClass().add("evidence-gallery");

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
                javafx.scene.layout.StackPane previewFrame = new javafx.scene.layout.StackPane(preview);
                previewFrame.getStyleClass().add("evidence-preview");

                if (evidence.isImage()) {
                    preview.setImage(new javafx.scene.image.Image(
                            host.getProjectService().resolveProjectPath(evidence.getStoredPath()).toUri().toString(),
                            180,
                            110,
                            true,
                            true,
                            true
                    ));
                }

                Label nameLabel = createSupportLabel(evidence.getDisplayName());
                Button removeEvidenceButton = createSecondaryButton("Remove", "fas-trash");
                removeEvidenceButton.setOnAction(event -> {
                    try {
                        host.getProjectService().deleteExecutionRunEvidence(report.getId(), evidence.getId());
                        host.reloadWorkspaceAndReselect(WorkspaceNodeType.EXECUTION_RUN, run.getId());
                        host.markDirty("Evidence image removed.");
                    } catch (Exception exception) {
                        host.showError("Unable to remove evidence", exception.getMessage());
                    }
                });

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
        if (hasLegacyMetrics(run)) {
            content.getChildren().add(1, createSupportLabel(
                    "This run still carries migrated legacy summary values behind the scenes, but the active report metrics now come from the run status."
            ));
        }
        return UiSupport.wrapInScrollPane(content);
    }

    private Node buildCommentsAndNotesSection(ReportRecord report, Map<String, String> fields) {
        VBox content = new VBox(12);
        content.getChildren().add(UiSupport.sectionHeading(TestExecutionSection.COMMENTS_AND_NOTES));

        TextArea commentsArea = UiSupport.createArea(fields.getOrDefault("comments.content", ""), 5);
        TextArea notesArea = UiSupport.createArea(fields.getOrDefault("notes.content", ""), 5);
        bindReportField(report.getId(), commentsArea, "comments.content");
        bindReportField(report.getId(), notesArea, "notes.content");

        GridPane gridPane = createFormGrid();
        gridPane.addRow(0, new Label("Comments"), commentsArea);
        gridPane.addRow(1, new Label("Notes"), notesArea);
        content.getChildren().add(gridPane);
        return UiSupport.wrapInScrollPane(content);
    }

    private Node buildConclusionSection(ReportRecord report, Map<String, String> fields) {
        VBox content = new VBox(12);
        content.getChildren().add(UiSupport.sectionHeading(TestExecutionSection.CONCLUSION_AND_RECOMMENDATIONS));

        TextArea overallConclusion = UiSupport.createArea(fields.getOrDefault("conclusion.overallConclusion", ""), 5);
        TextArea recommendations = UiSupport.createArea(fields.getOrDefault("conclusion.recommendations", ""), 4);
        TextArea limitations = UiSupport.createArea(fields.getOrDefault("conclusion.knownLimitations", ""), 3);
        TextArea followUp = UiSupport.createArea(fields.getOrDefault("conclusion.followUpActions", ""), 3);
        TextArea openConcerns = UiSupport.createArea(fields.getOrDefault("conclusion.openConcerns", ""), 3);

        bindReportField(report.getId(), overallConclusion, "conclusion.overallConclusion");
        bindReportField(report.getId(), recommendations, "conclusion.recommendations");
        bindReportField(report.getId(), limitations, "conclusion.knownLimitations");
        bindReportField(report.getId(), followUp, "conclusion.followUpActions");
        bindReportField(report.getId(), openConcerns, "conclusion.openConcerns");

        GridPane gridPane = createFormGrid();
        gridPane.addRow(0, new Label("Overall Conclusion"), overallConclusion);
        gridPane.addRow(1, new Label("Recommendations"), recommendations);
        gridPane.addRow(2, new Label("Known Limitations"), limitations);
        gridPane.addRow(3, new Label("Follow-up Actions"), followUp);
        gridPane.addRow(4, new Label("Open Concerns"), openConcerns);
        content.getChildren().add(gridPane);
        return UiSupport.wrapInScrollPane(content);
    }

    private Node buildSimpleTextSection(
            ReportRecord report,
            Map<String, String> fields,
            TestExecutionSection section,
            String fieldKey,
            String labelText
    ) {
        VBox content = new VBox(12);
        content.getChildren().add(UiSupport.sectionHeading(section));

        TextArea area = UiSupport.createArea(fields.getOrDefault(fieldKey, ""), 8);
        bindReportField(report.getId(), area, fieldKey);

        GridPane gridPane = createFormGrid();
        gridPane.addRow(0, new Label(labelText), area);
        content.getChildren().add(gridPane);
        return UiSupport.wrapInScrollPane(content);
    }

    private Node createExecutionMetricsDashboard(ExecutionMetrics metrics) {
        VBox dashboard = new VBox(14);
        dashboard.getStyleClass().add("summary-dashboard");

        FlowPane primaryCards = createMetricFlowPane("summary-primary-cards");
        primaryCards.getChildren().addAll(
                createMetricCard("Overall Outcome", valueOrFallback(metrics.getOverallOutcome()), "fas-signal", "summary-metric-card-primary"),
                createMetricCard("Pass Rate", formatPassRateValue(metrics), "fas-percentage", "summary-metric-card-primary"),
                createMetricCard("Total Test Cases", Integer.toString(metrics.getTestCaseCount()), "fas-list-ol", "summary-metric-card-primary")
        );

        FlowPane resultCards = createMetricFlowPane("summary-secondary-cards");
        resultCards.getChildren().addAll(
                createMetricCard("Passed", Integer.toString(metrics.getPassedCount()), "fas-check-circle", "summary-metric-card-secondary"),
                createMetricCard("Failed", Integer.toString(metrics.getFailedCount()), "fas-times-circle", "summary-metric-card-secondary"),
                createMetricCard("Blocked", Integer.toString(metrics.getBlockedCount()), "fas-ban", "summary-metric-card-secondary"),
                createMetricCard("Skipped", Integer.toString(metrics.getSkippedCount()), "fas-forward", "summary-metric-card-secondary"),
                createMetricCard("Deferred", Integer.toString(metrics.getDeferredCount()), "far-clock", "summary-metric-card-secondary"),
                createMetricCard("Not Run", Integer.toString(metrics.getNotRunCount()), "far-circle", "summary-metric-card-secondary")
        );

        FlowPane metaCards = createMetricFlowPane("summary-meta-cards");
        metaCards.getChildren().addAll(
                createMetricCard("Linked Issues", Integer.toString(metrics.getIssueCount()), "fas-bug", "summary-metric-card-meta"),
                createMetricCard("Evidence", Integer.toString(metrics.getEvidenceCount()), "fas-image", "summary-metric-card-meta"),
                createMetricCard("Earliest Date", formatSummaryDate(metrics.getEarliestDate()), "far-calendar-alt", "summary-metric-card-meta"),
                createMetricCard("Latest Date", formatSummaryDate(metrics.getLatestDate()), "far-calendar-check", "summary-metric-card-meta")
        );

        dashboard.getChildren().addAll(primaryCards, resultCards, metaCards);
        return dashboard;
    }

    private Label createSupportLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("supporting-text");
        label.setWrapText(true);
        return label;
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

    private FlowPane createMetricFlowPane(String styleClass) {
        FlowPane pane = new FlowPane();
        pane.setHgap(8);
        pane.setVgap(8);
        pane.getStyleClass().add(styleClass);
        return pane;
    }

    private VBox createMetricCard(String labelText, String valueText, String iconLiteral, String... styleClasses) {
        Label label = new Label(labelText);
        label.getStyleClass().add("summary-metric-label");

        Node icon = IconSupport.createSectionIcon(iconLiteral);
        icon.getStyleClass().add("summary-metric-icon");

        HBox header = new HBox(6, icon, label);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("summary-metric-header");

        Label value = new Label(valueText);
        value.getStyleClass().add("summary-metric-value");
        value.setWrapText(true);

        VBox card = new VBox(6, header, value);
        card.getStyleClass().add("summary-metric-card");
        card.getStyleClass().addAll(styleClasses);
        return card;
    }

    private boolean hasLegacyMetrics(ExecutionRunRecord run) {
        return run.getLegacyTotalExecuted() != null
                || run.getLegacyPassedCount() != null
                || run.getLegacyFailedCount() != null
                || run.getLegacyBlockedCount() != null
                || run.getLegacyNotRunCount() != null
                || run.getLegacyDeferredCount() != null
                || run.getLegacySkippedCount() != null
                || run.getLegacyLinkedDefectCount() != null
                || !nullToEmpty(run.getLegacyOverallOutcome()).isBlank();
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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

    private void bindReportDateField(String reportId, DatePicker datePicker, String fieldKey) {
        datePicker.valueProperty().addListener((observable, previous, value) -> {
            if (value != null) {
                saveReportField(reportId, fieldKey, value.toString(), "Report updated.");
            }
        });
    }

    private void saveReportField(String reportId, String fieldKey, String value, String successMessage) {
        try {
            host.getProjectService().updateReportField(reportId, fieldKey, value);
            host.markDirty(successMessage);
        } catch (Exception exception) {
            host.showError("Unable to update report", exception.getMessage());
        }
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

    private Node buildExecutionRunWorkspacePane(ExecutionRunWorkspaceNode runNode) throws Exception {
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
