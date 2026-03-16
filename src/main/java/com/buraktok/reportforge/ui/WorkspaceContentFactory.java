package com.buraktok.reportforge.ui;

import com.buraktok.reportforge.model.ApplicationEntry;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.TestExecutionSection;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
        };
    }

    private Node buildProjectDetailsPane() {
        VBox content = new VBox(12);
        content.setPadding(new Insets(8));

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

        Button addButton = createSecondaryButton("Add");
        Button editButton = createSecondaryButton("Edit");
        Button removeButton = createSecondaryButton("Remove");
        Button primaryButton = createSecondaryButton("Make Primary");
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
        content.setPadding(new Insets(8));
        return UiSupport.wrapInScrollPane(content);
    }

    private Node buildEnvironmentPane(EnvironmentRecord environment) {
        VBox content = new VBox(12);
        content.setPadding(new Insets(8));

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

        Button createReportButton = createSecondaryButton("Create Test Execution Report");
        createReportButton.setOnAction(event -> host.createReportForEnvironment(environment));

        content.getChildren().addAll(heading, gridPane, reportsHeading, reportsListView, createReportButton);
        return UiSupport.wrapInScrollPane(content);
    }

    private Node buildReportEditor(ReportRecord report) throws Exception {
        Map<String, String> fields = host.getProjectService().loadReportFields(report.getId());
        List<ApplicationEntry> reportApplications = host.getProjectService().loadReportApplications(report.getId());
        EnvironmentRecord environmentSnapshot = host.getProjectService().loadReportEnvironmentSnapshot(report.getId());

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
        sectionPane.setPadding(new Insets(0, 0, 0, 12));
        sectionPane.getStyleClass().add("section-pane");

        Consumer<TestExecutionSection> sectionRenderer = section -> {
            try {
                host.getProjectService().updateLastSelectedSection(report.getId(), section);
                sectionPane.setCenter(buildSectionEditor(report, section, fields, reportApplications, environmentSnapshot));
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
        content.setPadding(new Insets(8));
        content.getStyleClass().add("content-card");
        return content;
    }

    private Node buildSectionEditor(
            ReportRecord report,
            TestExecutionSection section,
            Map<String, String> fields,
            List<ApplicationEntry> reportApplications,
            EnvironmentRecord environmentSnapshot
    ) {
        return switch (section) {
            case PROJECT_OVERVIEW -> buildProjectOverviewSection(report, fields, environmentSnapshot);
            case APPLICATIONS_UNDER_TEST -> buildReportApplicationsSection(report, reportApplications);
            case TEST_ENVIRONMENT -> buildReportEnvironmentSection(report, environmentSnapshot);
            case TEST_OBJECTIVES_SCOPE -> buildScopeSection(report, fields);
            case BUILD_RELEASE_INFORMATION -> buildSimpleTextSection(report, fields, section, "buildReleaseInfo.content", "Build / Release Information");
            case EXECUTION_SUMMARY -> buildExecutionSummarySection(report, fields);
            case DETAILED_TEST_RESULTS -> buildSimpleTextSection(report, fields, section, "detailedTestResults.content", "Detailed Test Results");
            case DEFECT_SUMMARY -> buildSimpleTextSection(report, fields, section, "defectSummary.content", "Defect Summary");
            case TEST_EVIDENCE -> buildSimpleTextSection(report, fields, section, "testEvidence.content", "Test Evidence");
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

        Button addButton = createSecondaryButton("Add");
        Button editButton = createSecondaryButton("Edit");
        Button removeButton = createSecondaryButton("Remove");
        Button primaryButton = createSecondaryButton("Make Primary");
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

    private Node buildExecutionSummarySection(ReportRecord report, Map<String, String> fields) {
        VBox content = new VBox(12);
        content.getChildren().add(UiSupport.sectionHeading(TestExecutionSection.EXECUTION_SUMMARY));

        DatePicker startDatePicker = new DatePicker(UiSupport.parseDate(fields.get("executionSummary.startDate")));
        DatePicker endDatePicker = new DatePicker(UiSupport.parseDate(fields.get("executionSummary.endDate")));
        TextField totalExecutedField = UiSupport.integerField(fields.getOrDefault("executionSummary.totalExecuted", ""));
        TextField passedField = UiSupport.integerField(fields.getOrDefault("executionSummary.passedCount", ""));
        TextField failedField = UiSupport.integerField(fields.getOrDefault("executionSummary.failedCount", ""));
        TextField blockedField = UiSupport.integerField(fields.getOrDefault("executionSummary.blockedCount", ""));
        TextField notRunField = UiSupport.integerField(fields.getOrDefault("executionSummary.notRunCount", ""));
        TextField deferredField = UiSupport.integerField(fields.getOrDefault("executionSummary.deferredCount", ""));
        TextField skipField = UiSupport.integerField(fields.getOrDefault("executionSummary.skipCount", ""));
        TextField linkedDefectCountField = UiSupport.integerField(fields.getOrDefault("executionSummary.linkedDefectCount", ""));
        TextField cycleNameField = new TextField(fields.getOrDefault("executionSummary.cycleName", ""));
        TextField executedByField = new TextField(fields.getOrDefault("executionSummary.executedBy", ""));
        ComboBox<String> overallOutcomeCombo = new ComboBox<>(FXCollections.observableArrayList("NOT_EXECUTED", "PASS", "FAIL", "BLOCKED", "MIXED"));
        overallOutcomeCombo.setValue(fields.getOrDefault("executionSummary.overallOutcome", "NOT_EXECUTED"));
        TextField passRateField = UiSupport.readOnlyField(
                UiSupport.calculatePassRate(passedField.getText(), totalExecutedField.getText())
        );
        TextField executionWindowField = new TextField(fields.getOrDefault("executionSummary.executionWindow", ""));
        TextField dataSourceField = new TextField(fields.getOrDefault("executionSummary.dataSourceReference", ""));
        CheckBox blockedExecutionCheckBox = new CheckBox("Blocked Execution Flag");
        blockedExecutionCheckBox.setSelected(Boolean.parseBoolean(fields.getOrDefault("executionSummary.blockedExecutionFlag", "false")));
        TextArea narrativeArea = UiSupport.createArea(fields.getOrDefault("executionSummary.summaryNarrative", ""), 4);

        Runnable updatePassRate = () -> {
            String calculated = UiSupport.calculatePassRate(passedField.getText(), totalExecutedField.getText());
            passRateField.setText(calculated);
            try {
                host.getProjectService().updateReportField(report.getId(), "executionSummary.passRatePercent", calculated);
                host.markDirty("Execution summary updated.");
            } catch (Exception exception) {
                host.showError("Unable to update pass rate", exception.getMessage());
            }
        };

        bindReportDateField(report.getId(), startDatePicker, "executionSummary.startDate");
        bindReportDateField(report.getId(), endDatePicker, "executionSummary.endDate");
        bindReportField(report.getId(), totalExecutedField, "executionSummary.totalExecuted", updatePassRate);
        bindReportField(report.getId(), passedField, "executionSummary.passedCount", updatePassRate);
        bindReportField(report.getId(), failedField, "executionSummary.failedCount");
        bindReportField(report.getId(), blockedField, "executionSummary.blockedCount");
        bindReportField(report.getId(), notRunField, "executionSummary.notRunCount");
        bindReportField(report.getId(), deferredField, "executionSummary.deferredCount");
        bindReportField(report.getId(), skipField, "executionSummary.skipCount");
        bindReportField(report.getId(), linkedDefectCountField, "executionSummary.linkedDefectCount");
        bindReportField(report.getId(), cycleNameField, "executionSummary.cycleName");
        bindReportField(report.getId(), executedByField, "executionSummary.executedBy");
        bindReportField(report.getId(), executionWindowField, "executionSummary.executionWindow");
        bindReportField(report.getId(), dataSourceField, "executionSummary.dataSourceReference");
        bindReportField(report.getId(), narrativeArea, "executionSummary.summaryNarrative");
        overallOutcomeCombo.setOnAction(event -> saveReportField(
                report.getId(),
                "executionSummary.overallOutcome",
                overallOutcomeCombo.getValue(),
                "Execution summary updated."
        ));
        blockedExecutionCheckBox.selectedProperty().addListener((observable, previous, selected) ->
                saveReportField(
                        report.getId(),
                        "executionSummary.blockedExecutionFlag",
                        Boolean.toString(selected),
                        "Execution summary updated."
                ));

        GridPane gridPane = createFormGrid();
        gridPane.addRow(0, new Label("Execution Start Date"), startDatePicker);
        gridPane.addRow(1, new Label("Execution End Date"), endDatePicker);
        gridPane.addRow(2, new Label("Total Tests Executed"), totalExecutedField);
        gridPane.addRow(3, new Label("Passed Count"), passedField);
        gridPane.addRow(4, new Label("Failed Count"), failedField);
        gridPane.addRow(5, new Label("Blocked Count"), blockedField);
        gridPane.addRow(6, new Label("Not Run Count"), notRunField);
        gridPane.addRow(7, new Label("Deferred Count"), deferredField);
        gridPane.addRow(8, new Label("Skip / Not Applicable Count"), skipField);
        gridPane.addRow(9, new Label("Linked Defect Count"), linkedDefectCountField);
        gridPane.addRow(10, new Label("Execution Cycle Name"), cycleNameField);
        gridPane.addRow(11, new Label("Executed By"), executedByField);
        gridPane.addRow(12, new Label("Overall Outcome"), overallOutcomeCombo);
        gridPane.addRow(13, new Label("Pass Rate %"), passRateField);
        gridPane.addRow(14, new Label("Execution Window / Time Zone"), executionWindowField);
        gridPane.addRow(15, new Label("Data Source / Snapshot Reference"), dataSourceField);
        gridPane.addRow(16, new Label("Execution Summary Narrative"), narrativeArea);
        gridPane.addRow(17, new Label("Flags"), blockedExecutionCheckBox);

        content.getChildren().add(gridPane);
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

    private Button createSecondaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().addAll("app-button", "secondary-button");
        return button;
    }
}
