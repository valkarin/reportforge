package com.buraktok.reportforge.ui;

import com.buraktok.reportforge.model.EnvironmentRecord;
import javafx.scene.control.ChoiceDialog;

import java.util.List;
import java.util.Optional;

public final class EnvironmentSelectionDialog {
    private EnvironmentSelectionDialog() {
    }

    public static Optional<String> show(WindowContext context, List<EnvironmentRecord> candidateEnvironments) {
        if (candidateEnvironments.isEmpty()) {
            return Optional.empty();
        }
        ChoiceDialog<EnvironmentRecord> dialog = new ChoiceDialog<>(candidateEnvironments.getFirst(), candidateEnvironments);
        dialog.initOwner(context.getPrimaryStage());
        dialog.setTitle("Choose Environment");
        dialog.setHeaderText("Select the target environment");
        dialog.setContentText("Environment:");
        UiSupport.styleDialog(context, dialog);
        return dialog.showAndWait().map(EnvironmentRecord::getId);
    }
}
