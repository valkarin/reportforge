package com.buraktok.reportforge;

import com.buraktok.reportforge.model.ApplicationEntry;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ProjectWorkspace;
import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.ReportStatus;
import com.buraktok.reportforge.persistence.ProjectContainerService;
import com.buraktok.reportforge.persistence.RecentProjectsService;
import com.buraktok.reportforge.ui.ApplicationEntryDialog;
import com.buraktok.reportforge.ui.EnvironmentSelectionDialog;
import com.buraktok.reportforge.ui.FontSupport;
import com.buraktok.reportforge.ui.NewProjectDialog;
import com.buraktok.reportforge.ui.StartScreenView;
import com.buraktok.reportforge.ui.ThemeMode;
import com.buraktok.reportforge.ui.UiSupport;
import com.buraktok.reportforge.ui.WorkspaceContentFactory;
import com.buraktok.reportforge.ui.WorkspaceHost;
import com.buraktok.reportforge.ui.WorkspaceNavigator;
import com.buraktok.reportforge.ui.WorkspaceNode;
import com.buraktok.reportforge.ui.WorkspaceNodeType;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import javafx.scene.paint.Color;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.prefs.Preferences;

public class ReportForgeApplication extends Application {
    private static final String THEME_PREFERENCE_KEY = "themeMode";
    private static final double START_WINDOW_WIDTH = 960;
    private static final double START_WINDOW_HEIGHT = 640;
    private static final double PROJECT_WINDOW_WIDTH = 1400;
    private static final double PROJECT_WINDOW_HEIGHT = 900;
    private static final double PROJECT_WINDOW_MIN_WIDTH = 1180;
    private static final double PROJECT_WINDOW_MIN_HEIGHT = 760;

    private final ProjectContainerService projectService = new ProjectContainerService();
    private final RecentProjectsService recentProjectsService = new RecentProjectsService();
    private final Preferences preferences = Preferences.userNodeForPackage(ReportForgeApplication.class);
    private final PauseTransition autosavePause = new PauseTransition(Duration.millis(900));
    private final StartScreenView startScreenView = new StartScreenView();
    private final WorkspaceHost workspaceHost = new UiBridge();

    private ThemeMode themeMode = loadThemeMode();

    private Stage primaryStage;
    private Stage projectStage;
    private Scene scene;
    private Scene startScene;
    private Parent startRoot;
    private BorderPane root;
    private Node toolbarContainer;
    private Node statusBarContainer;
    private ToolBar leftToolBar;
    private HBox centerToolbarBox;
    private ToolBar rightToolBar;
    private Label infoStatusLabel;
    private Label reportStatusLabel;
    private Label autosaveStatusLabel;

    private ProjectWorkspace currentWorkspace;
    private WorkspaceNode currentSelection;
    private boolean dirty;

    private WorkspaceNavigator workspaceNavigator;
    private WorkspaceContentFactory workspaceContentFactory;
    private double startWindowDragOffsetX;
    private double startWindowDragOffsetY;

    @Override
    public void start(Stage stage) {
        FontSupport.loadBundledFonts();
        primaryStage = stage;
        primaryStage.initStyle(StageStyle.TRANSPARENT);
        primaryStage.setResizable(false);
        initializeProjectWindow();
        showStartWindow();
        primaryStage.show();
    }

    private void initializeProjectWindow() {
        root = new BorderPane();
        root.getStyleClass().addAll("app-root", "workspace-root");

        buildTopToolbar();
        buildStatusBar();
        toolbarContainer = buildToolbarContainer();
        statusBarContainer = buildStatusBarContainer();

        autosavePause.setOnFinished(event -> flushAutosave());

        scene = new Scene(root, PROJECT_WINDOW_WIDTH, PROJECT_WINDOW_HEIGHT);
        scene.getStylesheets().add(Objects.requireNonNull(
                ReportForgeApplication.class.getResource("reportforge.css")
        ).toExternalForm());

        projectStage = new Stage();
        projectStage.setScene(scene);
        projectStage.setTitle("ReportForge");
        projectStage.setResizable(true);
        projectStage.setMinWidth(PROJECT_WINDOW_MIN_WIDTH);
        projectStage.setMinHeight(PROJECT_WINDOW_MIN_HEIGHT);
        projectStage.setOnCloseRequest(event -> {
            if (currentWorkspace != null) {
                event.consume();
                closeCurrentProject();
            }
        });
        applyTheme();
        updateChrome();
    }

    @Override
    public void stop() {
        try {
            if (dirty) {
                flushAutosave();
            }
        } finally {
            projectService.closeCurrentSession();
        }
    }

    private void showStartWindow() {
        startRoot = (Parent) startScreenView.build(
                recentProjectsService.loadRecentProjects(),
                themeMode,
                this::handleNewProject,
                this::handleOpenProject,
                recentProject -> openProject(Path.of(recentProject.getPath()), true),
                this::toggleTheme,
                this::minimizeStartWindow,
                this::closeStartWindow
        );
        if (!startRoot.getStyleClass().contains("app-root")) {
            startRoot.getStyleClass().add(0, "app-root");
        }

        startScene = new Scene(startRoot, START_WINDOW_WIDTH, START_WINDOW_HEIGHT);
        startScene.setFill(Color.TRANSPARENT);
        startScene.getStylesheets().add(Objects.requireNonNull(
                ReportForgeApplication.class.getResource("reportforge.css")
        ).toExternalForm());
        applyTheme();
        wireStartWindowDragging();

        primaryStage.setScene(startScene);
        primaryStage.setTitle("ReportForge");
        primaryStage.setWidth(START_WINDOW_WIDTH);
        primaryStage.setHeight(START_WINDOW_HEIGHT);
        primaryStage.setResizable(false);
        startRoot.applyCss();
        primaryStage.centerOnScreen();
    }

    private void showWorkspace() {
        if (workspaceContentFactory == null) {
            workspaceContentFactory = new WorkspaceContentFactory(workspaceHost);
            workspaceNavigator = new WorkspaceNavigator(workspaceHost, workspaceContentFactory);
        }
        root.setCenter(workspaceNavigator.build());
        updateChrome();
        if (!projectStage.isShowing()) {
            projectStage.setWidth(PROJECT_WINDOW_WIDTH);
            projectStage.setHeight(PROJECT_WINDOW_HEIGHT);
            projectStage.centerOnScreen();
        }
        projectStage.show();
        projectStage.toFront();
        if (primaryStage != null && primaryStage.isShowing()) {
            primaryStage.hide();
        }
    }

    private Node buildToolbarContainer() {
        BorderPane toolbarPane = new BorderPane();
        toolbarPane.setPadding(new Insets(10, 16, 10, 16));
        toolbarPane.getStyleClass().add("top-chrome");
        toolbarPane.setLeft(leftToolBar);
        toolbarPane.setCenter(centerToolbarBox);
        toolbarPane.setRight(rightToolBar);
        return toolbarPane;
    }

    private Node buildStatusBarContainer() {
        BorderPane statusPane = new BorderPane();
        statusPane.setPadding(new Insets(8, 16, 10, 16));
        statusPane.getStyleClass().add("status-chrome");
        statusPane.setLeft(infoStatusLabel);
        HBox rightBox = new HBox(14, reportStatusLabel, autosaveStatusLabel);
        rightBox.setAlignment(Pos.CENTER_RIGHT);
        rightBox.getStyleClass().add("status-right-box");
        statusPane.setRight(rightBox);
        return statusPane;
    }

    private void buildTopToolbar() {
        leftToolBar = new ToolBar();
        leftToolBar.getStyleClass().add("chrome-toolbar");

        centerToolbarBox = new HBox(8);
        centerToolbarBox.setAlignment(Pos.CENTER);
        HBox.setHgrow(centerToolbarBox, Priority.ALWAYS);
        centerToolbarBox.getStyleClass().add("toolbar-center-box");

        rightToolBar = new ToolBar();
        rightToolBar.getStyleClass().add("chrome-toolbar");
    }

    private void buildStatusBar() {
        infoStatusLabel = new Label("Ready");
        reportStatusLabel = new Label("No report selected");
        autosaveStatusLabel = new Label("No project open");
        infoStatusLabel.getStyleClass().add("status-info");
        reportStatusLabel.getStyleClass().add("status-pill");
        autosaveStatusLabel.getStyleClass().add("status-pill");
    }

    private void updateChrome() {
        leftToolBar.getItems().setAll(
                createToolbarButton("New", event -> handleNewProject()),
                createToolbarButton("Open", event -> handleOpenProject()),
                createToolbarButton("Save", event -> flushAutosave(), currentWorkspace != null),
                createToolbarButton("Close Project", event -> closeCurrentProject(), currentWorkspace != null)
        );

        if (currentWorkspace == null) {
            root.setTop(null);
            root.setBottom(null);
            centerToolbarBox.getChildren().clear();
            rightToolBar.getItems().setAll(createToolbarButton(
                    "Export",
                    event -> showInformation("Export", "Open a report to export it."),
                    false
            ));
            reportStatusLabel.setText("No report selected");
            autosaveStatusLabel.setText("No project open");
            if (projectStage != null) {
                projectStage.setTitle("ReportForge");
            }
            return;
        }

        root.setTop(toolbarContainer);
        root.setBottom(statusBarContainer);
        updateCenterToolbar();
        updateRightToolbar();
        updateStatusBarLabels();
        updateStageTitle();
    }

    private void updateCenterToolbar() {
        centerToolbarBox.getChildren().clear();
        if (currentSelection == null) {
            return;
        }

        switch (currentSelection.type()) {
            case PROJECT -> {
                centerToolbarBox.getChildren().add(createToolbarButton("Add Environment", event -> handleAddEnvironment()));
                centerToolbarBox.getChildren().add(createToolbarButton("Applications", event -> selectApplicationsNode()));
            }
            case APPLICATIONS -> centerToolbarBox.getChildren().add(createToolbarButton("Add Application", event -> addProjectApplication()));
            case ENVIRONMENT -> {
                centerToolbarBox.getChildren().add(createToolbarButton("New Report", event -> createReportForCurrentEnvironment()));
                centerToolbarBox.getChildren().add(createToolbarButton("Delete Environment", event -> deleteCurrentEnvironment()));
            }
            case REPORT -> {
                centerToolbarBox.getChildren().add(createToolbarButton("Duplicate", event -> duplicateCurrentReport()));
                centerToolbarBox.getChildren().add(createToolbarButton("Move", event -> moveCurrentReport()));
                centerToolbarBox.getChildren().add(createToolbarButton("Copy To", event -> copyCurrentReport()));
                centerToolbarBox.getChildren().add(createToolbarButton("Delete Report", event -> deleteCurrentReport()));

                ComboBox<ReportStatus> statusComboBox = new ComboBox<>(FXCollections.observableArrayList(ReportStatus.values()));
                statusComboBox.setConverter(UiSupport.reportStatusConverter());
                ReportRecord reportRecord = (ReportRecord) currentSelection.payload();
                statusComboBox.setValue(reportRecord.getStatus());
                statusComboBox.setOnAction(event -> handleReportStatusChange(reportRecord, statusComboBox.getValue()));
                centerToolbarBox.getChildren().add(statusComboBox);
            }
        }
    }

    private void updateRightToolbar() {
        boolean reportSelected = currentSelection != null && currentSelection.type() == WorkspaceNodeType.REPORT;
        Button themeButton = createThemeToggleButton(true);
        Button exportButton = createToolbarButton(
                "Export",
                event -> showInformation(
                        "Export",
                        "Export presets and report exports will be implemented next. This first build focuses on project and report authoring."
                ),
                reportSelected
        );
        exportButton.getStyleClass().add("accent-button");
        rightToolBar.getItems().setAll(themeButton, exportButton);
    }

    private void updateStageTitle() {
        if (projectStage != null) {
            projectStage.setTitle("ReportForge - " + currentWorkspace.getProject().getName());
        }
    }

    private void updateStatusBarLabels() {
        if (currentSelection != null && currentSelection.type() == WorkspaceNodeType.REPORT) {
            ReportRecord report = (ReportRecord) currentSelection.payload();
            reportStatusLabel.setText("Status: " + report.getStatus().getDisplayName());
        } else {
            reportStatusLabel.setText("Status: N/A");
        }
        autosaveStatusLabel.setText(dirty ? "Autosave: Pending" : "Autosave: Saved");
    }

    private Button createToolbarButton(String text, java.util.function.Consumer<javafx.event.ActionEvent> action) {
        return createToolbarButton(text, action, true);
    }

    private Button createToolbarButton(String text, java.util.function.Consumer<javafx.event.ActionEvent> action, boolean enabled) {
        Button button = new Button(text);
        button.setDisable(!enabled);
        button.setOnAction(action::accept);
        button.getStyleClass().addAll("app-button", "toolbar-button");
        return button;
    }

    private Button createThemeToggleButton(boolean compact) {
        Button button = new Button(themeMode == ThemeMode.DARK ? "Light Mode" : "Dark Mode");
        button.setOnAction(event -> toggleTheme());
        button.getStyleClass().addAll("app-button", compact ? "toolbar-button" : "secondary-button");
        if (!compact) {
            button.getStyleClass().add("theme-toggle-button");
        }
        return button;
    }

    private void toggleTheme() {
        themeMode = themeMode == ThemeMode.DARK ? ThemeMode.LIGHT : ThemeMode.DARK;
        preferences.put(THEME_PREFERENCE_KEY, themeMode.preferenceValue());
        applyTheme();
        if (currentWorkspace == null && primaryStage != null && primaryStage.isShowing()) {
            showStartWindow();
            primaryStage.show();
        }
        updateChrome();
    }

    private void applyTheme() {
        applyTheme(root);
        applyTheme(startRoot);
    }

    private void applyTheme(Parent parent) {
        if (parent == null) {
            return;
        }
        parent.getStyleClass().removeAll(ThemeMode.DARK.cssClass(), ThemeMode.LIGHT.cssClass());
        if (!parent.getStyleClass().contains("app-root")) {
            parent.getStyleClass().add(0, "app-root");
        }
        parent.getStyleClass().add(themeMode.cssClass());
    }

    private ThemeMode loadThemeMode() {
        return ThemeMode.fromPreferenceValue(preferences.get(THEME_PREFERENCE_KEY, ThemeMode.DARK.preferenceValue()));
    }

    private void handleNewProject() {
        Optional<NewProjectDialog.Result> input = NewProjectDialog.show(workspaceHost);
        input.ifPresent(this::createProject);
    }

    private void createProject(NewProjectDialog.Result input) {
        try {
            projectService.createProject(
                    Path.of(UiSupport.requireText(input.projectPath(), "Project file location")),
                    UiSupport.requireText(input.projectName(), "Project Name"),
                    input.initialEnvironmentName(),
                    input.applicationNames()
            );
            currentWorkspace = projectService.loadWorkspace();
            currentSelection = null;
            dirty = false;
            recentProjectsService.touchProject(projectService.getCurrentSession().projectFile(), currentWorkspace.getProject().getName());
            showWorkspace();
            selectProjectNode();
            setInfoStatus("Project created.");
        } catch (Exception exception) {
            showError("Unable to create project", exception.getMessage());
        }
    }

    private void handleOpenProject() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Project");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "ReportForge Projects",
                "*" + ProjectContainerService.PROJECT_EXTENSION
        ));
        var file = chooser.showOpenDialog(currentWindowStage());
        if (file != null) {
            openProject(file.toPath(), false);
        }
    }

    private void openProject(Path projectPath, boolean removeWhenMissing) {
        try {
            projectService.openProject(projectPath);
            currentWorkspace = projectService.loadWorkspace();
            currentSelection = null;
            dirty = false;
            recentProjectsService.touchProject(projectPath, currentWorkspace.getProject().getName());
            showWorkspace();
            selectProjectNode();
            setInfoStatus("Project opened.");
        } catch (Exception exception) {
            if (removeWhenMissing && "File not found.".equals(exception.getMessage())) {
                recentProjectsService.removeProject(projectPath);
                resetCurrentProjectState();
                reopenStartScreen();
            }
            showError("Unable to open project", normalizeOpenProjectMessage(exception.getMessage()));
        }
    }

    private String normalizeOpenProjectMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Failed to read project data.";
        }
        return message;
    }

    private void addProjectApplication() {
        Optional<ApplicationEntry> input = ApplicationEntryDialog.show(workspaceHost, "Add Application", null);
        input.ifPresent(application -> {
            try {
                projectService.upsertProjectApplication(application);
                reloadWorkspaceAndReselect(WorkspaceNodeType.APPLICATIONS, "project-applications");
                markDirty("Application added.");
            } catch (Exception exception) {
                showError("Unable to add application", exception.getMessage());
            }
        });
    }

    private void editProjectApplication(ApplicationEntry selected) {
        Optional<ApplicationEntry> input = ApplicationEntryDialog.show(workspaceHost, "Edit Application", selected);
        input.ifPresent(application -> {
            try {
                projectService.upsertProjectApplication(application);
                reloadWorkspaceAndReselect(WorkspaceNodeType.APPLICATIONS, "project-applications");
                markDirty("Application updated.");
            } catch (Exception exception) {
                showError("Unable to update application", exception.getMessage());
            }
        });
    }

    private void handleAddEnvironment() {
        TextInputDialog dialog = new TextInputDialog("QA");
        dialog.initOwner(currentWindowStage());
        dialog.setTitle("Add Environment");
        dialog.setHeaderText("Create a new environment");
        dialog.setContentText("Environment Name:");
        UiSupport.styleDialog(workspaceHost, dialog);
        dialog.showAndWait().ifPresent(name -> {
            try {
                EnvironmentRecord environment = projectService.createEnvironment(name);
                reloadWorkspaceAndReselect(WorkspaceNodeType.ENVIRONMENT, environment.getId());
                markDirty("Environment created.");
            } catch (Exception exception) {
                showError("Unable to create environment", exception.getMessage());
            }
        });
    }

    private void deleteCurrentEnvironment() {
        if (currentSelection == null || currentSelection.type() != WorkspaceNodeType.ENVIRONMENT) {
            return;
        }
        EnvironmentRecord environment = (EnvironmentRecord) currentSelection.payload();
        if (!confirm("Delete Environment", "Delete environment '" + environment.getName() + "'?")) {
            return;
        }
        try {
            projectService.deleteEnvironment(environment.getId());
            reloadWorkspaceAndReselect(WorkspaceNodeType.PROJECT, currentWorkspace.getProject().getId());
            markDirty("Environment deleted.");
        } catch (Exception exception) {
            showError("Unable to delete environment", exception.getMessage());
        }
    }

    private void createReportForCurrentEnvironment() {
        if (currentSelection != null && currentSelection.type() == WorkspaceNodeType.ENVIRONMENT) {
            createReportForEnvironment((EnvironmentRecord) currentSelection.payload());
        }
    }

    private void createReportForEnvironment(EnvironmentRecord environment) {
        TextInputDialog dialog = new TextInputDialog("Test Execution Report");
        dialog.initOwner(currentWindowStage());
        dialog.setTitle("Create Report");
        dialog.setHeaderText("Create Test Execution Report");
        dialog.setContentText("Report Title:");
        UiSupport.styleDialog(workspaceHost, dialog);
        dialog.showAndWait().ifPresent(title -> {
            try {
                ReportRecord report = projectService.createTestExecutionReport(environment.getId(), title);
                reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                markDirty("Report created.");
            } catch (Exception exception) {
                showError("Unable to create report", exception.getMessage());
            }
        });
    }

    private void duplicateCurrentReport() {
        if (currentSelection == null || currentSelection.type() != WorkspaceNodeType.REPORT) {
            return;
        }
        ReportRecord report = (ReportRecord) currentSelection.payload();
        try {
            ReportRecord duplicatedReport = projectService.copyReportToEnvironment(report.getId(), report.getEnvironmentId());
            reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, duplicatedReport.getId());
            markDirty("Report duplicated.");
        } catch (Exception exception) {
            showError("Unable to duplicate report", exception.getMessage());
        }
    }

    private void moveCurrentReport() {
        if (currentSelection == null || currentSelection.type() != WorkspaceNodeType.REPORT) {
            return;
        }
        ReportRecord report = (ReportRecord) currentSelection.payload();
        chooseTargetEnvironment(report.getEnvironmentId()).ifPresent(environmentId -> {
            try {
                projectService.moveReportToEnvironment(report.getId(), environmentId);
                reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                markDirty("Report moved.");
            } catch (Exception exception) {
                showError("Unable to move report", exception.getMessage());
            }
        });
    }

    private void copyCurrentReport() {
        if (currentSelection == null || currentSelection.type() != WorkspaceNodeType.REPORT) {
            return;
        }
        ReportRecord report = (ReportRecord) currentSelection.payload();
        chooseTargetEnvironment(report.getEnvironmentId()).ifPresent(environmentId -> {
            try {
                ReportRecord copiedReport = projectService.copyReportToEnvironment(report.getId(), environmentId);
                reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, copiedReport.getId());
                markDirty("Report copied.");
            } catch (Exception exception) {
                showError("Unable to copy report", exception.getMessage());
            }
        });
    }

    private void deleteCurrentReport() {
        if (currentSelection == null || currentSelection.type() != WorkspaceNodeType.REPORT) {
            return;
        }
        ReportRecord report = (ReportRecord) currentSelection.payload();
        if (!confirm("Delete Report", "Delete report '" + report.getTitle() + "'?")) {
            return;
        }
        try {
            String environmentId = report.getEnvironmentId();
            projectService.deleteReport(report.getId());
            reloadWorkspaceAndReselect(WorkspaceNodeType.ENVIRONMENT, environmentId);
            markDirty("Report deleted.");
        } catch (Exception exception) {
            showError("Unable to delete report", exception.getMessage());
        }
    }

    private Optional<String> chooseTargetEnvironment(String currentEnvironmentId) {
        List<EnvironmentRecord> candidateEnvironments = currentWorkspace.getEnvironments().stream()
                .filter(environment -> !Objects.equals(environment.getId(), currentEnvironmentId))
                .toList();
        if (candidateEnvironments.isEmpty()) {
            showInformation("Environment Selection", "Create another environment first.");
            return Optional.empty();
        }
        return EnvironmentSelectionDialog.show(workspaceHost, candidateEnvironments);
    }

    private void handleReportStatusChange(ReportRecord report, ReportStatus requestedStatus) {
        if (requestedStatus == null || requestedStatus == report.getStatus()) {
            return;
        }

        if (requestedStatus != ReportStatus.DRAFT) {
            List<String> validationIssues = validateReport(report.getId(), report.getTitle());
            if (!validationIssues.isEmpty()) {
                showError(
                        "Cannot change report status",
                        "Complete the required fields first:\n- " + String.join("\n- ", validationIssues)
                );
                reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                return;
            }
        }

        try {
            projectService.updateReportStatus(report.getId(), requestedStatus);
            reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
            markDirty("Report status updated.");
        } catch (Exception exception) {
            showError("Unable to update report status", exception.getMessage());
        }
    }

    private List<String> validateReport(String reportId, String reportTitle) {
        try {
            Map<String, String> fields = projectService.loadReportFields(reportId);
            List<ApplicationEntry> applications = projectService.loadReportApplications(reportId);
            EnvironmentRecord environment = projectService.loadReportEnvironmentSnapshot(reportId);

            List<String> issues = new ArrayList<>();
            if (reportTitle == null || reportTitle.isBlank()) {
                issues.add("Project Overview -> Report Title");
            }
            if (fields.getOrDefault("projectOverview.projectName", "").isBlank()) {
                issues.add("Project Overview -> Project Name");
            }
            if (environment.getName() == null || environment.getName().isBlank()) {
                issues.add("Test Environment -> Environment Name");
            }
            if (applications.isEmpty() || applications.stream().anyMatch(application -> application.getName() == null || application.getName().isBlank())) {
                issues.add("Applications under Test -> At least one named application");
            }
            if (fields.getOrDefault("scope.objectiveSummary", "").isBlank()) {
                issues.add("Test Objectives and Scope -> Test Objective Summary");
            }
            requireNumericField(fields, "executionSummary.totalExecuted", "Execution Summary -> Total Tests Executed", issues);
            requireNumericField(fields, "executionSummary.passedCount", "Execution Summary -> Passed Count", issues);
            requireNumericField(fields, "executionSummary.failedCount", "Execution Summary -> Failed Count", issues);
            requireNumericField(fields, "executionSummary.blockedCount", "Execution Summary -> Blocked Count", issues);
            if (fields.getOrDefault("executionSummary.startDate", "").isBlank()) {
                issues.add("Execution Summary -> Execution Start Date");
            }
            if (fields.getOrDefault("executionSummary.endDate", "").isBlank()) {
                issues.add("Execution Summary -> Execution End Date");
            }
            if (fields.getOrDefault("executionSummary.overallOutcome", "").isBlank()) {
                issues.add("Execution Summary -> Overall Outcome");
            }
            if (fields.getOrDefault("conclusion.overallConclusion", "").isBlank()) {
                issues.add("Conclusion -> Overall Conclusion");
            }
            return issues;
        } catch (Exception exception) {
            return List.of("Unable to validate report: " + exception.getMessage());
        }
    }

    private void requireNumericField(Map<String, String> fields, String fieldKey, String label, List<String> issues) {
        String value = fields.getOrDefault(fieldKey, "");
        if (value.isBlank()) {
            issues.add(label);
            return;
        }
        try {
            int parsedValue = Integer.parseInt(value);
            if (parsedValue < 0) {
                issues.add(label);
            }
        } catch (NumberFormatException exception) {
            issues.add(label);
        }
    }

    private void reloadWorkspaceAndReselect(WorkspaceNodeType nodeType, String nodeId) {
        try {
            currentSelection = null;
            currentWorkspace = projectService.loadWorkspace();
            if (workspaceNavigator != null) {
                workspaceNavigator.rebuildTree();
                workspaceNavigator.selectNode(nodeType, nodeId);
            }
            updateChrome();
        } catch (Exception exception) {
            showError("Unable to refresh workspace", exception.getMessage());
        }
    }

    private void selectProjectNode() {
        if (workspaceNavigator != null) {
            workspaceNavigator.selectProjectNode();
        }
    }

    private void selectApplicationsNode() {
        if (workspaceNavigator != null) {
            workspaceNavigator.selectApplicationsNode();
        }
    }

    private void selectNode(WorkspaceNodeType nodeType, String nodeId) {
        if (workspaceNavigator != null) {
            workspaceNavigator.selectNode(nodeType, nodeId);
        }
    }

    private void onWorkspaceSelectionChanged(WorkspaceNode selection) {
        currentSelection = selection;
        updateChrome();
    }

    private void flushAutosave() {
        if (currentWorkspace == null || projectService.getCurrentSession() == null || !dirty) {
            return;
        }
        WorkspaceNode selectionSnapshot = currentSelection;
        try {
            autosaveStatusLabel.setText("Autosave: Saving...");
            projectService.saveProject();
            dirty = false;
            currentWorkspace = projectService.loadWorkspace();
            recentProjectsService.touchProject(projectService.getCurrentSession().projectFile(), currentWorkspace.getProject().getName());
            if (workspaceNavigator != null) {
                workspaceNavigator.rebuildTree();
                if (selectionSnapshot != null) {
                    workspaceNavigator.selectNode(selectionSnapshot.type(), selectionSnapshot.id());
                }
            }
            autosaveStatusLabel.setText("Autosave: Saved");
        } catch (Exception exception) {
            autosaveStatusLabel.setText("Autosave: Failed");
            showError("Unable to save project", exception.getMessage());
        }
    }

    private void closeCurrentProject() {
        if (currentWorkspace == null || projectService.getCurrentSession() == null) {
            return;
        }

        autosavePause.stop();
        try {
            autosaveStatusLabel.setText("Autosave: Saving...");
            projectService.saveProject();
            recentProjectsService.touchProject(projectService.getCurrentSession().projectFile(), currentWorkspace.getProject().getName());
            resetCurrentProjectState();
            reopenStartScreen();
        } catch (Exception exception) {
            autosaveStatusLabel.setText("Autosave: Failed");
            showError("Unable to close project", exception.getMessage());
        }
    }

    private void resetCurrentProjectState() {
        projectService.closeCurrentSession();
        currentWorkspace = null;
        currentSelection = null;
        dirty = false;
        root.setCenter(null);
        updateChrome();
    }

    private void reopenStartScreen() {
        if (projectStage != null && projectStage.isShowing()) {
            projectStage.hide();
        }
        showStartWindow();
        if (primaryStage != null && !primaryStage.isShowing()) {
            primaryStage.show();
        }
        if (primaryStage != null) {
            primaryStage.toFront();
        }
    }

    private void markDirty(String message) {
        dirty = true;
        setInfoStatus(message);
        updateStatusBarLabels();
        autosavePause.playFromStart();
    }

    private void setInfoStatus(String message) {
        infoStatusLabel.setText(message == null || message.isBlank() ? "Ready" : message);
    }

    private Stage currentWindowStage() {
        if (projectStage != null && projectStage.isShowing()) {
            return projectStage;
        }
        return primaryStage;
    }

    private void wireStartWindowDragging() {
        if (startRoot == null) {
            return;
        }
        Node dragRegion = startRoot.lookup("#start-window-title-bar");
        Node activeRegion = dragRegion != null ? dragRegion : startRoot;

        activeRegion.setOnMousePressed(event -> {
            startWindowDragOffsetX = event.getSceneX();
            startWindowDragOffsetY = event.getSceneY();
        });
        activeRegion.setOnMouseDragged(event -> {
            if (primaryStage != null && primaryStage.isShowing()) {
                primaryStage.setX(event.getScreenX() - startWindowDragOffsetX);
                primaryStage.setY(event.getScreenY() - startWindowDragOffsetY);
            }
        });
    }

    private void minimizeStartWindow() {
        if (primaryStage != null) {
            primaryStage.setIconified(true);
        }
    }

    private void closeStartWindow() {
        if (primaryStage != null) {
            primaryStage.close();
        }
    }

    private boolean confirm(String title, String message) {
        return UiSupport.confirm(workspaceHost, title, message);
    }

    private void showInformation(String title, String message) {
        UiSupport.showInformation(workspaceHost, title, message);
    }

    private void showError(String title, String message) {
        UiSupport.showError(workspaceHost, title, message);
    }

    private final class UiBridge implements WorkspaceHost {
        @Override
        public Stage getPrimaryStage() {
            return currentWindowStage();
        }

        @Override
        public Scene getScene() {
            if (projectStage != null && projectStage.isShowing()) {
                return scene;
            }
            return startScene;
        }

        @Override
        public ThemeMode getThemeMode() {
            return themeMode;
        }

        @Override
        public ProjectWorkspace getCurrentWorkspace() {
            return currentWorkspace;
        }

        @Override
        public ProjectContainerService getProjectService() {
            return projectService;
        }

        @Override
        public void onWorkspaceSelectionChanged(WorkspaceNode selection) {
            ReportForgeApplication.this.onWorkspaceSelectionChanged(selection);
        }

        @Override
        public void reloadWorkspaceAndReselect(WorkspaceNodeType nodeType, String nodeId) {
            ReportForgeApplication.this.reloadWorkspaceAndReselect(nodeType, nodeId);
        }

        @Override
        public void selectNode(WorkspaceNodeType nodeType, String nodeId) {
            ReportForgeApplication.this.selectNode(nodeType, nodeId);
        }

        @Override
        public void selectApplicationsNode() {
            ReportForgeApplication.this.selectApplicationsNode();
        }

        @Override
        public void markDirty(String message) {
            ReportForgeApplication.this.markDirty(message);
        }

        @Override
        public void showInformation(String title, String message) {
            ReportForgeApplication.this.showInformation(title, message);
        }

        @Override
        public void showError(String title, String message) {
            ReportForgeApplication.this.showError(title, message);
        }

        @Override
        public void addProjectApplication() {
            ReportForgeApplication.this.addProjectApplication();
        }

        @Override
        public void editProjectApplication(ApplicationEntry selected) {
            ReportForgeApplication.this.editProjectApplication(selected);
        }

        @Override
        public void createReportForEnvironment(EnvironmentRecord environment) {
            ReportForgeApplication.this.createReportForEnvironment(environment);
        }

        @Override
        public void handleReportStatusChange(ReportRecord report, ReportStatus requestedStatus) {
            ReportForgeApplication.this.handleReportStatusChange(report, requestedStatus);
        }
    }
}
