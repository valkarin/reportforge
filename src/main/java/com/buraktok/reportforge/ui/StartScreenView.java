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
                createWindowControlButton("-", onMinimizeWindow, false),
                createWindowControlButton("X", onCloseWindow, true)
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

        Label title = new Label("ReportForge");
        title.getStyleClass().add("start-title");

        Label description = new Label(
                "Create structured software test reports from projects, environments, and reusable report templates."
        );
        description.setWrapText(true);
        description.getStyleClass().add("start-description");

        HBox primaryActions = new HBox(12);
        primaryActions.setAlignment(Pos.CENTER_LEFT);
        Button newProjectButton = new Button("New Project");
        newProjectButton.setOnAction(event -> onNewProject.run());
        newProjectButton.getStyleClass().addAll("app-button", "primary-button");

        Button openProjectButton = new Button("Open Project");
        openProjectButton.setOnAction(event -> onOpenProject.run());
        openProjectButton.getStyleClass().addAll("app-button", "secondary-button");
        primaryActions.getChildren().addAll(newProjectButton, openProjectButton, createThemeToggleButton(themeMode, onToggleTheme));

        VBox introSection = new VBox(10, title, description, primaryActions);
        introSection.getStyleClass().add("start-intro");

        VBox recentsBox = new VBox(8);
        recentsBox.getStyleClass().add("recent-projects-box");

        Separator recentsSeparator = new Separator();
        recentsSeparator.getStyleClass().add("section-separator");
        recentsBox.getChildren().addAll(introSection, recentsSeparator);

        if (recentProjects.isEmpty()) {
            Label emptyLabel = new Label("No recent projects yet.");
            emptyLabel.getStyleClass().add("supporting-text");
            recentsBox.getChildren().add(emptyLabel);
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
                recentContent.setPadding(new Insets(0,0,0,15));

                Button recentButton = new Button();
                recentButton.setMaxWidth(Double.MAX_VALUE);
                recentButton.setPrefHeight(74);
                recentButton.setMinHeight(74);
                recentButton.setMaxHeight(74);
                recentButton.setAlignment(Pos.CENTER_LEFT);
                recentButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                recentButton.setGraphic(recentContent);
                recentButton.getStyleClass().addAll("recent-project-button", "recent-project-card");
                recentButton.setOnAction(event -> onOpenRecent.accept(recentProject));
                recentsBox.getChildren().add(recentButton);
            }
        }

        VBox recentCard = new VBox(14, recentsBox);
        recentCard.setPadding(new Insets(28));
        recentCard.getStyleClass().addAll("glass-card", "start-card");

        VBox content = new VBox(20, recentCard);
        content.setPadding(new Insets(24));
        content.setMaxWidth(920);
        content.setAlignment(Pos.TOP_LEFT);

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPadding(new Insets(0));
        scrollPane.getStyleClass().add("start-screen-scroll");

        VBox shell = new VBox(headerRow, scrollPane);
        shell.getStyleClass().add("start-screen-root");
        return shell;
    }

    private Button createThemeToggleButton(ThemeMode themeMode, Runnable onToggleTheme) {
        Button button = new Button(themeMode == ThemeMode.DARK ? "Light Mode" : "Dark Mode");
        button.setOnAction(event -> onToggleTheme.run());
        button.getStyleClass().addAll("app-button", "secondary-button", "theme-toggle-button");
        return button;
    }

    private Button createWindowControlButton(String text, Runnable action, boolean closeButton) {
        Button button = new Button(text);
        button.setFocusTraversable(false);
        button.setOnAction(event -> action.run());
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
