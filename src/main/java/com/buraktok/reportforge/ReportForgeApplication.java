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
import com.buraktok.reportforge.ui.ExecutionRunWorkspaceNode;
import com.buraktok.reportforge.ui.WorkspaceHost;
import com.buraktok.reportforge.ui.WorkspaceNavigator;
import com.buraktok.reportforge.ui.WorkspaceNode;
import com.buraktok.reportforge.ui.WorkspaceNodeType;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Side;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToolBar;
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

import java.awt.Desktop;
import java.nio.file.Path;
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
    private ToolBar leftToolBar;
    private HBox centerToolbarBox;
    private ToolBar rightToolBar;
    private Button projectWindowTitleButton;
    private Label projectWindowTitleLabel;
    private Label infoStatusLabel;
    private Button projectWindowMaximizeButton;
    private StackPane projectWindowSavedIndicatorSlot;
    private ContextMenu projectWindowTitleMenu;

    private ProjectWorkspace currentWorkspace;
    private WorkspaceNode currentSelection;
    private boolean dirty;
    private boolean projectSaveInProgress;

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

    private void initializeProjectWindow() {
        root = new BorderPane();
        root.getStyleClass().addAll("app-root", "start-screen-root", "workspace-root");

        buildTopToolbar();
        buildStatusBar();
        projectWindowTitleBar = buildProjectWindowTitleBar();
        toolbarContainer = buildToolbarContainer();
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
            if (currentWorkspace != null) {
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

    private Node buildToolbarContainer() {
        BorderPane toolbarPane = new BorderPane();
        toolbarPane.setPadding(new Insets(10, 16, 10, 16));
        toolbarPane.getStyleClass().add("top-chrome");
        toolbarPane.setLeft(leftToolBar);
        toolbarPane.setCenter(centerToolbarBox);
        toolbarPane.setRight(rightToolBar);
        return toolbarPane;
    }

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

    private Node buildStatusBarContainer() {
        BorderPane statusPane = new BorderPane();
        statusPane.setPadding(new Insets(4, 14, 5, 14));
        statusPane.getStyleClass().add("status-chrome");
        statusPane.setLeft(infoStatusLabel);
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
        infoStatusLabel.getStyleClass().add("status-info");
    }

    private void updateChrome() {
        leftToolBar.getItems().setAll(
                createToolbarButton("New", "fas-plus", event -> handleNewProject()),
                createToolbarButton("Open", "far-folder-open", event -> handleOpenProject()),
                createToolbarButton("Save", "fas-save", event -> flushAutosave(), currentWorkspace != null),
                createToolbarButton("Close Project", "fas-times", event -> closeCurrentProject(), currentWorkspace != null)
        );

        if (currentWorkspace == null) {
            hideProjectTitleMenu();
            root.setTop(null);
            root.setBottom(null);
            centerToolbarBox.getChildren().clear();
            rightToolBar.getItems().setAll(createToolbarButton(
                    "Export",
                    "fas-file-export",
                    event -> showInformation("Export", "Open a report to export it."),
                    false
            ));
            updateStageTitle();
            return;
        }

        root.setTop(projectWindowChrome);
        root.setBottom(statusBarContainer);
        updateCenterToolbar();
        updateRightToolbar();
        updateStageTitle();
    }

    private void updateCenterToolbar() {
        centerToolbarBox.getChildren().clear();
        if (currentSelection == null) {
            return;
        }

        switch (currentSelection.type()) {
            case PROJECT -> {
                centerToolbarBox.getChildren().add(createToolbarButton("Add Environment", "fas-plus", event -> handleAddEnvironment()));
                centerToolbarBox.getChildren().add(createToolbarButton("Applications", "fas-list", event -> selectApplicationsNode()));
            }
            case APPLICATIONS -> centerToolbarBox.getChildren().add(createToolbarButton("Add Application", "fas-plus", event -> addProjectApplication()));
            case ENVIRONMENT -> {
                centerToolbarBox.getChildren().add(createToolbarButton("New Report", "fas-file-alt", event -> createReportForCurrentEnvironment()));
                centerToolbarBox.getChildren().add(createToolbarButton("Delete Environment", "fas-trash", event -> deleteCurrentEnvironment()));
            }
            case REPORT, EXECUTION_RUN -> {
                ComboBox<ReportStatus> statusComboBox = new ComboBox<>(FXCollections.observableArrayList(ReportStatus.values()));
                statusComboBox.setConverter(UiSupport.reportStatusConverter());
                ReportRecord reportRecord = resolveSelectedReport();
                statusComboBox.setValue(reportRecord.getStatus());
                statusComboBox.setOnAction(event -> handleReportStatusChange(reportRecord, statusComboBox.getValue()));
                centerToolbarBox.getChildren().add(statusComboBox);
            }
        }
    }

    private void updateRightToolbar() {
        ReportRecord selectedReport = resolveSelectedReportNode();
        boolean reportSelected = selectedReport != null;
        Button themeButton = createThemeToggleButton(true);
        Button exportButton = createToolbarButton(
                "Export",
                "fas-file-export",
                event -> { },
                reportSelected
        );
        exportButton.setOnAction(event -> showExportMenu(exportButton));
        exportButton.getStyleClass().add("accent-button");
        rightToolBar.getItems().setAll(themeButton, exportButton);
    }

    private void updateStageTitle() {
        String stageTitle = currentWorkspace == null
                ? "ReportForge"
                : "ReportForge - " + currentWorkspace.getProject().getName();
        updateProjectWindowTitle(stageTitle);
        updateProjectTitleDisplay();
    }

    private Button createToolbarButton(String text, String iconLiteral, java.util.function.Consumer<javafx.event.ActionEvent> action) {
        return createToolbarButton(text, iconLiteral, action, true);
    }

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

    private Button createWindowControlButton(String iconLiteral, Runnable action, boolean closeButton) {
        Button button = new Button();
        button.setFocusTraversable(false);
        button.setOnAction(event -> action.run());
        button.setGraphic(IconSupport.createWindowControlIcon(iconLiteral));
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().addAll("window-control-button", closeButton ? "window-close-button" : "window-minimize-button");
        return button;
    }

    private void toggleTheme() {
        hideProjectTitleMenu();
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
            projectSaveInProgress = false;
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
            projectSaveInProgress = false;
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

    private void duplicateReport(ReportRecord report) {
        try {
            ReportRecord duplicatedReport = projectService.copyReportToEnvironment(report.getId(), report.getEnvironmentId());
            reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, duplicatedReport.getId());
            markDirty("Report duplicated.");
        } catch (Exception exception) {
            showError("Unable to duplicate report", exception.getMessage());
        }
    }

    private void moveReport(ReportRecord report) {
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

    private void copyReport(ReportRecord report) {
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

    private void deleteReport(ReportRecord report) {
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

    private ReportRecord resolveSelectedReport() {
        if (currentSelection == null) {
            return null;
        }
        return switch (currentSelection.type()) {
            case REPORT -> (ReportRecord) currentSelection.payload();
            case EXECUTION_RUN -> ((ExecutionRunWorkspaceNode) currentSelection.payload()).report();
            default -> null;
        };
    }

    private ReportRecord resolveSelectedReportNode() {
        if (currentSelection == null || currentSelection.type() != WorkspaceNodeType.REPORT) {
            return null;
        }
        return (ReportRecord) currentSelection.payload();
    }

    private void showExportMenu(Button exportButton) {
        ReportRecord selectedReport = resolveSelectedReportNode();
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
        exportMenu.show(exportButton, Side.BOTTOM, 0, 6);
    }

    private void exportReportAsHtml(ReportRecord report) {
        if (report == null || currentWorkspace == null) {
            return;
        }
        try {
            Map<String, String> fields = projectService.loadReportFields(report.getId());
            List<ApplicationEntry> applications = projectService.loadReportApplications(report.getId());
            EnvironmentRecord environment = projectService.loadReportEnvironmentSnapshot(report.getId());
            ExecutionReportSnapshot executionSnapshot = projectService.loadExecutionReportSnapshot(report.getId());

            FileChooser chooser = new FileChooser();
            chooser.setTitle("Export HTML Report");
            chooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("HTML Files", "*.html"));
            chooser.setInitialFileName(sanitizeExportFileName(report.getTitle()) + ".html");

            java.io.File selectedFile = chooser.showSaveDialog(currentWindowStage());
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
                    projectService::resolveProjectPath
            );
            setInfoStatus("HTML report exported.");
            if (UiSupport.showExportComplete(workspaceHost, exportPath)) {
                openExportedHtml(exportPath);
            }
        } catch (Exception exception) {
            showError("Unable to export HTML report", exception.getMessage());
        }
    }

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
        } catch (Exception exception) {
            showError("Unable to open exported HTML", exception.getMessage());
        }
    }

    private Path ensureHtmlExtension(Path exportPath) {
        if (exportPath == null) {
            return null;
        }
        String fileName = exportPath.getFileName() == null ? "" : exportPath.getFileName().toString();
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".html")) {
            return exportPath;
        }
        return exportPath.resolveSibling(fileName + ".html");
    }

    private String sanitizeExportFileName(String reportTitle) {
        String baseName = reportTitle == null || reportTitle.isBlank() ? "report-export" : reportTitle.trim();
        String sanitized = baseName.replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
        return sanitized.isBlank() ? "report-export" : sanitized;
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
        } catch (Exception exception) {
            return List.of("Unable to validate report: " + exception.getMessage());
        }
    }

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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

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

    private void updateWorkspaceNode(WorkspaceNode node) {
        if (workspaceNavigator != null) {
            workspaceNavigator.updateNode(node);
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
        if (currentWorkspace == null || projectService.getCurrentSession() == null || !dirty || projectSaveInProgress) {
            return;
        }
        try {
            projectSaveInProgress = true;
            updateProjectTitleDisplay();
            projectService.saveProject();
            dirty = false;
            projectSaveInProgress = false;
            recentProjectsService.touchProject(projectService.getCurrentSession().projectFile(), currentWorkspace.getProject().getName());
            updateProjectTitleDisplay();
        } catch (Exception exception) {
            projectSaveInProgress = false;
            setInfoStatus("Autosave failed.");
            updateProjectTitleDisplay();
            showError("Unable to save project", exception.getMessage());
        }
    }

    private void closeCurrentProject() {
        if (currentWorkspace == null || projectService.getCurrentSession() == null || projectSaveInProgress) {
            return;
        }

        autosavePause.stop();
        try {
            projectSaveInProgress = true;
            updateProjectTitleDisplay();
            projectService.saveProject();
            projectSaveInProgress = false;
            recentProjectsService.touchProject(projectService.getCurrentSession().projectFile(), currentWorkspace.getProject().getName());
            resetCurrentProjectState();
            reopenStartScreen();
        } catch (Exception exception) {
            projectSaveInProgress = false;
            setInfoStatus("Unable to close project.");
            updateProjectTitleDisplay();
            showError("Unable to close project", exception.getMessage());
        }
    }

    private void resetCurrentProjectState() {
        hideProjectTitleMenu();
        projectService.closeCurrentSession();
        currentWorkspace = null;
        currentSelection = null;
        dirty = false;
        projectSaveInProgress = false;
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
        updateProjectTitleDisplay();
        autosavePause.playFromStart();
    }

    private void setInfoStatus(String message) {
        infoStatusLabel.setText(message == null || message.isBlank() ? "Ready" : message);
    }

    private void updateProjectWindowTitle(String title) {
        String resolvedTitle = title == null || title.isBlank() ? "ReportForge" : title;
        if (projectStage != null) {
            projectStage.setTitle(resolvedTitle);
        }
    }

    private void updateProjectTitleDisplay() {
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
                    && !dirty
                    && !projectSaveInProgress;
            projectWindowSavedIndicatorSlot.setOpacity(showSavedIndicator ? 1.0 : 0.0);
        }
    }

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

    private void hideProjectTitleMenu() {
        if (projectWindowTitleMenu != null) {
            projectWindowTitleMenu.hide();
            projectWindowTitleMenu = null;
        }
        if (projectWindowTitleButton != null) {
            projectWindowTitleButton.getStyleClass().remove("active");
        }
    }

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

    private HBox createProjectTitleField(String iconLiteral, String value) {
        TextField textField = UiSupport.readOnlyField(value);
        textField.setPrefColumnCount(40);
        HBox.setHgrow(textField, Priority.ALWAYS);

        HBox fieldRow = new HBox(10, IconSupport.createSectionIcon(iconLiteral), textField);
        fieldRow.setAlignment(Pos.CENTER_LEFT);
        fieldRow.getStyleClass().add("project-title-popup-row");
        return fieldRow;
    }

    private Label createProjectLastSavedLabel(ProjectSession session) {
        Label lastSavedLabel = new Label("Last saved: " + formatProjectTimestamp(session.manifestData().updatedAt()));
        lastSavedLabel.getStyleClass().add("project-title-popup-meta");
        lastSavedLabel.setGraphic(IconSupport.createSectionIcon("far-clock"));
        return lastSavedLabel;
    }

    private String resolveProjectDirectory(Path projectFile) {
        Path absolutePath = projectFile.toAbsolutePath();
        Path parent = absolutePath.getParent();
        return parent == null ? absolutePath.toString() : parent.toString();
    }

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

    private void wireProjectWindowInteractions() {
        wireProjectWindowDragging();
        wireProjectWindowResizing();
    }

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

    private void toggleProjectWindowMaximized() {
        if (projectWindowMaximized) {
            restoreProjectWindow();
        } else {
            maximizeProjectWindow();
        }
    }

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

    private void updateProjectWindowMaximizeButton() {
        if (projectWindowMaximizeButton == null) {
            return;
        }
        projectWindowMaximizeButton.setGraphic(IconSupport.createWindowControlIcon(
                projectWindowMaximized ? "far-window-restore" : "far-window-maximize"
        ));
    }

    private void minimizeStartWindow() {
        if (primaryStage != null) {
            primaryStage.setIconified(true);
        }
    }

    private void minimizeProjectWindow() {
        if (projectStage != null) {
            projectStage.setIconified(true);
        }
    }

    private void closeStartWindow() {
        if (primaryStage != null) {
            primaryStage.close();
        }
    }

    private void closeProjectWindow() {
        if (currentWorkspace != null) {
            closeCurrentProject();
            return;
        }
        if (projectStage != null) {
            projectStage.hide();
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
