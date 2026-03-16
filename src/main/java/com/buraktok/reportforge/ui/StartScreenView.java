package com.buraktok.reportforge.ui;

import com.buraktok.reportforge.model.RecentProject;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class StartScreenView {
    private static final DateTimeFormatter RECENT_PROJECT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.getDefault());

    public Node build(
            List<RecentProject> recentProjects,
            ThemeMode themeMode,
            Runnable onNewProject,
            Runnable onOpenProject,
            Consumer<RecentProject> onOpenRecent,
            Runnable onToggleTheme,
            Runnable onMinimizeWindow,
            Runnable onCloseWindow
    ) {
        Label windowTitle = new Label("ReportForge");
        windowTitle.getStyleClass().addAll("window-title", "start-title");

        HBox windowControls = new HBox(
                8,
                createWindowControlButton("fas-minus", onMinimizeWindow, false),
                createWindowControlButton("fas-times", onCloseWindow, true)
        );
        windowControls.getStyleClass().add("window-controls");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(
                16,
                windowTitle,
                headerSpacer,
                windowControls
        );
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setId("start-window-title-bar");
        headerRow.getStyleClass().add("start-window-header");

        Label sidebarDescription = new Label(
                "Open an existing report workspace or create a new one"
        );
        sidebarDescription.setWrapText(true);
        sidebarDescription.getStyleClass().addAll("supporting-text", "sidebar-description");

        Separator sidebarSeparator = new Separator();
        sidebarSeparator.getStyleClass().add("section-separator");
        VBox.setMargin(sidebarSeparator, new Insets(15, 0, 15, 0));

        Button newProjectButton = createSidebarActionButton("New Project", "fas-plus", onNewProject, "primary-button");
        Button openProjectButton = createSidebarActionButton("Open Project", "far-folder-open", onOpenProject, "secondary-button");
        Button themeButton = createThemeToggleButton(themeMode, onToggleTheme);
        themeButton.getStyleClass().add("sidebar-action-button");
        themeButton.setMaxWidth(Double.MAX_VALUE);
        themeButton.setAlignment(Pos.CENTER_LEFT);

        VBox actionGroup = new VBox(10, newProjectButton, openProjectButton, themeButton);
        actionGroup.setFillWidth(true);

        Region sidebarSpacer = new Region();
        VBox.setVgrow(sidebarSpacer, Priority.ALWAYS);

        Label sidebarFooter = new Label("Structured QA reporting tool.");
        sidebarFooter.setWrapText(true);
        sidebarFooter.getStyleClass().addAll("meta-text", "sidebar-footer");

        VBox sidebar = new VBox(
                14,
                sidebarDescription,
                sidebarSeparator,
                actionGroup,
                sidebarSpacer,
                sidebarFooter
        );
        sidebar.setPrefWidth(232);
        sidebar.setMinWidth(232);
        sidebar.setMaxWidth(232);
        sidebar.setFillWidth(true);
        sidebar.setMaxHeight(Double.MAX_VALUE);
        sidebar.getStyleClass().add("start-sidebar");

        Label recentTitle = new Label("Recent Projects");
        recentTitle.getStyleClass().add("panel-heading");
        recentTitle.setGraphic(IconSupport.createSectionIcon("far-clock"));
        recentTitle.setGraphicTextGap(10);

        Label recentSubtitle = new Label(
                recentProjects.isEmpty()
                        ? "Your recently opened ReportForge workspaces will appear here."
                        : "Continue from one of your latest report workspaces."
        );
        recentSubtitle.setWrapText(true);
        recentSubtitle.getStyleClass().addAll("supporting-text", "recent-projects-subtitle");

        Separator recentsSeparator = new Separator();
        recentsSeparator.getStyleClass().add("section-separator");

        VBox recentsBox = new VBox(6);
        recentsBox.setFillWidth(true);
        recentsBox.getStyleClass().add("recent-project-list");

        if (recentProjects.isEmpty()) {
            Label emptyLabel = new Label("No recent projects yet.");
            emptyLabel.getStyleClass().addAll("panel-heading", "empty-state-title");

            Label emptyDescription = new Label("Create a new project or open an existing `.rfproj` workspace to get started.");
            emptyDescription.setWrapText(true);
            emptyDescription.getStyleClass().addAll("supporting-text", "empty-state-description");

            VBox emptyState = new VBox(8, emptyLabel, emptyDescription);
            emptyState.getStyleClass().add("start-empty-state");
            recentsBox.getChildren().add(emptyState);
        } else {
            for (RecentProject recentProject : recentProjects) {
                Label nameLabel = new Label(recentProject.getName());
                nameLabel.getStyleClass().add("recent-project-name");

                Label pathLabel = new Label(recentProject.getPath());
                pathLabel.getStyleClass().add("supporting-text");
                pathLabel.getStyleClass().add("recent-project-path");

                Label dateLabel = new Label("Last opened: " + formatLastOpened(recentProject.getLastOpenedAt()));
                dateLabel.getStyleClass().add("meta-text");

                VBox recentContent = new VBox(4, nameLabel, pathLabel, dateLabel);
                recentContent.setFillWidth(true);
                recentContent.setAlignment(Pos.CENTER_LEFT);
                recentContent.setMaxHeight(Region.USE_PREF_SIZE);
                recentContent.setPadding(Insets.EMPTY);
                HBox.setHgrow(recentContent, Priority.ALWAYS);

                HBox recentGraphic = new HBox(
                        12,
                        recentContent,
                        IconSupport.createRecentProjectIcon("far-folder-open")
                );
                recentGraphic.setAlignment(Pos.CENTER_RIGHT);
                recentGraphic.setPadding(new Insets(0, 15, 0, 15));

                Button recentButton = new Button();
                recentButton.setMaxWidth(Double.MAX_VALUE);
                recentButton.setPrefHeight(74);
                recentButton.setMinHeight(74);
                recentButton.setMaxHeight(74);
                //recentButton.setPadding(new Insets(0, 15, 0, 15));
                recentButton.setAlignment(Pos.CENTER_LEFT);
                recentButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                recentButton.setGraphic(recentGraphic);
                recentButton.getStyleClass().addAll("recent-project-button", "recent-project-card");
                recentButton.setOnAction(event -> onOpenRecent.accept(recentProject));
                recentsBox.getChildren().add(recentButton);
            }
        }

        VBox recentPanel = new VBox(14, recentTitle, recentSubtitle, recentsSeparator, recentsBox);
        recentPanel.setFillWidth(true);
        recentPanel.setMaxHeight(Double.MAX_VALUE);
        recentPanel.getStyleClass().add("start-main-panel");
        HBox.setHgrow(recentPanel, Priority.ALWAYS);

        HBox content = new HBox(sidebar, recentPanel);
        content.setFillHeight(true);
        content.setPadding(Insets.EMPTY);
        content.setAlignment(Pos.TOP_LEFT);
        content.getStyleClass().add("start-workspace");

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setPadding(new Insets(0));
        scrollPane.getStyleClass().add("start-screen-scroll");

        VBox shell = new VBox(headerRow, scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        shell.getStyleClass().add("start-screen-root");
        return shell;
    }

    private Button createThemeToggleButton(ThemeMode themeMode, Runnable onToggleTheme) {
        Button button = new Button(themeMode == ThemeMode.DARK ? "Light Mode" : "Dark Mode");
        button.setOnAction(event -> onToggleTheme.run());
        button.getStyleClass().addAll("app-button", "secondary-button", "theme-toggle-button");
        button.setGraphic(IconSupport.createButtonIcon(themeMode == ThemeMode.DARK ? "fas-sun" : "fas-moon"));
        button.setContentDisplay(ContentDisplay.LEFT);
        button.setGraphicTextGap(10);
        return button;
    }

    private Button createSidebarActionButton(String text, String iconLiteral, Runnable action, String variantClass) {
        Button button = new Button(text);
        button.setOnAction(event -> action.run());
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.getStyleClass().addAll("app-button", "sidebar-action-button", variantClass);
        button.setGraphic(IconSupport.createButtonIcon(iconLiteral));
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

    private String formatLastOpened(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown";
        }
        try {
            return RECENT_PROJECT_DATE_FORMATTER.format(Instant.parse(value).atZone(ZoneId.systemDefault()));
        } catch (DateTimeParseException exception) {
            return value;
        }
    }
}
