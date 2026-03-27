package com.buraktok.reportforge.ui;

import com.buraktok.reportforge.model.ApplicationEntry;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ExecutionMetrics;
import com.buraktok.reportforge.model.ExecutionReportSnapshot;
import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.TestExecutionSection;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class ReportSectionEditors {
    @FunctionalInterface
    interface WorkspaceOperation {
        void run() throws IOException, SQLException;
    }

    interface Support {
        void runWorkspaceOperation(String title, WorkspaceOperation operation);

        void bindReportField(String reportId, TextField textField, String fieldKey);

        void bindReportField(String reportId, TextField textField, String fieldKey, Runnable afterSave);

        void bindReportField(String reportId, TextArea textArea, String fieldKey);

        void bindReportField(String reportId, CheckBox checkBox, String fieldKey);

        void bindReportDateField(String reportId, DatePicker datePicker, String fieldKey);

        void commitOnFocusLost(TextField textField, Consumer<String> consumer);

        void commitOnFocusLost(TextArea textArea, Consumer<String> consumer);

        GridPane createFormGrid();

        HBox createInlineActions(Button... buttons);

        Button createSecondaryButton(String text, String iconLiteral);

        Label createSupportLabel(String text);

        Label createSectionSubheading(String text);

        VBox createSummaryPanel(Node... children);

        String valueOrFallback(String value);

        String formatPassRateValue(ExecutionMetrics metrics);

        String formatSummaryDate(String value);

        boolean booleanField(Map<String, String> fields, String fieldKey, boolean defaultValue);
    }

    private final WorkspaceHost host;
    private final Support support;

    ReportSectionEditors(WorkspaceHost host, Support support) {
        this.host = host;
        this.support = support;
    }

    Node buildReportEditor(ReportRecord report) {
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

        BorderPane sectionPane = new BorderPane();
        sectionPane.setPadding(new Insets(0, 0, 0, 16));
        sectionPane.getStyleClass().add("section-pane");

        Consumer<TestExecutionSection> sectionRenderer = section -> support.runWorkspaceOperation(
                "Unable to update report navigation",
                () -> {
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
                }
        );

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
    ) {
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
            case EXPORT_PRESETS -> buildExportPresetsSection(report, fields);
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
                UiSupport.parseDate(fields.getOrDefault("projectOverview.preparedDate", LocalDate.now().toString()))
        );
        TextField audienceField = new TextField(fields.getOrDefault("projectOverview.stakeholderAudience", ""));
        TextField revisionNoteField = new TextField(fields.getOrDefault("projectOverview.revisionNote", ""));

        support.commitOnFocusLost(reportTitleField, value -> support.runWorkspaceOperation(
                "Unable to update report title",
                () -> {
                    host.getProjectService().updateReportTitle(report.getId(), value);
                    host.markDirty("Report title updated.");
                    host.reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                }
        ));
        support.bindReportField(report.getId(), projectDescriptionArea, "projectOverview.projectDescription");
        support.bindReportField(report.getId(), releaseIterationField, "projectOverview.releaseIterationName");
        support.bindReportField(report.getId(), preparedByField, "projectOverview.preparedBy");
        support.bindReportDateField(report.getId(), preparedDatePicker, "projectOverview.preparedDate");
        support.bindReportField(report.getId(), audienceField, "projectOverview.stakeholderAudience");
        support.bindReportField(report.getId(), revisionNoteField, "projectOverview.revisionNote");

        GridPane gridPane = support.createFormGrid();
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

        Button addButton = support.createSecondaryButton("Add", "fas-plus");
        Button editButton = support.createSecondaryButton("Edit", "fas-edit");
        Button removeButton = support.createSecondaryButton("Remove", "fas-trash");
        Button primaryButton = support.createSecondaryButton("Make Primary", "fas-star");
        HBox actions = support.createInlineActions(addButton, editButton, removeButton, primaryButton);

        addButton.setOnAction(event -> {
            var input = ApplicationEntryDialog.show(host, "Add Application", null);
            input.ifPresent(application -> support.runWorkspaceOperation(
                    "Unable to add report application",
                    () -> {
                        host.getProjectService().upsertReportApplication(report.getId(), application);
                        host.reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                        host.markDirty("Report application added.");
                    }
            ));
        });
        editButton.setOnAction(event -> {
            ApplicationEntry selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null) {
                host.showInformation("Applications under Test", "Select an application to edit.");
                return;
            }
            var input = ApplicationEntryDialog.show(host, "Edit Application", selected);
            input.ifPresent(application -> support.runWorkspaceOperation(
                    "Unable to update report application",
                    () -> {
                        host.getProjectService().upsertReportApplication(report.getId(), application);
                        host.reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                        host.markDirty("Report application updated.");
                    }
            ));
        });
        removeButton.setOnAction(event -> {
            ApplicationEntry selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null) {
                host.showInformation("Applications under Test", "Select an application to remove.");
                return;
            }
            support.runWorkspaceOperation("Unable to remove report application", () -> {
                host.getProjectService().deleteReportApplication(selected.getId(), report.getId());
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                host.markDirty("Report application removed.");
            });
        });
        primaryButton.setOnAction(event -> {
            ApplicationEntry selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null) {
                host.showInformation("Applications under Test", "Select an application to mark as primary.");
                return;
            }
            support.runWorkspaceOperation("Unable to update primary report application", () -> {
                host.getProjectService().setPrimaryReportApplication(report.getId(), selected.getId());
                host.reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                host.markDirty("Primary report application updated.");
            });
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

        Consumer<Void> saveAction = ignored -> support.runWorkspaceOperation(
                "Unable to update report environment",
                () -> {
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
                }
        );

        support.commitOnFocusLost(nameField, value -> saveAction.accept(null));
        support.commitOnFocusLost(typeField, value -> saveAction.accept(null));
        support.commitOnFocusLost(urlField, value -> saveAction.accept(null));
        support.commitOnFocusLost(osField, value -> saveAction.accept(null));
        support.commitOnFocusLost(browserField, value -> saveAction.accept(null));
        support.commitOnFocusLost(backendField, value -> saveAction.accept(null));
        support.commitOnFocusLost(notesArea, value -> saveAction.accept(null));

        GridPane gridPane = support.createFormGrid();
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

        support.bindReportField(report.getId(), objectiveSummary, "scope.objectiveSummary");
        support.bindReportField(report.getId(), inScopeItems, "scope.inScopeItems");
        support.bindReportField(report.getId(), outOfScopeItems, "scope.outOfScopeItems");
        support.bindReportField(report.getId(), strategyNote, "scope.testStrategyNote");
        support.bindReportField(report.getId(), assumptions, "scope.assumptions");
        support.bindReportField(report.getId(), dependencies, "scope.dependencies");
        support.bindReportField(report.getId(), entryCriteria, "scope.entryCriteria");
        support.bindReportField(report.getId(), exitCriteria, "scope.exitCriteria");

        GridPane gridPane = support.createFormGrid();
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
        support.bindReportField(report.getId(), narrativeArea, "executionSummary.summaryNarrative");
        VBox narrativePanel = support.createSummaryPanel(
                support.createSectionSubheading("Execution Narrative"),
                support.createSupportLabel("Summarize release readiness, notable risks, and the key takeaways across all execution runs."),
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

    private Node buildCommentsAndNotesSection(ReportRecord report, Map<String, String> fields) {
        VBox content = new VBox(12);
        content.getChildren().add(UiSupport.sectionHeading(TestExecutionSection.COMMENTS_AND_NOTES));

        TextArea notesArea = UiSupport.createArea(fields.getOrDefault("notes.content", ""), 5);
        support.bindReportField(report.getId(), notesArea, "notes.content");

        GridPane gridPane = support.createFormGrid();
        gridPane.addRow(0, new Label("Notes"), notesArea);
        content.getChildren().addAll(
                support.createSupportLabel("These notes stay at the report level."),
                gridPane
        );
        return UiSupport.wrapInScrollPane(content);
    }

    private Node buildExportPresetsSection(ReportRecord report, Map<String, String> fields) {
        VBox content = new VBox(12);
        content.getChildren().add(UiSupport.sectionHeading(TestExecutionSection.EXPORT_PRESETS));
        content.getChildren().add(support.createSupportLabel(
                "These presets currently apply to HTML export. Future exporters can reuse the same per-report settings."
        ));

        CheckBox skipEmptyContentCheck = new CheckBox("Skip empty content");
        skipEmptyContentCheck.setSelected(support.booleanField(fields, "exportPresets.skipEmptyContent", false));
        support.bindReportField(report.getId(), skipEmptyContentCheck, "exportPresets.skipEmptyContent");

        CheckBox includeEvidenceImagesCheck = new CheckBox("Embed evidence images");
        includeEvidenceImagesCheck.setSelected(support.booleanField(fields, "exportPresets.includeEvidenceImages", true));
        support.bindReportField(report.getId(), includeEvidenceImagesCheck, "exportPresets.includeEvidenceImages");

        VBox skipEmptyPanel = support.createSummaryPanel(
                support.createSectionSubheading("Skip Empty Content"),
                support.createSupportLabel("When enabled, HTML export omits empty fields, empty sections, and placeholder messaging instead of surfacing blank content."),
                skipEmptyContentCheck
        );
        VBox evidencePanel = support.createSummaryPanel(
                support.createSectionSubheading("Embed Evidence Images"),
                support.createSupportLabel("When enabled, evidence screenshots are embedded directly in the HTML file. Disable this to keep exports lighter and easier to share."),
                includeEvidenceImagesCheck
        );

        content.getChildren().addAll(skipEmptyPanel, evidencePanel);
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

        support.bindReportField(report.getId(), overallConclusion, "conclusion.overallConclusion");
        support.bindReportField(report.getId(), recommendations, "conclusion.recommendations");
        support.bindReportField(report.getId(), limitations, "conclusion.knownLimitations");
        support.bindReportField(report.getId(), followUp, "conclusion.followUpActions");
        support.bindReportField(report.getId(), openConcerns, "conclusion.openConcerns");

        GridPane gridPane = support.createFormGrid();
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
        support.bindReportField(report.getId(), area, fieldKey);

        GridPane gridPane = support.createFormGrid();
        gridPane.addRow(0, new Label(labelText), area);
        content.getChildren().add(gridPane);
        return UiSupport.wrapInScrollPane(content);
    }

    private Node createExecutionMetricsDashboard(ExecutionMetrics metrics) {
        VBox dashboard = new VBox(14);
        dashboard.getStyleClass().add("summary-dashboard");

        FlowPane primaryCards = createMetricFlowPane("summary-primary-cards");
        primaryCards.getChildren().addAll(
                createMetricCard("Overall Outcome", support.valueOrFallback(metrics.getOverallOutcome()), "fas-signal", "summary-metric-card-primary"),
                createMetricCard("Pass Rate", support.formatPassRateValue(metrics), "fas-percentage", "summary-metric-card-primary"),
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
                createMetricCard("Earliest Date", support.formatSummaryDate(metrics.getEarliestDate()), "far-calendar-alt", "summary-metric-card-meta"),
                createMetricCard("Latest Date", support.formatSummaryDate(metrics.getLatestDate()), "far-calendar-check", "summary-metric-card-meta")
        );

        dashboard.getChildren().addAll(primaryCards, resultCards, metaCards);
        return dashboard;
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
}
