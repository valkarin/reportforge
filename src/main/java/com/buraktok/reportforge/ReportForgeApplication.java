package com.buraktok.reportforge;

import com.buraktok.reportforge.model.ApplicationEntry;
import com.buraktok.reportforge.model.ExecutionReportSnapshot;
import com.buraktok.reportforge.model.ExecutionRunRecord;
import com.buraktok.reportforge.model.ExecutionRunSnapshot;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ProjectWorkspace;
import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.ReportStatus;
import com.buraktok.reportforge.persistence.ProjectContainerService;
import com.buraktok.reportforge.persistence.ProjectSession;
import com.buraktok.reportforge.persistence.RecentProjectsService;
import com.buraktok.reportforge.ui.ApplicationEntryDialog;
import com.buraktok.reportforge.ui.EnvironmentSelectionDialog;
import com.buraktok.reportforge.ui.FontSupport;
import com.buraktok.reportforge.ui.IconSupport;
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
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Side;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.prefs.Preferences;

public class ReportForgeApplication extends Application {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReportForgeApplication.class);
    private static final String THEME_PREFERENCE_KEY = "themeMode";
    private static final double START_WINDOW_WIDTH = 960;
    private static final double START_WINDOW_HEIGHT = 640;
    private static final double PROJECT_WINDOW_WIDTH = 1400;
    private static final double PROJECT_WINDOW_HEIGHT = 900;
    private static final double PROJECT_WINDOW_MIN_WIDTH = 1180;
    private static final double PROJECT_WINDOW_MIN_HEIGHT = 760;
    private static final double WINDOW_RESIZE_MARGIN = 6;
    private static final DateTimeFormatter PROJECT_METADATA_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.getDefault());

    private final ProjectContainerService projectService = new ProjectContainerService();
    private final RecentProjectsService recentProjectsService = new RecentProjectsService();
    private final Preferences preferences = Preferences.userNodeForPackage(ReportForgeApplication.class);
    private final PauseTransition autosavePause = new PauseTransition(Duration.millis(900));
    private final StartScreenView startScreenView = new StartScreenView();
    private final WorkspaceHost workspaceHost = new UiBridge();
    private final ProjectLifecycleCoordinator projectLifecycleCoordinator = new ProjectLifecycleCoordinator(
            new ProjectServiceBridge(),
            new RecentProjectsBridge(),
            new AutosaveBridge(),
            new LifecycleUiBridge()
    );
    private final ToolbarAndExportCoordinator toolbarAndExportCoordinator =
            new ToolbarAndExportCoordinator(workspaceHost, new ToolbarActionBridge());

    private ThemeMode themeMode = loadThemeMode();

    private Stage primaryStage;
    private Stage projectStage;
    private Scene scene;
    private Scene startScene;
    private Parent startRoot;
    private BorderPane root;
    private Node projectWindowChrome;
    private Node projectWindowTitleBar;
    private Node toolbarContainer;
    private Node statusBarContainer;
    private Button projectWindowTitleButton;
    private Label projectWindowTitleLabel;
    private Label infoStatusLabel;
    private Button projectWindowMaximizeButton;
    private StackPane projectWindowSavedIndicatorSlot;
    private ContextMenu projectWindowTitleMenu;

    private WorkspaceNode currentSelection;

    private WorkspaceNavigator workspaceNavigator;
    private WorkspaceContentFactory workspaceContentFactory;
    private double startWindowDragOffsetX;
    private double startWindowDragOffsetY;
    private double projectWindowDragOffsetX;
    private double projectWindowDragOffsetY;
    private Cursor projectWindowActiveResizeCursor = Cursor.DEFAULT;
    private double projectWindowResizeStartScreenX;
    private double projectWindowResizeStartScreenY;
    private double projectWindowResizeStartX;
    private double projectWindowResizeStartY;
    private double projectWindowResizeStartWidth;
    private double projectWindowResizeStartHeight;
    private double projectWindowRestoreX = Double.NaN;
    private double projectWindowRestoreY = Double.NaN;
    private double projectWindowRestoreWidth = Double.NaN;
    private double projectWindowRestoreHeight = Double.NaN;
    private boolean projectWindowMaximized;

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

    /**
     * Initializes the primary application structural layout, scenes, and window controls for the project workspace.
     */
    private void initializeProjectWindow() {
        root = new BorderPane();
        root.getStyleClass().addAll("app-root", "start-screen-root", "workspace-root");

        toolbarContainer = toolbarAndExportCoordinator.buildToolbarContainer();
        buildStatusBar();
        projectWindowTitleBar = buildProjectWindowTitleBar();
        projectWindowChrome = new VBox(projectWindowTitleBar, toolbarContainer);
        statusBarContainer = buildStatusBarContainer();

        autosavePause.setOnFinished(event -> flushAutosave());

        scene = new Scene(root, PROJECT_WINDOW_WIDTH, PROJECT_WINDOW_HEIGHT);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(Objects.requireNonNull(
                ReportForgeApplication.class.getResource("reportforge.css")
        ).toExternalForm());

        projectStage = new Stage();
        projectStage.initStyle(StageStyle.TRANSPARENT);
        projectStage.setScene(scene);
        projectStage.setTitle("ReportForge");
        projectStage.setResizable(true);
        projectStage.setMinWidth(PROJECT_WINDOW_MIN_WIDTH);
        projectStage.setMinHeight(PROJECT_WINDOW_MIN_HEIGHT);
        projectStage.setOnCloseRequest(event -> {
            if (currentWorkspace() != null) {
                event.consume();
                closeCurrentProject();
            }
        });
        wireProjectWindowInteractions();
        applyTheme();
        updateChrome();
    }

    @Override
    public void stop() {
        projectLifecycleCoordinator.shutdown();
    }

    /**
     * Structures and presents the initial launch screen view containing recent projects.
     */
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

    /**
     * Transitions the application view to the active project workspace window and hides the start screen.
     */
    private void showWorkspace() {
        if (workspaceContentFactory == null) {
            workspaceContentFactory = new WorkspaceContentFactory(workspaceHost);
            workspaceNavigator = new WorkspaceNavigator(workspaceHost, workspaceContentFactory);
        }
        root.setCenter(workspaceNavigator.build());
        updateChrome();
        if (!projectStage.isShowing()) {
            if (projectWindowMaximized) {
                applyProjectWindowMaximizedBounds();
            } else {
                projectStage.setWidth(PROJECT_WINDOW_WIDTH);
                projectStage.setHeight(PROJECT_WINDOW_HEIGHT);
                projectStage.centerOnScreen();
            }
        }
        projectStage.show();
        projectStage.toFront();
        if (primaryStage != null && primaryStage.isShowing()) {
            primaryStage.hide();
        }
    }

    /**
     * Retrieves the currently active project workspace domain context.
     *
     * @return the project workspace or null if no project is loaded
     */
    private ProjectWorkspace currentWorkspace() {
        return projectLifecycleCoordinator.getCurrentWorkspace();
    }

    /**
     * Determines whether the current project has unsaved structural modifications.
     *
     * @return true if there are pending autosaves
     */
    private boolean isDirty() {
        return projectLifecycleCoordinator.isDirty();
    }

    /**
     * Checks if a save operation is currently writing to the filesystem.
     *
     * @return true if a save is in progress
     */
    private boolean isProjectSaveInProgress() {
        return projectLifecycleCoordinator.isProjectSaveInProgress();
    }

    /**
     * Constructs the custom movable title bar integrated with window navigation controls.
     *
     * @return the structured layout container for the header
     */
    private Node buildProjectWindowTitleBar() {
        projectWindowTitleLabel = new Label("ReportForge");
        projectWindowTitleLabel.getStyleClass().addAll("window-title", "project-title-name");

        Node savedIndicator = IconSupport.createButtonIcon("fas-check-circle");
        savedIndicator.getStyleClass().add("project-save-icon");
        projectWindowSavedIndicatorSlot = new StackPane(savedIndicator);
        projectWindowSavedIndicatorSlot.getStyleClass().add("project-save-indicator-slot");
        projectWindowSavedIndicatorSlot.setOpacity(0);
        projectWindowSavedIndicatorSlot.setMouseTransparent(true);

        HBox titleDisplay = new HBox(10, projectWindowSavedIndicatorSlot, projectWindowTitleLabel);
        titleDisplay.setAlignment(Pos.CENTER);
        titleDisplay.getStyleClass().add("project-title-display");

        projectWindowTitleButton = new Button();
        projectWindowTitleButton.setFocusTraversable(false);
        projectWindowTitleButton.setGraphic(titleDisplay);
        projectWindowTitleButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        projectWindowTitleButton.getStyleClass().add("project-title-button");
        projectWindowTitleButton.setDisable(true);
        projectWindowTitleButton.setOnAction(event -> toggleProjectTitleMenu());

        Button minimizeButton = createWindowControlButton("fas-minus", this::minimizeProjectWindow, false);
        projectWindowMaximizeButton = createWindowControlButton("far-window-maximize", this::toggleProjectWindowMaximized, false);
        Button closeButton = createWindowControlButton("fas-times", this::closeProjectWindow, true);

        HBox windowControls = new HBox(8, minimizeButton, projectWindowMaximizeButton, closeButton);
        windowControls.getStyleClass().add("window-controls");

        Label applicationTitleLabel = new Label("ReportForge");
        applicationTitleLabel.getStyleClass().addAll("window-title", "window-brand-title");

        HBox leftBrandBox = new HBox(applicationTitleLabel);
        leftBrandBox.setAlignment(Pos.CENTER_LEFT);
        leftBrandBox.minWidthProperty().bind(windowControls.widthProperty());
        leftBrandBox.prefWidthProperty().bind(windowControls.widthProperty());
        leftBrandBox.maxWidthProperty().bind(windowControls.widthProperty());

        StackPane centeredTitle = new StackPane(projectWindowTitleButton);
        centeredTitle.setAlignment(Pos.CENTER);
        HBox.setHgrow(centeredTitle, Priority.ALWAYS);

        HBox headerRow = new HBox(leftBrandBox, centeredTitle, windowControls);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setId("project-window-title-bar");
        headerRow.getStyleClass().add("start-window-header");
        return headerRow;
    }

    /**
     * Builds the trailing status bar container attached to the base of the window.
     *
     * @return the status layout node
     */
    private Node buildStatusBarContainer() {
        BorderPane statusPane = new BorderPane();
        statusPane.setPadding(new Insets(4, 14, 5, 14));
        statusPane.getStyleClass().add("status-chrome");
        statusPane.setLeft(infoStatusLabel);
        return statusPane;
    }

    /**
     * Initializes the internal informational textual elements belonging to the status boundaries.
     */
    private void buildStatusBar() {
        infoStatusLabel = new Label("Ready");
        infoStatusLabel.getStyleClass().add("status-info");
    }

    /**
     * Synchronizes dynamic navigation headers and context-sensitive structural controls with the active view.
     */
    private void updateChrome() {
        toolbarAndExportCoordinator.syncToolbars(currentSelection);

        if (currentWorkspace() == null) {
            hideProjectTitleMenu();
            root.setTop(null);
            root.setBottom(null);
            updateStageTitle();
            return;
        }

        root.setTop(projectWindowChrome);
        root.setBottom(statusBarContainer);
        updateStageTitle();
    }

    /**
     * Updates the underlying stage text strings mirroring the internal project state.
     */
    private void updateStageTitle() {
        ProjectWorkspace currentWorkspace = currentWorkspace();
        String stageTitle = currentWorkspace == null
                ? "ReportForge"
                : "ReportForge - " + currentWorkspace.getProject().getName();
        updateProjectWindowTitle(stageTitle);
        updateProjectTitleDisplay();
    }

    /**
     * Produces a customized button that toggles the primary color theme mode.
     *
     * @param compact whether the layout should fit horizontally within smaller bounds
     * @return the prepared toggle interactive button
     */
    private Button createThemeToggleButton(boolean compact) {
        Button button = new Button(themeMode == ThemeMode.DARK ? "Light Mode" : "Dark Mode");
        button.setOnAction(event -> toggleTheme());
        button.getStyleClass().addAll("app-button", compact ? "toolbar-button" : "secondary-button");
        if (!compact) {
            button.getStyleClass().add("theme-toggle-button");
        }
        button.setGraphic(IconSupport.createButtonIcon(themeMode == ThemeMode.DARK ? "fas-sun" : "fas-moon"));
        button.setContentDisplay(ContentDisplay.LEFT);
        button.setGraphicTextGap(10);
        return button;
    }

    /**
     * Creates a simple icon-only button specifically intended for OS-level window actions (minimize, close).
     *
     * @param iconLiteral the FontAwesome icon identifier
     * @param action      the click handler execution event
     * @param closeButton true if this operates the destructive close action formatting
     * @return the customized interactive node
     */
    private Button createWindowControlButton(String iconLiteral, Runnable action, boolean closeButton) {
        Button button = new Button();
        button.setFocusTraversable(false);
        button.setOnAction(event -> action.run());
        button.setGraphic(IconSupport.createWindowControlIcon(iconLiteral));
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().addAll("window-control-button", closeButton ? "window-close-button" : "window-minimize-button");
        return button;
    }

    /**
     * Inverts the active application theme framework and commits the selection to local machine preferences.
     */
    private void toggleTheme() {
        hideProjectTitleMenu();
        themeMode = themeMode == ThemeMode.DARK ? ThemeMode.LIGHT : ThemeMode.DARK;
        preferences.put(THEME_PREFERENCE_KEY, themeMode.preferenceValue());
        applyTheme();
        if (currentWorkspace() == null && primaryStage != null && primaryStage.isShowing()) {
            showStartWindow();
            primaryStage.show();
        }
        updateChrome();
    }

    /**
     * Forces all primary window instances to synchronize style structures with the current UI theme.
     */
    private void applyTheme() {
        applyTheme(root);
        applyTheme(startRoot);
    }

    /**
     * Reapplies global CSS theme classes to a specified parent root node.
     *
     * @param parent the target parent node
     */
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

    /**
     * Reads the configured UI theme mode from persistent application preferences.
     *
     * @return the saved theme preference, defaulting to dark mode
     */
    private ThemeMode loadThemeMode() {
        return ThemeMode.fromPreferenceValue(preferences.get(THEME_PREFERENCE_KEY, ThemeMode.DARK.preferenceValue()));
    }

    /**
     * Initiates the interactive workflow to create and initialize a new project workspace.
     */
    private void handleNewProject() {
        Optional<NewProjectDialog.Result> input = NewProjectDialog.show(workspaceHost);
        input.ifPresent(this::createProject);
    }

    /**
     * Executes the backend creation and initial setup of a new project.
     *
     * @param input the configuration properties gathered from the new project dialog
     */
    private void createProject(NewProjectDialog.Result input) {
        runProjectIoUiAction("Unable to create project", () -> projectLifecycleCoordinator.createProject(
                Path.of(UiSupport.requireText(input.projectPath(), "Project file location")),
                UiSupport.requireText(input.projectName(), "Project Name"),
                input.initialEnvironmentName(),
                input.applicationNames()
        ));
    }

    /**
     * Opens a system file chooser allowing the user to browse for and open an existing project file.
     */
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

    /**
     * Requests the lifecycle coordinator to structurally load a specific project file into active memory.
     *
     * @param projectPath       the absolute filesystem path of the project
     * @param removeWhenMissing whether to evict the project from recent history if the file does not exist
     */
    private void openProject(Path projectPath, boolean removeWhenMissing) {
        projectLifecycleCoordinator.openProject(projectPath, removeWhenMissing);
    }

    /**
     * Launches the interactive workflow to define and add a new global application entry to the project.
     */
    private void addProjectApplication() {
        Optional<ApplicationEntry> input = ApplicationEntryDialog.show(workspaceHost, "Add Application", null);
        input.ifPresent(application -> runDatabaseUiAction("Unable to add application", () -> {
            projectService.upsertProjectApplication(application);
            reloadWorkspaceAndReselect(WorkspaceNodeType.APPLICATIONS, "project-applications");
            markDirty("Application added.");
        }));
    }

    /**
     * Launches the interactive workflow to modify the parameters of an existing project application.
     *
     * @param selected the application entry record to edit
     */
    private void editProjectApplication(ApplicationEntry selected) {
        Optional<ApplicationEntry> input = ApplicationEntryDialog.show(workspaceHost, "Edit Application", selected);
        input.ifPresent(application -> runDatabaseUiAction("Unable to update application", () -> {
            projectService.upsertProjectApplication(application);
            reloadWorkspaceAndReselect(WorkspaceNodeType.APPLICATIONS, "project-applications");
            markDirty("Application updated.");
        }));
    }

    /**
     * Displays a prompt to capture a name and registers a new test environment in the active project.
     */
    private void handleAddEnvironment() {
        TextInputDialog dialog = new TextInputDialog("QA");
        dialog.initOwner(currentWindowStage());
        dialog.setTitle("Add Environment");
        dialog.setHeaderText("Create a new environment");
        dialog.setContentText("Environment Name:");
        UiSupport.styleDialog(workspaceHost, dialog);
        dialog.showAndWait().ifPresent(name -> runDatabaseUiAction("Unable to create environment", () -> {
            EnvironmentRecord environment = projectService.createEnvironment(name);
            reloadWorkspaceAndReselect(WorkspaceNodeType.ENVIRONMENT, environment.getId());
            markDirty("Environment created.");
        }));
    }

    /**
     * Prompts for confirmation and permanently removes the currently focused environment from the project.
     */
    private void deleteCurrentEnvironment() {
        if (currentSelection == null || currentSelection.type() != WorkspaceNodeType.ENVIRONMENT) {
            return;
        }
        EnvironmentRecord environment = (EnvironmentRecord) currentSelection.payload();
        if (!confirm("Delete Environment", "Delete environment '" + environment.getName() + "'?")) {
            return;
        }
        runDatabaseUiAction("Unable to delete environment", () -> {
            projectService.deleteEnvironment(environment.getId());
            reloadWorkspaceAndReselect(WorkspaceNodeType.PROJECT, currentWorkspace().getProject().getId());
            markDirty("Environment deleted.");
        });
    }

    /**
     * Evaluates the active selection UI state and conditionally provisions a new test report.
     */
    private void createReportForCurrentEnvironment() {
        if (currentSelection != null && currentSelection.type() == WorkspaceNodeType.ENVIRONMENT) {
            createReportForEnvironment((EnvironmentRecord) currentSelection.payload());
        }
    }

    /**
     * Extends a named environment entity with a newly initialized, empty test execution report.
     *
     * @param environment the parent environment tracking the new report
     */
    private void createReportForEnvironment(EnvironmentRecord environment) {
        TextInputDialog dialog = new TextInputDialog("Test Execution Report");
        dialog.initOwner(currentWindowStage());
        dialog.setTitle("Create Report");
        dialog.setHeaderText("Create Test Execution Report");
        dialog.setContentText("Report Title:");
        UiSupport.styleDialog(workspaceHost, dialog);
        dialog.showAndWait().ifPresent(title -> runDatabaseUiAction("Unable to create report", () -> {
            ReportRecord report = projectService.createTestExecutionReport(environment.getId(), title);
            reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
            markDirty("Report created.");
        }));
    }

    /**
     * Replicates an existing report and all its embedded test execution data within the same environment.
     *
     * @param report the source record to duplicate
     */
    private void duplicateReport(ReportRecord report) {
        runDatabaseUiAction("Unable to duplicate report", () -> {
            ReportRecord duplicatedReport = projectService.copyReportToEnvironment(report.getId(), report.getEnvironmentId());
            reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, duplicatedReport.getId());
            markDirty("Report duplicated.");
        });
    }

    /**
     * Prompts for a target environment and relocates an existing report to the newly chosen parent.
     *
     * @param report the record to move
     */
    private void moveReport(ReportRecord report) {
        chooseTargetEnvironment(report.getEnvironmentId()).ifPresent(environmentId ->
                runDatabaseUiAction("Unable to move report", () -> {
                    projectService.moveReportToEnvironment(report.getId(), environmentId);
                    reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
                    markDirty("Report moved.");
                })
        );
    }

    /**
     * Prompts for a target boundary environment and duplicates a report entity into that separate scope.
     *
     * @param report the report entity to logically clone
     */
    private void copyReport(ReportRecord report) {
        chooseTargetEnvironment(report.getEnvironmentId()).ifPresent(environmentId ->
                runDatabaseUiAction("Unable to copy report", () -> {
                    ReportRecord copiedReport = projectService.copyReportToEnvironment(report.getId(), environmentId);
                    reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, copiedReport.getId());
                    markDirty("Report copied.");
                })
        );
    }

    /**
     * Requests user confirmation before permanently erasing a specified report and its associated data.
     *
     * @param report the report to delete
     */
    private void deleteReport(ReportRecord report) {
        if (!confirm("Delete Report", "Delete report '" + report.getTitle() + "'?")) {
            return;
        }
        runDatabaseUiAction("Unable to delete report", () -> {
            String environmentId = report.getEnvironmentId();
            projectService.deleteReport(report.getId());
            reloadWorkspaceAndReselect(WorkspaceNodeType.ENVIRONMENT, environmentId);
            markDirty("Report deleted.");
        });
    }

    /**
     * Given an arbitrary workspace node, traces the hierarchy map logic to find the underlying report record.
     *
     * @param selection the tree branch to interrogate
     * @return the governing contextual report, or null
     */
    static ReportRecord resolveReportSelection(WorkspaceNode selection) {
        return ToolbarAndExportCoordinator.resolveReportSelection(selection);
    }

    /**
     * Displays a selection modal offering all peer environments except the currently active context boundary.
     *
     * @param currentEnvironmentId the UUID of the environment holding the element
     * @return an optional containing the chosen UUID, or empty if cancelled
     */
    private Optional<String> chooseTargetEnvironment(String currentEnvironmentId) {
        List<EnvironmentRecord> candidateEnvironments = currentWorkspace().getEnvironments().stream()
                .filter(environment -> !Objects.equals(environment.getId(), currentEnvironmentId))
                .toList();
        if (candidateEnvironments.isEmpty()) {
            showInformation("Environment Selection", "Create another environment first.");
            return Optional.empty();
        }
        return EnvironmentSelectionDialog.show(workspaceHost, candidateEnvironments);
    }

    /**
     * Intercepts a UI request to change a report's target status, enforcing field validations before committing.
     *
     * @param report          the target report record
     * @param requestedStatus the desired new status
     */
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

        runDatabaseUiAction("Unable to update report status", () -> {
            projectService.updateReportStatus(report.getId(), requestedStatus);
            reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, report.getId());
            markDirty("Report status updated.");
        });
    }

    /**
     * Evaluates a report record to ensure all mandatory fields are populated prior to advancing its status.
     *
     * @param reportId    the internal identifier of the report
     * @param reportTitle the human-readable title of the report
     * @return a list of human-readable issues, or an empty list if valid
     */
    private List<String> validateReport(String reportId, String reportTitle) {
        try {
            Map<String, String> fields = projectService.loadReportFields(reportId);
            List<ApplicationEntry> applications = projectService.loadReportApplications(reportId);
            EnvironmentRecord environment = projectService.loadReportEnvironmentSnapshot(reportId);
            ExecutionReportSnapshot executionSnapshot = projectService.loadExecutionReportSnapshot(reportId);

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
            if (executionSnapshot.getRuns().isEmpty()) {
                issues.add("Execution Summary -> At least one execution run");
            } else {
                for (int index = 0; index < executionSnapshot.getRuns().size(); index++) {
                    validateExecutionRun(executionSnapshot.getRuns().get(index), index, issues);
                }
            }
            if (fields.getOrDefault("conclusion.overallConclusion", "").isBlank()) {
                issues.add("Conclusion -> Overall Conclusion");
            }
            return issues;
        } catch (SQLException | IllegalStateException exception) {
            LOGGER.warn("Unable to validate report '{}'.", reportId, exception);
            return List.of("Unable to validate report: " + normalizeOperationMessage(exception.getMessage(), "Operation failed."));
        }
    }

    /**
     * Validates deep constraint rules for an individual test execution run, appending any issues to the provided list.
     *
     * @param runSnapshot the execution run snapshot to evaluate
     * @param index       the numeric sequence position of the run
     * @param issues      a mutable list aggregating discovered validation failures
     */
    private void validateExecutionRun(ExecutionRunSnapshot runSnapshot, int index, List<String> issues) {
        ExecutionRunRecord run = runSnapshot.getRun();
        String prefix = "Execution Summary -> Run " + (index + 1);

        if (isBlank(run.getExecutionKey()) && isBlank(run.getSuiteName())) {
            issues.add(prefix + " -> Execution ID or Suite Name");
        }
        if (isBlank(run.getExecutionDate()) && isBlank(run.getStartDate()) && isBlank(run.getEndDate())) {
            issues.add(prefix + " -> Execution Date or Window");
        }

        LocalDate startDate = parseDate(run.getStartDate());
        LocalDate endDate = parseDate(run.getEndDate());
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            issues.add(prefix + " -> Execution End Date cannot be before Execution Start Date");
        }

        if (isBlank(run.getStatus())) {
            issues.add(prefix + " -> Status");
            return;
        }

        String normalizedStatus = normalizeResultStatus(run.getStatus());
        if ("FAIL".equals(normalizedStatus)
                && isBlank(run.getActualResult())
                && isBlank(run.getRelatedIssue())
                && isBlank(run.getDefectSummary())) {
            issues.add(prefix + " -> Actual Result, Related Issue, or Defect Summary for failed runs");
        }
        if ("BLOCKED".equals(normalizedStatus)
                && isBlank(run.getBlockedReason())
                && isBlank(run.getRemarks())
                && isBlank(run.getDefectSummary())) {
            issues.add(prefix + " -> Blocked Reason, Remarks, or Defect Summary");
        }
    }

    /**
     * Safely attempts to parse an arbitrary textual date string into a structured local date object.
     *
     * @param value the raw text value to parse
     * @return the successfully parsed date, or null if empty or invalid
     */
    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /**
     * A utility method to concisely determine if a string is null, entirely whitespace, or empty.
     *
     * @param value the string to check
     * @return true if the string is structurally blank
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Standardizes flexible external testing status strings into a bounded set of logical constant identifiers.
     *
     * @param value the untethered status input text
     * @return a capitalized, strictly normalized variant of the string
     */
    private String normalizeResultStatus(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return switch (value.trim().toUpperCase()) {
            case "PASSED" -> "PASS";
            case "FAILED" -> "FAIL";
            case "SKIPPED", "SKIP" -> "SKIPPED";
            default -> value.trim().toUpperCase();
        };
    }

    /**
     * Triggers a comprehensive reload of the project workspace tree and enforces a new UI node selection.
     *
     * @param nodeType the classification of the targeted node
     * @param nodeId   the datastore identifier associated with the node
     */
    private void reloadWorkspaceAndReselect(WorkspaceNodeType nodeType, String nodeId) {
        projectLifecycleCoordinator.reloadWorkspaceAndReselect(nodeType, nodeId);
    }

    /**
     * Rebuilds the active project workspace hierarchy while locking the previous scrollbar offset, and re-selects a given node.
     *
     * @param nodeType the classification of the node to target
     * @param nodeId   the unique datastore identifier mapped to the element
     */
    private void reloadWorkspaceAndReselectPreservingScroll(WorkspaceNodeType nodeType, String nodeId) {
        projectLifecycleCoordinator.reloadWorkspaceAndReselectPreservingScroll(nodeType, nodeId);
    }

    /**
     * Notifies the navigation components that a specific UI tree node requires a visual redraw action.
     *
     * @param node the node to refresh
     */
    private void updateWorkspaceNode(WorkspaceNode node) {
        if (workspaceNavigator != null) {
            workspaceNavigator.updateNode(node);
        }
    }

    /**
     * Automatically attempts to select the primary root project node within the navigator view.
     */
    private void selectProjectNode() {
        if (workspaceNavigator != null) {
            workspaceNavigator.selectProjectNode();
        }
    }

    /**
     * Programmatically forces the UI component tree to navigate to and select the unified applications category header.
     */
    private void selectApplicationsNode() {
        if (workspaceNavigator != null) {
            workspaceNavigator.selectApplicationsNode();
        }
    }

    /**
     * Executes an explicit UI navigation action to select a specific type and identifier entity in the workspace tree.
     *
     * @param nodeType the targeted classification structure of the element
     * @param nodeId   the contextual UUID value linking to the datastore
     */
    private void selectNode(WorkspaceNodeType nodeType, String nodeId) {
        if (workspaceNavigator != null) {
            workspaceNavigator.selectNode(nodeType, nodeId);
        }
    }

    /**
     * Intercepts tree view selection changes to synchronize contextual UI chrome controls dynamically.
     *
     * @param selection the newly focal workspace node mapping element
     */
    private void onWorkspaceSelectionChanged(WorkspaceNode selection) {
        currentSelection = selection;
        updateChrome();
    }

    /**
     * Directs the lifecycle execution engine to evaluate pending data changes and safely flush background saves to disk.
     */
    private void flushAutosave() {
        projectLifecycleCoordinator.flushAutosave();
    }

    /**
     * Cleanly unloads the currently active project and shuts down internal caching and tracking services.
     */
    private void closeCurrentProject() {
        projectLifecycleCoordinator.closeCurrentProject();
    }

    /**
     * Resets the application view layer returning the user to the initial launch window while hiding active projects.
     */
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

    /**
     * Flags the active project as possessing unsaved structural changes, queuing an autosave cycle.
     *
     * @param message a contextual label describing the modification
     */
    private void markDirty(String message) {
        projectLifecycleCoordinator.markDirty(message);
    }

    /**
     * Pushes a temporary informational text message to the bottom status bar spanning the workspace.
     *
     * @param message the status message to display
     */
    private void setInfoStatus(String message) {
        infoStatusLabel.setText(message == null || message.isBlank() ? "Ready" : message);
    }

    /**
     * Synchronizes the native operating system window title to reflect the active project state.
     *
     * @param title the string to apply as the window title
     */
    private void updateProjectWindowTitle(String title) {
        String resolvedTitle = title == null || title.isBlank() ? "ReportForge" : title;
        if (projectStage != null) {
            projectStage.setTitle(resolvedTitle);
        }
    }

    /**
     * Refreshes the custom application-rendered window chrome header, including the name and save-state icon.
     */
    private void updateProjectTitleDisplay() {
        ProjectWorkspace currentWorkspace = currentWorkspace();
        String projectName = currentWorkspace == null ? "ReportForge" : currentWorkspace.getProject().getName();
        if (projectWindowTitleLabel != null) {
            projectWindowTitleLabel.setText(projectName);
        }
        if (projectWindowTitleButton != null) {
            projectWindowTitleButton.setDisable(currentWorkspace == null || projectService.getCurrentSession() == null);
        }
        if (projectWindowSavedIndicatorSlot != null) {
            boolean showSavedIndicator = currentWorkspace != null
                    && projectService.getCurrentSession() != null
                    && !isDirty()
                    && !isProjectSaveInProgress();
            projectWindowSavedIndicatorSlot.setOpacity(showSavedIndicator ? 1.0 : 0.0);
        }
    }

    /**
     * Opens or cleanly collapses the contextual configuration dropdown attached to the central project title header component.
     */
    private void toggleProjectTitleMenu() {
        if (projectWindowTitleButton == null || projectWindowTitleButton.isDisabled()) {
            return;
        }
        if (projectWindowTitleMenu != null && projectWindowTitleMenu.isShowing()) {
            hideProjectTitleMenu();
            return;
        }

        ProjectSession session = projectService.getCurrentSession();
        if (session == null) {
            return;
        }

        ContextMenu titleMenu = buildProjectTitleMenu(session);
        projectWindowTitleMenu = titleMenu;
        projectWindowTitleButton.getStyleClass().add("active");
        titleMenu.setOnHidden(event -> {
            if (projectWindowTitleMenu == titleMenu) {
                projectWindowTitleMenu = null;
            }
            if (projectWindowTitleButton != null) {
                projectWindowTitleButton.getStyleClass().remove("active");
            }
        });
        titleMenu.setOnShown(event -> centerProjectTitleMenu(titleMenu));
        titleMenu.show(projectWindowTitleButton, Side.BOTTOM, 0, 8);
    }

    /**
     * Forcibly hides the configuration dropdown menu anchored to the project title header.
     */
    private void hideProjectTitleMenu() {
        if (projectWindowTitleMenu != null) {
            projectWindowTitleMenu.hide();
            projectWindowTitleMenu = null;
        }
        if (projectWindowTitleButton != null) {
            projectWindowTitleButton.getStyleClass().remove("active");
        }
    }

    /**
     * Assembles the layout structure and populates data for the project header context dropdown menu.
     *
     * @param session the currently active tracking project session
     * @return a structured, styleable context menu container
     */
    private ContextMenu buildProjectTitleMenu(ProjectSession session) {
        VBox popupContent = new VBox(
                10,
                createProjectTitleField("far-file-alt", session.projectFile().getFileName().toString()),
                createProjectTitleField("far-folder-open", resolveProjectDirectory(session.projectFile())),
                createProjectLastSavedLabel(session)
        );
        popupContent.getStyleClass().add("project-title-popup");
        UiSupport.installTextInputContextMenus(workspaceHost, popupContent);

        CustomMenuItem popupItem = new CustomMenuItem(popupContent, false);
        popupItem.getStyleClass().add("project-title-popup-item");
        return UiSupport.themedContextMenu(workspaceHost, popupItem);
    }

    /**
     * Creates an icon-prefixed, read-only text field meant for structural project metadata presentation.
     *
     * @param iconLiteral the icon identifier mapping
     * @param value       the immutable text to display inside the field
     * @return a formatted layout structure containing the controls
     */
    private HBox createProjectTitleField(String iconLiteral, String value) {
        TextField textField = UiSupport.readOnlyField(value);
        textField.setPrefColumnCount(40);
        HBox.setHgrow(textField, Priority.ALWAYS);

        HBox fieldRow = new HBox(10, IconSupport.createSectionIcon(iconLiteral), textField);
        fieldRow.setAlignment(Pos.CENTER_LEFT);
        fieldRow.getStyleClass().add("project-title-popup-row");
        return fieldRow;
    }

    /**
     * Generates a descriptive formatting label detailing the timestamp of the last successful file save.
     *
     * @param session the active project session holding the manifest snapshot
     * @return the populated textual label component
     */
    private Label createProjectLastSavedLabel(ProjectSession session) {
        Label lastSavedLabel = new Label("Last saved: " + formatProjectTimestamp(session.manifestData().updatedAt()));
        lastSavedLabel.getStyleClass().add("project-title-popup-meta");
        lastSavedLabel.setGraphic(IconSupport.createSectionIcon("far-clock"));
        return lastSavedLabel;
    }

    /**
     * Extracts and normalizes the textual path of the parent directory containing the defined project file.
     *
     * @param projectFile the absolute file path tracking the project
     * @return the directory string path
     */
    private String resolveProjectDirectory(Path projectFile) {
        Path absolutePath = projectFile.toAbsolutePath();
        Path parent = absolutePath.getParent();
        return parent == null ? absolutePath.toString() : parent.toString();
    }

    /**
     * Formats an ISO-8601 timestamp string into a human-readable, locale-aware textual date and time representation.
     *
     * @param value the raw timestamp variable to parse
     * @return the formatted date string, or the raw value if unavailable or invalid
     */
    private String formatProjectTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return "Not available";
        }
        try {
            return PROJECT_METADATA_TIME_FORMATTER.format(Instant.parse(value).atZone(ZoneId.systemDefault()));
        } catch (DateTimeParseException exception) {
            return value;
        }
    }

    /**
     * Visually centers the configuration context menu to sit directly underneath the customized title label.
     *
     * @param titleMenu the dropdown popup container to align
     */
    private void centerProjectTitleMenu(ContextMenu titleMenu) {
        if (titleMenu == null || projectWindowTitleLabel == null || projectWindowTitleButton == null) {
            return;
        }

        Bounds labelBounds = projectWindowTitleLabel.localToScreen(projectWindowTitleLabel.getBoundsInLocal());
        Bounds buttonBounds = projectWindowTitleButton.localToScreen(projectWindowTitleButton.getBoundsInLocal());
        if (labelBounds == null || buttonBounds == null) {
            return;
        }

        Screen targetScreen = Screen.getScreensForRectangle(
                labelBounds.getMinX(),
                labelBounds.getMinY(),
                Math.max(1, labelBounds.getWidth()),
                Math.max(1, labelBounds.getHeight())
        ).stream().findFirst().orElse(Screen.getPrimary());
        Rectangle2D visualBounds = targetScreen.getVisualBounds();

        double desiredX = labelBounds.getMinX() + (labelBounds.getWidth() - titleMenu.getWidth()) / 2.0;
        double clampedX = Math.max(
                visualBounds.getMinX() + 8,
                Math.min(desiredX, visualBounds.getMaxX() - titleMenu.getWidth() - 8)
        );
        double desiredY = buttonBounds.getMaxY() + 8;
        double clampedY = Math.max(visualBounds.getMinY() + 8, desiredY);

        titleMenu.setAnchorX(clampedX);
        titleMenu.setAnchorY(clampedY);
    }

    /**
     * Resolves the primary active window stage (either the startup screen or the workspace project interface) available to the user.
     *
     * @return the currently visible host stage
     */
    private Stage currentWindowStage() {
        if (projectStage != null && projectStage.isShowing()) {
            return projectStage;
        }
        return primaryStage;
    }

    /**
     * Assigns window dragging behaviors to the startup screen, facilitating boundary movement without traditional window borders.
     */
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

    /**
     * Centralized initialization routine hooking all drag and resize interactions into the custom project window structure.
     */
    private void wireProjectWindowInteractions() {
        wireProjectWindowDragging();
        wireProjectWindowResizing();
    }

    /**
     * Associates pointer listeners to the custom workspace header allowing the user to logically drag and move the window.
     */
    private void wireProjectWindowDragging() {
        if (projectWindowTitleBar == null) {
            return;
        }
        projectWindowTitleBar.setOnMousePressed(event -> {
            if (projectStage == null || event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            projectWindowDragOffsetX = event.getSceneX();
            projectWindowDragOffsetY = event.getSceneY();
        });
        projectWindowTitleBar.setOnMouseDragged(event -> {
            if (projectStage == null || !projectStage.isShowing() || projectWindowMaximized || !event.isPrimaryButtonDown()) {
                return;
            }
            if (projectWindowActiveResizeCursor != Cursor.DEFAULT) {
                return;
            }
            projectStage.setX(event.getScreenX() - projectWindowDragOffsetX);
            projectStage.setY(event.getScreenY() - projectWindowDragOffsetY);
        });
        projectWindowTitleBar.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                toggleProjectWindowMaximized();
            }
        });
    }

    /**
     * Configures the complex boundary sizing event trackers, evaluating custom cursors and mathematical structural bounds resizing actions.
     */
    private void wireProjectWindowResizing() {
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            if (projectStage == null || projectWindowMaximized || projectWindowActiveResizeCursor != Cursor.DEFAULT) {
                scene.setCursor(Cursor.DEFAULT);
                return;
            }
            scene.setCursor(resolveProjectWindowResizeCursor(event.getSceneX(), event.getSceneY()));
        });
        scene.addEventFilter(MouseEvent.MOUSE_EXITED, event -> {
            if (projectWindowActiveResizeCursor == Cursor.DEFAULT) {
                scene.setCursor(Cursor.DEFAULT);
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (projectStage == null || projectWindowMaximized || event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            Cursor cursor = resolveProjectWindowResizeCursor(event.getSceneX(), event.getSceneY());
            if (cursor == Cursor.DEFAULT) {
                return;
            }
            projectWindowActiveResizeCursor = cursor;
            projectWindowResizeStartScreenX = event.getScreenX();
            projectWindowResizeStartScreenY = event.getScreenY();
            projectWindowResizeStartX = projectStage.getX();
            projectWindowResizeStartY = projectStage.getY();
            projectWindowResizeStartWidth = projectStage.getWidth();
            projectWindowResizeStartHeight = projectStage.getHeight();
            event.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (projectWindowActiveResizeCursor == Cursor.DEFAULT || projectStage == null || projectWindowMaximized) {
                return;
            }
            resizeProjectWindow(event);
            event.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (projectWindowActiveResizeCursor == Cursor.DEFAULT) {
                return;
            }
            projectWindowActiveResizeCursor = Cursor.DEFAULT;
            scene.setCursor(resolveProjectWindowResizeCursor(event.getSceneX(), event.getSceneY()));
        });
    }

    /**
     * Examines current pointer coordinates near the application window edge bounding box and assigns appropriate directional cursors.
     *
     * @param sceneX the internal X-coordinate mapped to the application scene
     * @param sceneY the internal Y-coordinate mapped to the application scene
     * @return the resulting visual resize cursor, or DEFAULT if no edge is hovered
     */
    private Cursor resolveProjectWindowResizeCursor(double sceneX, double sceneY) {
        if (scene == null || projectWindowMaximized) {
            return Cursor.DEFAULT;
        }
        double width = scene.getWidth();
        double height = scene.getHeight();
        boolean left = sceneX >= 0 && sceneX <= WINDOW_RESIZE_MARGIN;
        boolean right = sceneX >= width - WINDOW_RESIZE_MARGIN && sceneX <= width;
        boolean top = sceneY >= 0 && sceneY <= WINDOW_RESIZE_MARGIN;
        boolean bottom = sceneY >= height - WINDOW_RESIZE_MARGIN && sceneY <= height;

        if (left && top) {
            return Cursor.NW_RESIZE;
        }
        if (right && top) {
            return Cursor.NE_RESIZE;
        }
        if (left && bottom) {
            return Cursor.SW_RESIZE;
        }
        if (right && bottom) {
            return Cursor.SE_RESIZE;
        }
        if (left) {
            return Cursor.W_RESIZE;
        }
        if (right) {
            return Cursor.E_RESIZE;
        }
        if (top) {
            return Cursor.N_RESIZE;
        }
        if (bottom) {
            return Cursor.S_RESIZE;
        }
        return Cursor.DEFAULT;
    }

    /**
     * Translates drag mathematics into concrete structural boundary adjustments on the active application window stage.
     *
     * @param event the triggering interface mouse event carrying pointer deltas
     */
    private void resizeProjectWindow(MouseEvent event) {
        double deltaX = event.getScreenX() - projectWindowResizeStartScreenX;
        double deltaY = event.getScreenY() - projectWindowResizeStartScreenY;
        double minWidth = Math.max(projectStage.getMinWidth(), 1);
        double minHeight = Math.max(projectStage.getMinHeight(), 1);
        double newX = projectWindowResizeStartX;
        double newY = projectWindowResizeStartY;
        double newWidth = projectWindowResizeStartWidth;
        double newHeight = projectWindowResizeStartHeight;

        if (projectWindowActiveResizeCursor == Cursor.W_RESIZE
                || projectWindowActiveResizeCursor == Cursor.NW_RESIZE
                || projectWindowActiveResizeCursor == Cursor.SW_RESIZE) {
            double candidateWidth = projectWindowResizeStartWidth - deltaX;
            if (candidateWidth < minWidth) {
                newX = projectWindowResizeStartX + (projectWindowResizeStartWidth - minWidth);
                newWidth = minWidth;
            } else {
                newX = projectWindowResizeStartX + deltaX;
                newWidth = candidateWidth;
            }
        }
        if (projectWindowActiveResizeCursor == Cursor.E_RESIZE
                || projectWindowActiveResizeCursor == Cursor.NE_RESIZE
                || projectWindowActiveResizeCursor == Cursor.SE_RESIZE) {
            newWidth = Math.max(minWidth, projectWindowResizeStartWidth + deltaX);
        }
        if (projectWindowActiveResizeCursor == Cursor.N_RESIZE
                || projectWindowActiveResizeCursor == Cursor.NE_RESIZE
                || projectWindowActiveResizeCursor == Cursor.NW_RESIZE) {
            double candidateHeight = projectWindowResizeStartHeight - deltaY;
            if (candidateHeight < minHeight) {
                newY = projectWindowResizeStartY + (projectWindowResizeStartHeight - minHeight);
                newHeight = minHeight;
            } else {
                newY = projectWindowResizeStartY + deltaY;
                newHeight = candidateHeight;
            }
        }
        if (projectWindowActiveResizeCursor == Cursor.S_RESIZE
                || projectWindowActiveResizeCursor == Cursor.SE_RESIZE
                || projectWindowActiveResizeCursor == Cursor.SW_RESIZE) {
            newHeight = Math.max(minHeight, projectWindowResizeStartHeight + deltaY);
        }

        projectStage.setX(newX);
        projectStage.setY(newY);
        projectStage.setWidth(newWidth);
        projectStage.setHeight(newHeight);
    }

    /**
     * Alternates the visual state of the project window cleanly betweeen its maximized sequence bounds and manually customized bounds.
     */
    private void toggleProjectWindowMaximized() {
        if (projectWindowMaximized) {
            restoreProjectWindow();
        } else {
            maximizeProjectWindow();
        }
    }

    /**
     * Caches the previous project window boundaries and forcefully expands the primary application layer to encompass the visible screen area.
     */
    private void maximizeProjectWindow() {
        if (projectStage == null || projectWindowMaximized) {
            return;
        }
        projectWindowRestoreX = projectStage.getX();
        projectWindowRestoreY = projectStage.getY();
        projectWindowRestoreWidth = projectStage.getWidth();
        projectWindowRestoreHeight = projectStage.getHeight();
        applyProjectWindowMaximizedBounds();
        projectWindowMaximized = true;
        updateProjectWindowMaximizeButton();
    }

    /**
     * Retrieves the previously cached structure dimension values and restores the layout to its un-maximized formatting parameters context.
     */
    private void restoreProjectWindow() {
        if (projectStage == null) {
            return;
        }
        projectWindowMaximized = false;
        if (Double.isNaN(projectWindowRestoreWidth) || Double.isNaN(projectWindowRestoreHeight)) {
            projectStage.setWidth(PROJECT_WINDOW_WIDTH);
            projectStage.setHeight(PROJECT_WINDOW_HEIGHT);
            projectStage.centerOnScreen();
        } else {
            projectStage.setX(projectWindowRestoreX);
            projectStage.setY(projectWindowRestoreY);
            projectStage.setWidth(projectWindowRestoreWidth);
            projectStage.setHeight(projectWindowRestoreHeight);
        }
        updateProjectWindowMaximizeButton();
    }

    /**
     * Projects and clamps the custom application window to structurally attach to the precise visual bounds of the active physical display.
     */
    private void applyProjectWindowMaximizedBounds() {
        if (projectStage == null) {
            return;
        }
        Rectangle2D visualBounds = Screen.getScreensForRectangle(
                        projectStage.getX(),
                        projectStage.getY(),
                        Math.max(projectStage.getWidth(), 1),
                        Math.max(projectStage.getHeight(), 1)
                ).stream()
                .findFirst()
                .orElse(Screen.getPrimary())
                .getVisualBounds();
        projectStage.setX(visualBounds.getMinX());
        projectStage.setY(visualBounds.getMinY());
        projectStage.setWidth(visualBounds.getWidth());
        projectStage.setHeight(visualBounds.getHeight());
        scene.setCursor(Cursor.DEFAULT);
        projectWindowActiveResizeCursor = Cursor.DEFAULT;
    }

    /**
     * Evaluates the contextual display status tracking sequence variables and updates the maximized icon accordingly.
     */
    private void updateProjectWindowMaximizeButton() {
        if (projectWindowMaximizeButton == null) {
            return;
        }
        projectWindowMaximizeButton.setGraphic(IconSupport.createWindowControlIcon(
                projectWindowMaximized ? "far-window-restore" : "far-window-maximize"
        ));
    }

    /**
     * Attempts to minimize and suspend the presentation bounds of the initial start screen.
     */
    private void minimizeStartWindow() {
        if (primaryStage != null) {
            primaryStage.setIconified(true);
        }
    }

    /**
     * Triggers the operating system action to minimize the active project window structure.
     */
    private void minimizeProjectWindow() {
        if (projectStage != null) {
            projectStage.setIconified(true);
        }
    }

    /**
     * Directs the local application environment to gracefully dismiss the start sequence structure interface.
     */
    private void closeStartWindow() {
        if (primaryStage != null) {
            primaryStage.close();
        }
    }

    /**
     * Initiates the graceful teardown and closing sequence of the active project workspace window element.
     */
    private void closeProjectWindow() {
        if (currentWorkspace() != null) {
            closeCurrentProject();
            return;
        }
        if (projectStage != null) {
            projectStage.hide();
        }
    }

    /**
     * Initiates a yes/no dialog requiring explicit user approval before proceeding.
     *
     * @param title   the dialog header title
     * @param message the content prompt to detail
     * @return true if affirmatively confirmed, false otherwise
     */
    private boolean confirm(String title, String message) {
        return UiSupport.confirm(workspaceHost, title, message);
    }

    /**
     * Displays a simple informational modal to the user containing a title and a message body.
     *
     * @param title   the header title
     * @param message the informational content
     */
    private void showInformation(String title, String message) {
        UiSupport.showInformation(workspaceHost, title, message);
    }

    /**
     * Functional interface for database-related UI actions that might throw SQL exceptions.
     */
    @FunctionalInterface
    private interface DatabaseUiAction {
        void run() throws SQLException;
    }

    /**
     * Functional interface for project IO-related UI actions that might throw SQL or IO exceptions.
     */
    @FunctionalInterface
    private interface ProjectIoUiAction {
        void run() throws IOException, SQLException;
    }

    /**
     * Executes a database UI action, catching expected exceptions and displaying them formatted to the user.
     *
     * @param title  the context title for potential error messages
     * @param action the functional action to execute
     */
    private void runDatabaseUiAction(String title, DatabaseUiAction action) {
        try {
            action.run();
        } catch (SQLException | IllegalArgumentException | IllegalStateException exception) {
            showHandledUiError(title, exception);
        }
    }

    /**
     * Executes an IO and database UI action, catching expected exceptions and displaying them formatted to the user.
     *
     * @param title  the context title for potential error messages
     * @param action the functional action to execute
     */
    private void runProjectIoUiAction(String title, ProjectIoUiAction action) {
        try {
            action.run();
        } catch (IOException | SQLException | IllegalArgumentException | IllegalStateException exception) {
            showHandledUiError(title, exception);
        }
    }

    /**
     * Translates a caught exception into a standardized, user-facing error modal dialog.
     *
     * @param title     the context title describing the failed operation
     * @param exception the caught exception
     */
    private void showHandledUiError(String title, Exception exception) {
        showError(title, normalizeOperationMessage(exception.getMessage(), title + "."), exception);
    }

    /**
     * Provides a standard fallback message if the caught exception does not contain a coherent detail string.
     *
     * @param message         the original exception message
     * @param fallbackMessage the fallback text to use if the message is blank
     * @return a normalized string suitable for UI display
     */
    private String normalizeOperationMessage(String message, String fallbackMessage) {
        if (message == null || message.isBlank()) {
            return fallbackMessage;
        }
        return message;
    }

    /**
     * Displays a simple error modal to the user containing a title and a message body.
     *
     * @param title   the header title
     * @param message the error content
     */
    private void showError(String title, String message) {
        UiSupport.showError(workspaceHost, title, message);
    }

    /**
     * Displays an error modal to the user and logs the underlying exception stacktrace to the application logs.
     *
     * @param title     the header title
     * @param message   the error content
     * @param exception the throwable to log
     */
    private void showError(String title, String message, Throwable exception) {
        LOGGER.error("{}: {}", title, message, exception);
        showError(title, message);
    }

    private final class ToolbarActionBridge implements ToolbarAndExportCoordinator.Support {
        @Override
        public void handleNewProject() {
            ReportForgeApplication.this.handleNewProject();
        }

        @Override
        public void handleOpenProject() {
            ReportForgeApplication.this.handleOpenProject();
        }

        @Override
        public void flushAutosave() {
            ReportForgeApplication.this.flushAutosave();
        }

        @Override
        public void closeCurrentProject() {
            ReportForgeApplication.this.closeCurrentProject();
        }

        @Override
        public void handleAddEnvironment() {
            ReportForgeApplication.this.handleAddEnvironment();
        }

        @Override
        public void selectApplicationsNode() {
            ReportForgeApplication.this.selectApplicationsNode();
        }

        @Override
        public void addProjectApplication() {
            ReportForgeApplication.this.addProjectApplication();
        }

        @Override
        public void createReportForCurrentEnvironment() {
            ReportForgeApplication.this.createReportForCurrentEnvironment();
        }

        @Override
        public void deleteCurrentEnvironment() {
            ReportForgeApplication.this.deleteCurrentEnvironment();
        }

        @Override
        public void handleReportStatusChange(ReportRecord report, ReportStatus requestedStatus) {
            ReportForgeApplication.this.handleReportStatusChange(report, requestedStatus);
        }

        @Override
        public Button createThemeToggleButton(boolean compact) {
            return ReportForgeApplication.this.createThemeToggleButton(compact);
        }

        @Override
        public void setInfoStatus(String message) {
            ReportForgeApplication.this.setInfoStatus(message);
        }

        @Override
        public void showHandledUiError(String title, Exception exception) {
            ReportForgeApplication.this.showHandledUiError(title, exception);
        }
    }

    private final class ProjectServiceBridge implements ProjectLifecycleCoordinator.ProjectAccess {
        @Override
        public void createProject(Path projectPath, String projectName, String initialEnvironmentName, List<String> applicationNames)
                throws IOException, SQLException {
            projectService.createProject(projectPath, projectName, initialEnvironmentName, applicationNames);
        }

        @Override
        public void openProject(Path projectPath) throws IOException, SQLException {
            projectService.openProject(projectPath);
        }

        @Override
        public ProjectWorkspace loadWorkspace() throws SQLException {
            return projectService.loadWorkspace();
        }

        @Override
        public void saveProject() throws IOException, SQLException {
            projectService.saveProject();
        }

        @Override
        public ProjectSession getCurrentSession() {
            return projectService.getCurrentSession();
        }

        @Override
        public void closeCurrentSession() {
            projectService.closeCurrentSession();
        }
    }

    private final class RecentProjectsBridge implements ProjectLifecycleCoordinator.RecentProjectsAccess {
        @Override
        public void touchProject(Path projectPath, String name) {
            recentProjectsService.touchProject(projectPath, name);
        }

        @Override
        public void removeProject(Path projectPath) {
            recentProjectsService.removeProject(projectPath);
        }
    }

    private final class AutosaveBridge implements ProjectLifecycleCoordinator.AutosaveScheduler {
        @Override
        public void schedule() {
            autosavePause.playFromStart();
        }

        @Override
        public void cancel() {
            autosavePause.stop();
        }
    }

    private final class LifecycleUiBridge implements ProjectLifecycleCoordinator.Support {
        @Override
        public WorkspaceNode getCurrentSelection() {
            return currentSelection;
        }

        @Override
        public void clearCurrentSelection() {
            currentSelection = null;
        }

        @Override
        public double captureCenterScrollVvalue() {
            return workspaceNavigator == null ? Double.NaN : workspaceNavigator.captureCenterScrollVvalue();
        }

        @Override
        public void rebuildWorkspaceTree() {
            if (workspaceNavigator != null) {
                workspaceNavigator.rebuildTree();
            }
        }

        @Override
        public void selectNode(WorkspaceNodeType nodeType, String nodeId) {
            if (workspaceNavigator != null) {
                workspaceNavigator.selectNode(nodeType, nodeId);
            }
        }

        @Override
        public void restoreCenterScrollVvalue(double scrollVvalue) {
            if (workspaceNavigator != null) {
                workspaceNavigator.restoreCenterScrollVvalue(scrollVvalue);
            }
        }

        @Override
        public void selectProjectNode() {
            ReportForgeApplication.this.selectProjectNode();
        }

        @Override
        public void showWorkspace() {
            ReportForgeApplication.this.showWorkspace();
        }

        @Override
        public void reopenStartScreen() {
            ReportForgeApplication.this.reopenStartScreen();
        }

        @Override
        public void hideProjectTitleMenu() {
            ReportForgeApplication.this.hideProjectTitleMenu();
        }

        @Override
        public void clearWorkspaceView() {
            if (root != null) {
                root.setCenter(null);
            }
        }

        @Override
        public void updateChrome() {
            ReportForgeApplication.this.updateChrome();
        }

        @Override
        public void updateProjectTitleDisplay() {
            ReportForgeApplication.this.updateProjectTitleDisplay();
        }

        @Override
        public void setInfoStatus(String message) {
            ReportForgeApplication.this.setInfoStatus(message);
        }

        @Override
        public void showError(String title, String message, Throwable exception) {
            ReportForgeApplication.this.showError(title, message, exception);
        }
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
            return currentWorkspace();
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
        public void reloadWorkspaceAndReselectPreservingScroll(WorkspaceNodeType nodeType, String nodeId) {
            ReportForgeApplication.this.reloadWorkspaceAndReselectPreservingScroll(nodeType, nodeId);
        }

        @Override
        public void selectNode(WorkspaceNodeType nodeType, String nodeId) {
            ReportForgeApplication.this.selectNode(nodeType, nodeId);
        }

        @Override
        public void updateNode(WorkspaceNode node) {
            ReportForgeApplication.this.updateWorkspaceNode(node);
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
        public void duplicateReport(ReportRecord report) {
            ReportForgeApplication.this.duplicateReport(report);
        }

        @Override
        public void moveReport(ReportRecord report) {
            ReportForgeApplication.this.moveReport(report);
        }

        @Override
        public void copyReport(ReportRecord report) {
            ReportForgeApplication.this.copyReport(report);
        }

        @Override
        public void deleteReport(ReportRecord report) {
            ReportForgeApplication.this.deleteReport(report);
        }

        @Override
        public void handleReportStatusChange(ReportRecord report, ReportStatus requestedStatus) {
            ReportForgeApplication.this.handleReportStatusChange(report, requestedStatus);
        }
    }
}
