package com.buraktok.reportforge.ui;

import com.buraktok.reportforge.persistence.ProjectContainerService;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class NewProjectDialog {
    private NewProjectDialog() {
    }

    public static Optional<Result> show(WindowContext context) {
        Dialog<Result> dialog = new Dialog<>();
        dialog.initOwner(context.getPrimaryStage());
        dialog.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        dialog.setTitle("New Project");
        UiSupport.styleDialog(context, dialog);

        ButtonType createButtonType = new ButtonType("Create Project", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createButtonType, ButtonType.CANCEL);

        GridPane gridPane = new GridPane();
        gridPane.setHgap(10);
        gridPane.setVgap(10);
        gridPane.setPadding(new Insets(20));
        gridPane.getStyleClass().add("form-grid");

        TextField projectNameField = new TextField();
        TextField projectPathField = new TextField();
        TextField environmentField = new TextField();
        TextArea applicationsArea = new TextArea();
        applicationsArea.setPromptText("One application per line");
        applicationsArea.setPrefRowCount(4);

        javafx.scene.control.Button browseButton = new javafx.scene.control.Button("Browse...");
        browseButton.getStyleClass().addAll("app-button", "secondary-button");
        browseButton.setOnAction(event -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose Project File");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "ReportForge Projects",
                    "*" + ProjectContainerService.PROJECT_EXTENSION
            ));
            chooser.setInitialFileName("reportforge" + ProjectContainerService.PROJECT_EXTENSION);
            Path defaultDirectory = Path.of(System.getProperty("user.home"), "Documents");
            if (Files.isDirectory(defaultDirectory)) {
                chooser.setInitialDirectory(defaultDirectory.toFile());
            }
            var selectedFile = chooser.showSaveDialog(context.getPrimaryStage());
            if (selectedFile != null) {
                projectPathField.setText(selectedFile.toPath().toString());
            }
        });

        HBox pathBox = new HBox(8, projectPathField, browseButton);
        HBox.setHgrow(projectPathField, Priority.ALWAYS);

        gridPane.addRow(0, new Label("Project Name"), projectNameField);
        gridPane.addRow(1, new Label("Save Location"), pathBox);
        gridPane.addRow(2, new Label("Initial Environment"), environmentField);
        gridPane.addRow(3, new Label("Applications under Test"), applicationsArea);

        dialog.getDialogPane().setContent(gridPane);
        dialog.setResultConverter(buttonType -> {
            if (buttonType != createButtonType) {
                return null;
            }
            return new Result(
                    projectNameField.getText(),
                    projectPathField.getText(),
                    environmentField.getText(),
                    UiSupport.splitLines(applicationsArea.getText())
            );
        });

        return dialog.showAndWait();
    }

    public record Result(String projectName, String projectPath, String initialEnvironmentName, List<String> applicationNames) {
    }
}
