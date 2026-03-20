package com.buraktok.reportforge.ui;

import com.buraktok.reportforge.model.TestExecutionSection;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.Clipboard;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.UnaryOperator;

public final class UiSupport {
    private static final String TEXT_CONTEXT_MENU_INSTALLED_KEY = "reportforge.textContextMenuInstalled";

    private UiSupport() {
    }

    public static void styleDialog(WindowContext context, Dialog<?> dialog) {
        if (context.getScene() != null) {
            dialog.getDialogPane().getStylesheets().setAll(context.getScene().getStylesheets());
        }
        dialog.getDialogPane().getStyleClass().removeAll(ThemeMode.DARK.cssClass(), ThemeMode.LIGHT.cssClass());
        dialog.getDialogPane().getStyleClass().addAll("app-root", context.getThemeMode().cssClass(), "dialog-root");
        dialog.getDialogPane().contentProperty().addListener((observable, previous, current) ->
                Platform.runLater(() -> installTextInputContextMenus(context, dialog.getDialogPane())));
        dialog.getDialogPane().sceneProperty().addListener((observable, previous, current) -> {
            if (current != null) {
                Platform.runLater(() -> installTextInputContextMenus(context, dialog.getDialogPane()));
            }
        });
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

    public static void installTextInputContextMenus(WindowContext context, Node node) {
        if (node == null) {
            return;
        }
        if (node instanceof TextInputControl textInputControl) {
            installTextInputContextMenu(context, textInputControl);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                installTextInputContextMenus(context, child);
            }
        }
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

    private static void installTextInputContextMenu(WindowContext context, TextInputControl textInputControl) {
        if (Boolean.TRUE.equals(textInputControl.getProperties().get(TEXT_CONTEXT_MENU_INSTALLED_KEY))) {
            return;
        }
        if (textInputControl.getContextMenu() != null) {
            return;
        }

        MenuItem cutItem = createMenuItem("Cut", "fas-cut", textInputControl::cut);
        MenuItem copyItem = createMenuItem("Copy", "far-copy", textInputControl::copy);
        MenuItem pasteItem = createMenuItem("Paste", "fas-paste", textInputControl::paste);
        MenuItem deleteItem = createMenuItem("Delete", "fas-trash-alt", () -> textInputControl.replaceSelection(""));
        MenuItem selectAllItem = createMenuItem("Select All", "fas-i-cursor", textInputControl::selectAll);

        ContextMenu contextMenu = themedContextMenu(
                context,
                cutItem,
                copyItem,
                pasteItem,
                deleteItem,
                new SeparatorMenuItem(),
                selectAllItem
        );

        Runnable updater = () -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            boolean editable = textInputControl.isEditable() && !textInputControl.isDisabled();
            boolean hasSelection = textInputControl.getSelection().getLength() > 0;
            boolean hasText = textInputControl.getText() != null && !textInputControl.getText().isEmpty();
            boolean hasClipboardText = clipboard.hasString();

            cutItem.setDisable(!editable || !hasSelection);
            copyItem.setDisable(!hasSelection);
            pasteItem.setDisable(!editable || !hasClipboardText);
            deleteItem.setDisable(!editable || !hasSelection);
            selectAllItem.setDisable(!hasText);
        };

        textInputControl.addEventHandler(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> updater.run());
        textInputControl.setContextMenu(contextMenu);
        textInputControl.getProperties().put(TEXT_CONTEXT_MENU_INSTALLED_KEY, true);
    }
}
