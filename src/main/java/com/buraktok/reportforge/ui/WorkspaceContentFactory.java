package com.buraktok.reportforge.ui;

import com.buraktok.reportforge.model.ApplicationEntry;
import com.buraktok.reportforge.model.ExecutionMetrics;
import com.buraktok.reportforge.model.ExecutionReportSnapshot;
import com.buraktok.reportforge.model.ExecutionRunRecord;
import com.buraktok.reportforge.model.ExecutionRunSnapshot;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.TestCaseResultRecord;
import com.buraktok.reportforge.model.TestCaseResultSnapshot;
import com.buraktok.reportforge.model.TestCaseStepRecord;
import com.buraktok.reportforge.model.TestExecutionSection;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public final class WorkspaceContentFactory {
    private final WorkspaceHost host;

    public WorkspaceContentFactory(WorkspaceHost host) {
        this.host = host;
    }

    public Node buildContent(WorkspaceNode selection) throws Exception {
        return switch (selection.type()) {
            case PROJECT -> buildProjectDetailsPane();
            case APPLICATIONS -> buildProjectApplicationsPane();
            case ENVIRONMENT -> buildEnvironmentPane((EnvironmentRecord) selection.payload());
            case REPORT -> buildReportEditor((ReportRecord) selection.payload());
            case EXECUTION_RUN -> buildExecutionRunWorkspacePane((ExecutionRunWorkspaceNode) selection.payload());
        };
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
            case DETAILED_TEST_RESULTS -> buildDetailedResultsSection(executionSnapshot);
            case DEFECT_SUMMARY -> buildDefectSummarySection(executionSnapshot);
            case TEST_EVIDENCE -> buildEvidenceSection(executionSnapshot);
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
        return buildExecutionSummarySection(report, fields, executionSnapshot, null);
    }

    private Node buildExecutionSummarySection(
            ReportRecord report,
            Map<String, String> fields,
            ExecutionReportSnapshot executionSnapshot,
            String initialRunId
    ) {
        VBox content = new VBox(16);
        content.getChildren().add(UiSupport.sectionHeading(TestExecutionSection.EXECUTION_SUMMARY));

        Label summaryHeading = new Label("Derived execution summary");
        summaryHeading.getStyleClass().add("subsection-heading");

        Label summaryHint = new Label(
                "Run headers, case result rows, and step details drive the report metrics shown here and in future exports."
        );
        summaryHint.getStyleClass().add("supporting-text");

        Label narrativeLabel = new Label("Overall Execution Narrative");
        narrativeLabel.getStyleClass().add("subsection-heading");
        TextArea narrativeArea = UiSupport.createArea(fields.getOrDefault("executionSummary.summaryNarrative", ""), 4);
        bindReportField(report.getId(), narrativeArea, "executionSummary.summaryNarrative");

        ListView<ExecutionRunSnapshot> runsList = new ListView<>(FXCollections.observableArrayList(executionSnapshot.getRuns()));
        runsList.setPrefWidth(280);
        runsList.setPrefHeight(420);
        runsList.getStyleClass().add("entity-list");
        VBox runDetailPane = new VBox();
        runDetailPane.setFillWidth(true);
        VBox.setVgrow(runDetailPane, Priority.ALWAYS);

        Runnable addRunAction = () -> {
            try {
                host.getProjectService().createExecutionRun(report.getId());
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                host.markDirty("Execution run added.");
            } catch (Exception exception) {
                host.showError("Unable to add execution run", exception.getMessage());
            }
        };
        Consumer<ExecutionRunSnapshot> removeRunAction = selected -> {
            if (selected == null) {
                host.showInformation("Execution Summary", "Select an execution run to remove.");
                return;
            }
            try {
                host.getProjectService().deleteExecutionRun(report.getId(), selected.getRun().getId());
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                host.markDirty("Execution run removed.");
            } catch (Exception exception) {
                host.showError("Unable to remove execution run", exception.getMessage());
            }
        };

        runsList.setCellFactory(list -> {
            MenuItem addRunItem = UiSupport.createMenuItem("Add Run", "fas-plus", addRunAction);
            MenuItem removeRunItem = UiSupport.createMenuItem("Remove Run", "fas-trash", null);
            ContextMenu runItemContextMenu = UiSupport.themedContextMenu(
                    host,
                    addRunItem,
                    new SeparatorMenuItem(),
                    removeRunItem
            );

            ListCell<ExecutionRunSnapshot> cell = new ListCell<>() {
                @Override
                protected void updateItem(ExecutionRunSnapshot item, boolean empty) {
                    super.updateItem(item, empty);
                    boolean hasItem = !empty && item != null;
                    setText(hasItem ? item.getRun().getDisplayLabel() : null);
                    removeRunItem.setDisable(!hasItem);
                    setContextMenu(runItemContextMenu);
                }
            };

            removeRunItem.setOnAction(event -> {
                ExecutionRunSnapshot selected = cell.getItem();
                if (selected != null) {
                    runsList.getSelectionModel().select(selected);
                }
                removeRunAction.accept(selected);
            });

            cell.setOnContextMenuRequested(event -> {
                if (cell.isEmpty()) {
                    runsList.getSelectionModel().clearSelection();
                } else {
                    runsList.getSelectionModel().select(cell.getItem());
                }
            });
            return cell;
        });

        runsList.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (selected == null) {
                Label placeholder = createPlaceholderLabel("Select an execution run to view its row table and details.");
                runDetailPane.getChildren().setAll(placeholder);
                VBox.setVgrow(placeholder, Priority.ALWAYS);
                return;
            }
            Node runEditor = buildExecutionRunEditor(report, selected);
            runDetailPane.getChildren().setAll(runEditor);
            VBox.setVgrow(runEditor, Priority.ALWAYS);
        });

        Button addRunButton = createSecondaryButton("Add Run", "fas-plus");
        addRunButton.setOnAction(event -> addRunAction.run());
        Button removeRunButton = createSecondaryButton("Remove Run", "fas-trash");
        removeRunButton.setOnAction(event -> removeRunAction.accept(runsList.getSelectionModel().getSelectedItem()));
        HBox runActions = createInlineActions(addRunButton, removeRunButton);

        VBox runListPane = new VBox(
                12,
                new Label("Execution Runs"),
                createSupportLabel("Each run groups a batch, suite, or spreadsheet block of case results."),
                runsList,
                runActions
        );
        ((Label) runListPane.getChildren().get(0)).getStyleClass().add("subsection-heading");
        VBox.setVgrow(runsList, Priority.ALWAYS);

        SplitPane runsSplit = new SplitPane(runListPane, runDetailPane);
        runsSplit.setDividerPositions(0.22);
        runsSplit.getStyleClass().add("editor-split-pane");
        runsSplit.setPrefHeight(760);

        if (executionSnapshot.getRuns().isEmpty()) {
            Label placeholder = createPlaceholderLabel("No execution runs yet. Add one to begin entering results.");
            runDetailPane.getChildren().setAll(placeholder);
            VBox.setVgrow(placeholder, Priority.ALWAYS);
        } else {
            ExecutionRunSnapshot initialSelection = executionSnapshot.getRuns().stream()
                    .filter(runSnapshot -> java.util.Objects.equals(runSnapshot.getRun().getId(), initialRunId))
                    .findFirst()
                    .orElse(executionSnapshot.getRuns().getFirst());
            runsList.getSelectionModel().select(initialSelection);
        }

        content.getChildren().addAll(
                summaryHeading,
                summaryHint,
                createExecutionMetricsGrid(executionSnapshot.getMetrics()),
                narrativeLabel,
                narrativeArea,
                runsSplit
        );
        content.setFillWidth(true);
        VBox.setVgrow(runsSplit, Priority.ALWAYS);
        return content;
    }

    private Node buildExecutionRunEditor(ReportRecord report, ExecutionRunSnapshot runSnapshot) {
        ExecutionRunRecord run = runSnapshot.getRun();

        Label heading = new Label(run.getDisplayLabel());
        heading.getStyleClass().add("panel-heading");

        Label hint = createSupportLabel("Capture one execution run here, then add the row-based test case results underneath.");

        TextField executionIdField = new TextField(nullToEmpty(run.getExecutionKey()));
        TextField suiteNameField = new TextField(nullToEmpty(run.getSuiteName()));
        TextField executedByField = new TextField(nullToEmpty(run.getExecutedBy()));
        DatePicker executionDatePicker = new DatePicker(parseOptionalDate(run.getExecutionDate()));
        DatePicker startDatePicker = new DatePicker(parseOptionalDate(run.getStartDate()));
        DatePicker endDatePicker = new DatePicker(parseOptionalDate(run.getEndDate()));
        TextField durationField = new TextField(nullToEmpty(run.getDurationText()));
        TextField dataSourceField = new TextField(nullToEmpty(run.getDataSourceReference()));
        TextArea notesArea = UiSupport.createArea(nullToEmpty(run.getNotes()), 3);

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
        executionDatePicker.valueProperty().addListener((observable, previous, value) -> saveRun.run());
        startDatePicker.valueProperty().addListener((observable, previous, value) -> saveRun.run());
        endDatePicker.valueProperty().addListener((observable, previous, value) -> saveRun.run());

        GridPane runGrid = createFormGrid();
        runGrid.addRow(0, new Label("Execution ID"), executionIdField);
        runGrid.addRow(1, new Label("Suite / Cycle Name"), suiteNameField);
        runGrid.addRow(2, new Label("Executed By"), executedByField);
        runGrid.addRow(3, new Label("Execution Date"), executionDatePicker);
        runGrid.addRow(4, new Label("Execution Start Date"), startDatePicker);
        runGrid.addRow(5, new Label("Execution End Date"), endDatePicker);
        runGrid.addRow(6, new Label("Duration"), durationField);
        runGrid.addRow(7, new Label("Data Source / Reference"), dataSourceField);
        runGrid.addRow(8, new Label("Run Notes"), notesArea);

        Label runSummaryHeading = new Label("Run Summary");
        runSummaryHeading.getStyleClass().add("subsection-heading");
        Node runSummaryGrid = createExecutionMetricsGrid(runSnapshot.getMetrics());

        if (hasLegacyMetrics(run)) {
            Label legacyNote = createSupportLabel(
                    "This run contains migrated legacy summary totals. Add case rows to replace legacy-only metrics with structured detail."
            );
            runSummaryGrid = new VBox(8, runSummaryGrid, legacyNote);
        }

        TableView<TestCaseResultSnapshot> resultsTable = new TableView<>(FXCollections.observableArrayList(runSnapshot.getTestCaseResults()));
        resultsTable.getStyleClass().add("entity-list");
        resultsTable.setPlaceholder(createPlaceholderLabel("No test case rows yet. Add one to start capturing execution results."));
        configureExecutionResultsTable(resultsTable, false);

        CheckBox fullSpreadsheetToggle = new CheckBox("Full spreadsheet view");
        fullSpreadsheetToggle.selectedProperty().addListener((observable, previous, selected) -> {
            TestCaseResultSnapshot currentSelection = resultsTable.getSelectionModel().getSelectedItem();
            configureExecutionResultsTable(resultsTable, selected);
            if (currentSelection != null) {
                resultsTable.getSelectionModel().select(currentSelection);
            }
        });

        Button addCaseButton = createSecondaryButton("Add Test Case", "fas-plus");
        Button removeCaseButton = createSecondaryButton("Remove Test Case", "fas-trash");
        HBox caseActions = createInlineActions(addCaseButton, removeCaseButton);

        VBox caseDetailPane = new VBox(12);
        caseDetailPane.setPadding(new Insets(20));
        caseDetailPane.setFillWidth(true);
        caseDetailPane.setMinWidth(0);

        Runnable showEmptyCaseSelection = () -> caseDetailPane.getChildren().setAll(
                createSectionSubheading("Selected Row Details"),
                createSupportLabel("All row editing happens in this panel, including nested step results."),
                createPlaceholderLabel("Select a test case row to edit its detailed fields and steps.")
        );

        resultsTable.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (selected == null) {
                showEmptyCaseSelection.run();
                return;
            }
            caseDetailPane.getChildren().setAll(
                    createSectionSubheading("Selected Row Details"),
                    createSupportLabel("Edit the selected row here. Step-level expected, actual, and status details stay in this panel."),
                    buildTestCaseResultEditor(report, selected)
            );
        });

        addCaseButton.setOnAction(event -> {
            try {
                host.getProjectService().createTestCaseResult(report.getId(), run.getId());
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                host.markDirty("Test case result added.");
            } catch (Exception exception) {
                host.showError("Unable to add test case result", exception.getMessage());
            }
        });
        removeCaseButton.setOnAction(event -> {
            TestCaseResultSnapshot selected = resultsTable.getSelectionModel().getSelectedItem();
            if (selected == null) {
                host.showInformation("Execution Summary", "Select a test case result to remove.");
                return;
            }
            try {
                host.getProjectService().deleteTestCaseResult(report.getId(), selected.getResult().getId());
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                host.markDirty("Test case result removed.");
            } catch (Exception exception) {
                host.showError("Unable to remove test case result", exception.getMessage());
            }
        });

        VBox caseListPane = new VBox(
                12,
                heading,
                hint,
                runGrid,
                runSummaryHeading,
                runSummaryGrid,
                createSectionSubheading("Case Results"),
                createSupportLabel("Browse the selected execution's rows in the center table. Select a row to edit its details and steps in the right-side panel."),
                createSupportLabel("Compact view keeps the table scan-friendly. Turn on full spreadsheet view when you need the fuller spreadsheet-style columns."),
                fullSpreadsheetToggle,
                resultsTable,
                caseActions
        );
        caseListPane.setPadding(new Insets(20));
        caseListPane.setFillWidth(true);
        caseListPane.setMinWidth(0);
        VBox.setVgrow(resultsTable, Priority.ALWAYS);

        SplitPane resultsSplit = new SplitPane(caseListPane, UiSupport.wrapInScrollPane(caseDetailPane));
        resultsSplit.setDividerPositions(0.58);
        resultsSplit.getStyleClass().add("editor-split-pane");
        resultsSplit.setPrefHeight(680);

        if (runSnapshot.getTestCaseResults().isEmpty()) {
            showEmptyCaseSelection.run();
        } else {
            resultsTable.getSelectionModel().selectFirst();
        }
        return resultsSplit;
    }

    private Node buildTestCaseResultEditor(ReportRecord report, TestCaseResultSnapshot resultSnapshot) {
        TestCaseResultRecord result = resultSnapshot.getResult();

        Label heading = new Label(result.getDisplayLabel());
        heading.getStyleClass().add("subsection-heading");

        TextField testCaseIdField = new TextField(nullToEmpty(result.getTestCaseKey()));
        TextField sectionField = new TextField(nullToEmpty(result.getSectionName()));
        TextField subsectionField = new TextField(nullToEmpty(result.getSubsectionName()));
        TextField testCaseNameField = new TextField(nullToEmpty(result.getTestCaseName()));
        TextField priorityField = new TextField(nullToEmpty(result.getPriority()));
        TextField moduleField = new TextField(nullToEmpty(result.getModuleName()));
        ComboBox<String> statusCombo = new ComboBox<>(FXCollections.observableArrayList("PASS", "FAIL", "BLOCKED", "NOT_RUN", "DEFERRED", "SKIPPED"));
        statusCombo.setValue(
                normalizeResultStatus(result.getStatus()).isBlank()
                        ? "NOT_RUN"
                        : normalizeResultStatus(result.getStatus())
        );
        TextField executionTimeField = new TextField(nullToEmpty(result.getExecutionTime()));
        TextField relatedIssueField = new TextField(nullToEmpty(result.getRelatedIssue()));
        TextField attachmentField = new TextField(nullToEmpty(result.getAttachmentReference()));
        TextArea expectedSummaryArea = UiSupport.createArea(nullToEmpty(result.getExpectedResultSummary()), 2);
        TextArea actualResultArea = UiSupport.createArea(nullToEmpty(result.getActualResult()), 3);
        TextArea blockedReasonArea = UiSupport.createArea(nullToEmpty(result.getBlockedReason()), 2);
        TextArea remarksArea = UiSupport.createArea(nullToEmpty(result.getRemarks()), 3);

        Runnable saveResult = () -> {
            TestCaseResultRecord updatedResult = new TestCaseResultRecord(
                    result.getId(),
                    result.getExecutionRunId(),
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
                    attachmentField.getText(),
                    remarksArea.getText(),
                    blockedReasonArea.getText(),
                    result.getSortOrder(),
                    result.getCreatedAt(),
                    result.getUpdatedAt()
            );
            try {
                host.getProjectService().updateTestCaseResult(report.getId(), updatedResult);
                host.markDirty("Test case result updated.");
            } catch (Exception exception) {
                host.showError("Unable to update test case result", exception.getMessage());
            }
        };

        commitOnFocusLost(testCaseIdField, value -> saveResult.run());
        commitOnFocusLost(sectionField, value -> saveResult.run());
        commitOnFocusLost(subsectionField, value -> saveResult.run());
        commitOnFocusLost(testCaseNameField, value -> saveResult.run());
        commitOnFocusLost(priorityField, value -> saveResult.run());
        commitOnFocusLost(moduleField, value -> saveResult.run());
        commitOnFocusLost(executionTimeField, value -> saveResult.run());
        commitOnFocusLost(relatedIssueField, value -> saveResult.run());
        commitOnFocusLost(attachmentField, value -> saveResult.run());
        commitOnFocusLost(expectedSummaryArea, value -> saveResult.run());
        commitOnFocusLost(actualResultArea, value -> saveResult.run());
        commitOnFocusLost(blockedReasonArea, value -> saveResult.run());
        commitOnFocusLost(remarksArea, value -> saveResult.run());
        statusCombo.setOnAction(event -> saveResult.run());

        GridPane resultGrid = createFormGrid();
        resultGrid.addRow(0, new Label("Test Case ID"), testCaseIdField);
        resultGrid.addRow(1, new Label("Section"), sectionField);
        resultGrid.addRow(2, new Label("Subsection"), subsectionField);
        resultGrid.addRow(3, new Label("Test Case Name"), testCaseNameField);
        resultGrid.addRow(4, new Label("Priority"), priorityField);
        resultGrid.addRow(5, new Label("Module / Component"), moduleField);
        resultGrid.addRow(6, new Label("Status"), statusCombo);
        resultGrid.addRow(7, new Label("Execution Time"), executionTimeField);
        resultGrid.addRow(8, new Label("Related Issue"), relatedIssueField);
        resultGrid.addRow(9, new Label("Attachment / Evidence"), attachmentField);
        resultGrid.addRow(10, new Label("Expected Result Summary"), expectedSummaryArea);
        resultGrid.addRow(11, new Label("Actual Result"), actualResultArea);
        resultGrid.addRow(12, new Label("Blocked Reason"), blockedReasonArea);
        resultGrid.addRow(13, new Label("Remarks"), remarksArea);

        TableView<TestCaseStepRecord> stepsTable = new TableView<>(FXCollections.observableArrayList(resultSnapshot.getSteps()));
        stepsTable.getStyleClass().add("entity-list");
        stepsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        stepsTable.getColumns().setAll(List.of(
                stringColumn("Step #", 70, step -> step.getStepNumber() == null ? "" : Integer.toString(step.getStepNumber())),
                stringColumn("Step", 220, TestCaseStepRecord::getStepAction),
                stringColumn("Expected", 220, TestCaseStepRecord::getExpectedResult),
                stringColumn("Actual", 220, TestCaseStepRecord::getActualResult),
                stringColumn("Status", 100, step -> normalizeResultStatus(step.getStatus()))
        ));

        Button addStepButton = createSecondaryButton("Add Step", "fas-plus");
        Button removeStepButton = createSecondaryButton("Remove Step", "fas-trash");
        HBox stepActions = createInlineActions(addStepButton, removeStepButton);

        VBox stepDetailPane = new VBox(12);
        stepDetailPane.setPadding(new Insets(12, 0, 0, 0));

        stepsTable.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (selected == null) {
                stepDetailPane.getChildren().setAll(createPlaceholderLabel("Select a step to edit its expected and actual results."));
                return;
            }
            stepDetailPane.getChildren().setAll(buildTestCaseStepEditor(report, selected));
        });

        addStepButton.setOnAction(event -> {
            try {
                host.getProjectService().createTestCaseStep(report.getId(), result.getId());
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                host.markDirty("Test step added.");
            } catch (Exception exception) {
                host.showError("Unable to add test step", exception.getMessage());
            }
        });
        removeStepButton.setOnAction(event -> {
            TestCaseStepRecord selected = stepsTable.getSelectionModel().getSelectedItem();
            if (selected == null) {
                host.showInformation("Execution Summary", "Select a test step to remove.");
                return;
            }
            try {
                host.getProjectService().deleteTestCaseStep(report.getId(), selected.getId());
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                host.markDirty("Test step removed.");
            } catch (Exception exception) {
                host.showError("Unable to remove test step", exception.getMessage());
            }
        });

        if (resultSnapshot.getSteps().isEmpty()) {
            stepDetailPane.getChildren().setAll(createPlaceholderLabel("No step rows yet. Add one if this case needs detailed traceability."));
        } else {
            stepsTable.getSelectionModel().selectFirst();
        }

        VBox content = new VBox(
                12,
                heading,
                resultGrid,
                createSectionSubheading("Step Results"),
                createSupportLabel("Use step rows when a case needs detailed expected vs actual traceability."),
                stepsTable,
                stepActions,
                stepDetailPane
        );
        content.setFillWidth(true);
        return content;
    }

    private Node buildTestCaseStepEditor(ReportRecord report, TestCaseStepRecord step) {
        TextField stepNumberField = UiSupport.integerField(step.getStepNumber() == null ? "" : Integer.toString(step.getStepNumber()));
        TextArea stepActionArea = UiSupport.createArea(nullToEmpty(step.getStepAction()), 2);
        TextArea expectedArea = UiSupport.createArea(nullToEmpty(step.getExpectedResult()), 2);
        TextArea actualArea = UiSupport.createArea(nullToEmpty(step.getActualResult()), 2);
        ComboBox<String> statusCombo = new ComboBox<>(FXCollections.observableArrayList("PASS", "FAIL", "BLOCKED", "NOT_RUN", "DEFERRED", "SKIPPED"));
        statusCombo.setValue(
                normalizeResultStatus(step.getStatus()).isBlank()
                        ? "NOT_RUN"
                        : normalizeResultStatus(step.getStatus())
        );

        Runnable saveStep = () -> {
            TestCaseStepRecord updatedStep = new TestCaseStepRecord(
                    step.getId(),
                    step.getTestCaseResultId(),
                    parseOptionalInteger(stepNumberField.getText()),
                    stepActionArea.getText(),
                    expectedArea.getText(),
                    actualArea.getText(),
                    statusCombo.getValue(),
                    step.getSortOrder(),
                    step.getCreatedAt(),
                    step.getUpdatedAt()
            );
            try {
                host.getProjectService().updateTestCaseStep(report.getId(), updatedStep);
                host.markDirty("Test step updated.");
            } catch (Exception exception) {
                host.showError("Unable to update test step", exception.getMessage());
            }
        };

        commitOnFocusLost(stepNumberField, value -> saveStep.run());
        commitOnFocusLost(stepActionArea, value -> saveStep.run());
        commitOnFocusLost(expectedArea, value -> saveStep.run());
        commitOnFocusLost(actualArea, value -> saveStep.run());
        statusCombo.setOnAction(event -> saveStep.run());

        GridPane gridPane = createFormGrid();
        gridPane.addRow(0, new Label("Step Number"), stepNumberField);
        gridPane.addRow(1, new Label("Step"), stepActionArea);
        gridPane.addRow(2, new Label("Expected Result"), expectedArea);
        gridPane.addRow(3, new Label("Actual Result"), actualArea);
        gridPane.addRow(4, new Label("Status"), statusCombo);
        return gridPane;
    }

    private Node buildDetailedResultsSection(ExecutionReportSnapshot executionSnapshot) {
        VBox content = new VBox(16);
        content.getChildren().add(UiSupport.sectionHeading(TestExecutionSection.DETAILED_TEST_RESULTS));
        content.getChildren().add(createSupportLabel("This section is generated from the structured execution runs, case rows, and step results."));

        if (executionSnapshot.getRuns().isEmpty()) {
            content.getChildren().add(createPlaceholderLabel("No execution runs or case results are available yet."));
            return UiSupport.wrapInScrollPane(content);
        }

        for (ExecutionRunSnapshot runSnapshot : executionSnapshot.getRuns()) {
            VBox runBlock = new VBox(10);
            Label runHeading = new Label(runSnapshot.getRun().getDisplayLabel());
            runHeading.getStyleClass().add("subsection-heading");
            Label runMeta = createSupportLabel(
                    "Executed by: " + valueOrFallback(runSnapshot.getRun().getExecutedBy())
                            + " | Date: " + valueOrFallback(firstNonBlank(
                            runSnapshot.getRun().getExecutionDate(),
                            runSnapshot.getRun().getStartDate(),
                            runSnapshot.getRun().getEndDate()
                    ))
                            + " | Outcome: " + runSnapshot.getMetrics().getOverallOutcome()
            );
            runBlock.getChildren().addAll(runHeading, runMeta);

            for (TestCaseResultSnapshot resultSnapshot : runSnapshot.getTestCaseResults()) {
                runBlock.getChildren().add(buildDetailedCaseCard(resultSnapshot));
            }

            content.getChildren().add(runBlock);
        }

        return UiSupport.wrapInScrollPane(content);
    }

    private Node buildDefectSummarySection(ExecutionReportSnapshot executionSnapshot) {
        VBox content = new VBox(12);
        content.getChildren().add(UiSupport.sectionHeading(TestExecutionSection.DEFECT_SUMMARY));
        content.getChildren().add(createSupportLabel("Cases with linked issues or fail/blocked outcomes appear here automatically."));

        List<TestCaseResultSnapshot> issueRows = new ArrayList<>();
        for (ExecutionRunSnapshot runSnapshot : executionSnapshot.getRuns()) {
            for (TestCaseResultSnapshot resultSnapshot : runSnapshot.getTestCaseResults()) {
                String status = normalizeResultStatus(resultSnapshot.getResult().getStatus());
                if (!nullToEmpty(resultSnapshot.getResult().getRelatedIssue()).isBlank()
                        || "FAIL".equals(status)
                        || "BLOCKED".equals(status)) {
                    issueRows.add(resultSnapshot);
                }
            }
        }

        if (issueRows.isEmpty()) {
            content.getChildren().add(createPlaceholderLabel("No derived defect or issue rows yet."));
            return UiSupport.wrapInScrollPane(content);
        }

        TableView<TestCaseResultSnapshot> table = new TableView<>(FXCollections.observableArrayList(issueRows));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().setAll(List.of(
                stringColumn("TC ID", 110, row -> row.getResult().getTestCaseKey()),
                stringColumn("Test Case", 240, row -> row.getResult().getTestCaseName()),
                stringColumn("Status", 100, row -> normalizeResultStatus(row.getResult().getStatus())),
                stringColumn("Related Issue", 150, row -> row.getResult().getRelatedIssue()),
                stringColumn("Actual Result", 260, row -> row.getResult().getActualResult()),
                stringColumn("Blocked Reason", 220, row -> row.getResult().getBlockedReason())
        ));

        content.getChildren().add(table);
        return UiSupport.wrapInScrollPane(content);
    }

    private Node buildEvidenceSection(ExecutionReportSnapshot executionSnapshot) {
        VBox content = new VBox(12);
        content.getChildren().add(UiSupport.sectionHeading(TestExecutionSection.TEST_EVIDENCE));
        content.getChildren().add(createSupportLabel("Evidence rows are derived from case-level attachment references."));

        List<TestCaseResultSnapshot> evidenceRows = new ArrayList<>();
        for (ExecutionRunSnapshot runSnapshot : executionSnapshot.getRuns()) {
            for (TestCaseResultSnapshot resultSnapshot : runSnapshot.getTestCaseResults()) {
                if (!nullToEmpty(resultSnapshot.getResult().getAttachmentReference()).isBlank()) {
                    evidenceRows.add(resultSnapshot);
                }
            }
        }

        if (evidenceRows.isEmpty()) {
            content.getChildren().add(createPlaceholderLabel("No evidence references have been entered yet."));
            return UiSupport.wrapInScrollPane(content);
        }

        TableView<TestCaseResultSnapshot> table = new TableView<>(FXCollections.observableArrayList(evidenceRows));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().setAll(List.of(
                stringColumn("TC ID", 110, row -> row.getResult().getTestCaseKey()),
                stringColumn("Test Case", 240, row -> row.getResult().getTestCaseName()),
                stringColumn("Status", 100, row -> normalizeResultStatus(row.getResult().getStatus())),
                stringColumn("Attachment / Evidence", 260, row -> row.getResult().getAttachmentReference()),
                stringColumn("Remarks", 220, row -> row.getResult().getRemarks())
        ));

        content.getChildren().add(table);
        return UiSupport.wrapInScrollPane(content);
    }

    private Node buildDetailedCaseCard(TestCaseResultSnapshot resultSnapshot) {
        VBox card = new VBox(10);
        Label heading = new Label(resultSnapshot.getResult().getDisplayLabel());
        heading.getStyleClass().add("subsection-heading");
        Label meta = createSupportLabel(
                "Status: " + normalizeResultStatus(resultSnapshot.getResult().getStatus())
                        + " | Priority: " + valueOrFallback(resultSnapshot.getResult().getPriority())
                        + " | Module: " + valueOrFallback(resultSnapshot.getResult().getModuleName())
                        + " | Execution Time: " + valueOrFallback(resultSnapshot.getResult().getExecutionTime())
        );

        GridPane gridPane = createFormGrid();
        gridPane.addRow(0, new Label("Section"), UiSupport.readOnlyField(valueOrFallback(resultSnapshot.getResult().getSectionName())));
        gridPane.addRow(1, new Label("Subsection"), UiSupport.readOnlyField(valueOrFallback(resultSnapshot.getResult().getSubsectionName())));
        gridPane.addRow(2, new Label("Expected Result Summary"), readOnlyArea(valueOrFallback(resultSnapshot.getResult().getExpectedResultSummary()), 2));
        gridPane.addRow(3, new Label("Actual Result"), readOnlyArea(valueOrFallback(resultSnapshot.getResult().getActualResult()), 2));
        gridPane.addRow(4, new Label("Related Issue"), UiSupport.readOnlyField(valueOrFallback(resultSnapshot.getResult().getRelatedIssue())));
        gridPane.addRow(5, new Label("Attachment"), UiSupport.readOnlyField(valueOrFallback(resultSnapshot.getResult().getAttachmentReference())));
        gridPane.addRow(6, new Label("Blocked Reason"), readOnlyArea(valueOrFallback(resultSnapshot.getResult().getBlockedReason()), 2));
        gridPane.addRow(7, new Label("Remarks"), readOnlyArea(valueOrFallback(resultSnapshot.getResult().getRemarks()), 2));

        card.getChildren().addAll(heading, meta, gridPane);

        if (!resultSnapshot.getSteps().isEmpty()) {
            TableView<TestCaseStepRecord> stepsTable = new TableView<>(FXCollections.observableArrayList(resultSnapshot.getSteps()));
            stepsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            stepsTable.getColumns().setAll(List.of(
                    stringColumn("Step #", 70, step -> step.getStepNumber() == null ? "" : Integer.toString(step.getStepNumber())),
                    stringColumn("Step", 220, TestCaseStepRecord::getStepAction),
                    stringColumn("Expected", 220, TestCaseStepRecord::getExpectedResult),
                    stringColumn("Actual", 220, TestCaseStepRecord::getActualResult),
                    stringColumn("Status", 100, step -> normalizeResultStatus(step.getStatus()))
            ));
            card.getChildren().addAll(createSectionSubheading("Step Results"), stepsTable);
        }

        return card;
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

    private Node createExecutionMetricsGrid(ExecutionMetrics metrics) {
        GridPane gridPane = createFormGrid();
        gridPane.addRow(0, new Label("Execution Runs"), UiSupport.readOnlyField(Integer.toString(metrics.getExecutionRunCount())));
        gridPane.addRow(1, new Label("Total Test Cases"), UiSupport.readOnlyField(Integer.toString(metrics.getTestCaseCount())));
        gridPane.addRow(2, new Label("Passed"), UiSupport.readOnlyField(Integer.toString(metrics.getPassedCount())));
        gridPane.addRow(3, new Label("Failed"), UiSupport.readOnlyField(Integer.toString(metrics.getFailedCount())));
        gridPane.addRow(4, new Label("Blocked"), UiSupport.readOnlyField(Integer.toString(metrics.getBlockedCount())));
        gridPane.addRow(5, new Label("Not Run"), UiSupport.readOnlyField(Integer.toString(metrics.getNotRunCount())));
        gridPane.addRow(6, new Label("Deferred"), UiSupport.readOnlyField(Integer.toString(metrics.getDeferredCount())));
        gridPane.addRow(7, new Label("Skipped"), UiSupport.readOnlyField(Integer.toString(metrics.getSkippedCount())));
        gridPane.addRow(8, new Label("Linked Issues"), UiSupport.readOnlyField(Integer.toString(metrics.getIssueCount())));
        gridPane.addRow(9, new Label("Evidence References"), UiSupport.readOnlyField(Integer.toString(metrics.getEvidenceCount())));
        gridPane.addRow(10, new Label("Overall Outcome"), UiSupport.readOnlyField(metrics.getOverallOutcome()));
        gridPane.addRow(11, new Label("Pass Rate %"), UiSupport.readOnlyField(calculatePassRate(metrics)));
        gridPane.addRow(12, new Label("Earliest Date"), UiSupport.readOnlyField(valueOrFallback(metrics.getEarliestDate())));
        gridPane.addRow(13, new Label("Latest Date"), UiSupport.readOnlyField(valueOrFallback(metrics.getLatestDate())));
        return gridPane;
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

    private Label createPlaceholderLabel(String text) {
        Label label = createSupportLabel(text);
        label.setMinHeight(120);
        return label;
    }

    private void configureExecutionResultsTable(TableView<TestCaseResultSnapshot> table, boolean fullSpreadsheetView) {
        table.setColumnResizePolicy(
                fullSpreadsheetView
                        ? TableView.UNCONSTRAINED_RESIZE_POLICY
                        : TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        );

        List<TableColumn<TestCaseResultSnapshot, String>> columns = new ArrayList<>();
        columns.add(stringColumn("TC ID", 110, result -> result.getResult().getTestCaseKey()));
        columns.add(stringColumn("Section", 110, result -> result.getResult().getSectionName()));
        columns.add(stringColumn("Subsection", 120, result -> result.getResult().getSubsectionName()));
        columns.add(stringColumn("Test Case", 220, result -> result.getResult().getTestCaseName()));
        columns.add(stringColumn("Status", 100, result -> normalizeResultStatus(result.getResult().getStatus())));
        columns.add(stringColumn("Related Issue", 140, result -> result.getResult().getRelatedIssue()));
        columns.add(stringColumn("Evidence", 160, result -> result.getResult().getAttachmentReference()));
        columns.add(stringColumn("Exec Time", 100, result -> result.getResult().getExecutionTime()));

        if (fullSpreadsheetView) {
            columns.add(stringColumn("Expected Summary", 240, result -> result.getResult().getExpectedResultSummary()));
            columns.add(stringColumn("Actual Result", 260, result -> result.getResult().getActualResult()));
            columns.add(stringColumn("Blocked Reason", 220, result -> result.getResult().getBlockedReason()));
            columns.add(stringColumn("Remarks", 220, result -> result.getResult().getRemarks()));
            columns.add(stringColumn("Priority", 110, result -> result.getResult().getPriority()));
            columns.add(stringColumn("Module", 140, result -> result.getResult().getModuleName()));
        }

        table.getColumns().setAll(columns);
    }

    private <T> TableColumn<T, String> stringColumn(String title, double preferredWidth, Function<T, String> extractor) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setPrefWidth(preferredWidth);
        column.setCellValueFactory(cell -> new SimpleStringProperty(nullToEmpty(extractor.apply(cell.getValue()))));
        return column;
    }

    private TextArea readOnlyArea(String value, int rows) {
        TextArea area = UiSupport.createArea(value, rows);
        area.setEditable(false);
        area.setWrapText(true);
        return area;
    }

    private String calculatePassRate(ExecutionMetrics metrics) {
        return UiSupport.calculatePassRate(
                Integer.toString(metrics.getPassedCount()),
                Integer.toString(metrics.getTotalExecuted())
        );
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

    private Integer parseOptionalInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return null;
        }
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
        Map<String, String> fields = host.getProjectService().loadReportFields(report.getId());
        ExecutionReportSnapshot executionSnapshot = host.getProjectService().loadExecutionReportSnapshot(report.getId());

        VBox content = new VBox(12);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("content-card");
        content.setFillWidth(true);

        Label title = new Label(report.getTitle() + " - " + runNode.runSnapshot().getRun().getDisplayLabel());
        title.getStyleClass().add("panel-heading");

        Node executionSummary = buildExecutionSummarySection(
                report,
                fields,
                executionSnapshot,
                runNode.runSnapshot().getRun().getId()
        );
        VBox.setVgrow(executionSummary, Priority.ALWAYS);

        content.getChildren().addAll(title, executionSummary);
        return content;
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
