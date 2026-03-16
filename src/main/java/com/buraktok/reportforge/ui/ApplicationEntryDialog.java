package com.buraktok.reportforge.ui;

import com.buraktok.reportforge.model.ApplicationEntry;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import java.util.Optional;

public final class ApplicationEntryDialog {
    private ApplicationEntryDialog() {
    }

    public static Optional<ApplicationEntry> show(WindowContext context, String title, ApplicationEntry existing) {
        Dialog<ApplicationEntry> dialog = new Dialog<>();
        dialog.initOwner(context.getPrimaryStage());
        dialog.setTitle(title);
        UiSupport.styleDialog(context, dialog);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane gridPane = new GridPane();
        gridPane.setHgap(10);
        gridPane.setVgap(10);
        gridPane.setPadding(new Insets(20));
        gridPane.getStyleClass().add("form-grid");

        TextField nameField = new TextField(existing == null ? "" : existing.getName());
        TextField versionField = new TextField(existing == null ? "" : existing.getVersionOrBuild());
        TextField modulesField = new TextField(existing == null ? "" : existing.getModuleList());
        TextField platformField = new TextField(existing == null ? "" : existing.getPlatform());
        TextArea descriptionArea = new TextArea(existing == null ? "" : existing.getDescription());
        descriptionArea.setPrefRowCount(3);
        TextField servicesField = new TextField(existing == null ? "" : existing.getRelatedServices());
        CheckBox primaryCheckBox = new CheckBox("Primary application");
        primaryCheckBox.setSelected(existing != null && existing.isPrimary());

        gridPane.addRow(0, new Label("Application Name"), nameField);
        gridPane.addRow(1, new Label("Version / Build"), versionField);
        gridPane.addRow(2, new Label("Module / Component List"), modulesField);
        gridPane.addRow(3, new Label("Platform"), platformField);
        gridPane.addRow(4, new Label("Description"), descriptionArea);
        gridPane.addRow(5, new Label("Related Services / APIs"), servicesField);
        gridPane.addRow(6, new Label("Primary"), primaryCheckBox);

        dialog.getDialogPane().setContent(gridPane);
        dialog.setResultConverter(buttonType -> {
            if (buttonType != ButtonType.OK) {
                return null;
            }
            return new ApplicationEntry(
                    existing == null ? "" : existing.getId(),
                    nameField.getText(),
                    versionField.getText(),
                    modulesField.getText(),
                    platformField.getText(),
                    descriptionArea.getText(),
                    servicesField.getText(),
                    primaryCheckBox.isSelected(),
                    existing == null ? -1 : existing.getSortOrder()
            );
        });
        return dialog.showAndWait();
    }
}
