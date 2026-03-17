package com.buraktok.reportforge.ui;

import com.buraktok.reportforge.model.TestExecutionSection;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.UnaryOperator;

public final class UiSupport {
    private UiSupport() {
    }

    public static void styleDialog(WindowContext context, Dialog<?> dialog) {
        if (context.getScene() != null) {
            dialog.getDialogPane().getStylesheets().setAll(context.getScene().getStylesheets());
        }
        dialog.getDialogPane().getStyleClass().removeAll(ThemeMode.DARK.cssClass(), ThemeMode.LIGHT.cssClass());
        dialog.getDialogPane().getStyleClass().addAll("app-root", context.getThemeMode().cssClass(), "dialog-root");
    }

    public static boolean confirm(WindowContext context, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(context.getPrimaryStage());
        alert.setTitle(title);
        alert.setHeaderText(null);
        styleDialog(context, alert);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    public static void showInformation(WindowContext context, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.initOwner(context.getPrimaryStage());
        alert.setTitle(title);
        alert.setHeaderText(null);
        styleDialog(context, alert);
        alert.showAndWait();
    }

    public static void showError(WindowContext context, String title, String message) {
        Alert alert = new Alert(
                Alert.AlertType.ERROR,
                message == null || message.isBlank() ? "An unexpected error occurred." : message,
                ButtonType.OK
        );
        alert.initOwner(context.getPrimaryStage());
        alert.setTitle(title);
        alert.setHeaderText(null);
        styleDialog(context, alert);
        alert.showAndWait();
    }

    public static TextField readOnlyField(String value) {
        TextField textField = new TextField(value);
        textField.setEditable(false);
        return textField;
    }

    public static TextArea createArea(String value, int rows) {
        TextArea textArea = new TextArea(value);
        textArea.setPrefRowCount(rows);
        return textArea;
    }

    public static TextField integerField(String initialValue) {
        UnaryOperator<TextFormatter.Change> filter = change -> {
            String newText = change.getControlNewText();
            return newText.matches("\\d*") ? change : null;
        };
        TextField textField = new TextField(initialValue);
        textField.setTextFormatter(new TextFormatter<>(filter));
        return textField;
    }

    public static String calculatePassRate(String passedValue, String totalValue) {
        if (passedValue == null || passedValue.isBlank() || totalValue == null || totalValue.isBlank()) {
            return "N/A";
        }
        try {
            int passed = Integer.parseInt(passedValue);
            int total = Integer.parseInt(totalValue);
            if (total <= 0) {
                return "N/A";
            }
            double passRate = (passed * 100.0) / total;
            return String.format("%.2f", passRate);
        } catch (NumberFormatException exception) {
            return "N/A";
        }
    }

    public static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return LocalDate.now();
        }
    }

    public static List<String> splitLines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return value.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    public static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be empty.");
        }
        return value.trim();
    }

    public static Label sectionHeading(TestExecutionSection section) {
        Label label = new Label(section.getDisplayName());
        label.getStyleClass().add("section-heading");
        return label;
    }

    public static ScrollPane wrapInScrollPane(Node content) {
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll-pane");
        content.getStyleClass().add("content-card");
        return scrollPane;
    }

    public static ContextMenu themedContextMenu(WindowContext context, MenuItem... items) {
        ContextMenu contextMenu = new ContextMenu(items);
        applyPopupTheme(context, contextMenu);
        contextMenu.setOnShowing(event -> applyPopupTheme(context, contextMenu));
        return contextMenu;
    }

    public static MenuItem createMenuItem(String text, String iconLiteral, Runnable action) {
        MenuItem menuItem = new MenuItem(text);
        menuItem.setGraphic(IconSupport.createButtonIcon(iconLiteral));
        if (action != null) {
            menuItem.setOnAction(event -> action.run());
        }
        return menuItem;
    }

    public static StringConverter<com.buraktok.reportforge.model.ReportStatus> reportStatusConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(com.buraktok.reportforge.model.ReportStatus object) {
                return object == null ? "" : object.getDisplayName();
            }

            @Override
            public com.buraktok.reportforge.model.ReportStatus fromString(String string) {
                return null;
            }
        };
    }

    private static void applyPopupTheme(WindowContext context, ContextMenu contextMenu) {
        contextMenu.getStyleClass().removeAll(ThemeMode.DARK.cssClass(), ThemeMode.LIGHT.cssClass());
        if (!contextMenu.getStyleClass().contains("app-context-menu")) {
            contextMenu.getStyleClass().add("app-context-menu");
        }
        contextMenu.getStyleClass().add(context.getThemeMode().cssClass());
    }
}
