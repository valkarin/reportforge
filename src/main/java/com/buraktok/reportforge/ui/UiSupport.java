package com.buraktok.reportforge.ui;

import com.buraktok.reportforge.model.TestExecutionSection;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.Clipboard;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.StringConverter;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

public final class UiSupport {
    private static final String TEXT_CONTEXT_MENU_INSTALLED_KEY = "reportforge.textContextMenuInstalled";
    private static final String DIALOG_STYLE_INSTALLED_KEY = "reportforge.dialogStyleInstalled";
    private static final String DIALOG_BUTTONS_STYLED_KEY = "reportforge.dialogButtonsStyled";
    private static final String DIALOG_PURPOSE_KEY = "reportforge.dialogPurpose";

    private UiSupport() {
    }

    public static void styleDialog(WindowContext context, Dialog<?> dialog) {
        try {
            dialog.initStyle(StageStyle.TRANSPARENT);
        } catch (IllegalStateException ignored) {
            // Dialog style can only be configured before showing; if it's already set, keep going.
        }
        DialogPane dialogPane = dialog.getDialogPane();
        if (context.getScene() != null) {
            dialogPane.getStylesheets().setAll(context.getScene().getStylesheets());
        }
        dialogPane.getStyleClass().removeAll(ThemeMode.DARK.cssClass(), ThemeMode.LIGHT.cssClass());
        if (!dialogPane.getStyleClass().contains("app-root")) {
            dialogPane.getStyleClass().add("app-root");
        }
        if (!dialogPane.getStyleClass().contains(context.getThemeMode().cssClass())) {
            dialogPane.getStyleClass().add(context.getThemeMode().cssClass());
        }
        if (!dialogPane.getStyleClass().contains("dialog-root")) {
            dialogPane.getStyleClass().add("dialog-root");
        }
        if (!Boolean.TRUE.equals(dialogPane.getProperties().get(DIALOG_STYLE_INSTALLED_KEY)) && dialogPane.getHeader() == null) {
            dialogPane.setHeader(createDialogHeader(dialog));
        }
        dialogPane.getProperties().put(DIALOG_STYLE_INSTALLED_KEY, true);
        dialogPane.contentProperty().addListener((observable, previous, current) ->
                Platform.runLater(() -> installTextInputContextMenus(context, dialogPane)));
        dialogPane.sceneProperty().addListener((observable, previous, current) -> {
            if (current != null) {
                configureDialogScene(current);
                Platform.runLater(() -> {
                    installTextInputContextMenus(context, dialogPane);
                    applyDialogButtonStyles(dialog);
                });
            }
        });
        if (!Boolean.TRUE.equals(dialogPane.getProperties().get(DIALOG_BUTTONS_STYLED_KEY))) {
            dialogPane.getButtonTypes().addListener((ListChangeListener<ButtonType>) change ->
                    Platform.runLater(() -> applyDialogButtonStyles(dialog)));
            dialogPane.getProperties().put(DIALOG_BUTTONS_STYLED_KEY, true);
        }
        if (dialogPane.getScene() != null) {
            configureDialogScene(dialogPane.getScene());
        }
        Platform.runLater(() -> applyDialogButtonStyles(dialog));
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

    public static boolean showExportComplete(WindowContext context, Path exportPath) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(context.getPrimaryStage());
        dialog.setTitle("Export Complete");
        dialog.setHeaderText("HTML report exported successfully");
        dialog.getDialogPane().getProperties().put(DIALOG_PURPOSE_KEY, DialogPurpose.SUCCESS);
        ButtonType openButtonType = new ButtonType("Open HTML", ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(openButtonType, ButtonType.OK);
        dialog.getDialogPane().setPrefWidth(620);
        dialog.getDialogPane().setContent(createExportCompleteContent(exportPath));
        styleDialog(context, dialog);
        Optional<ButtonType> result = dialog.showAndWait();
        return result.filter(openButtonType::equals).isPresent();
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

    public static LineNumberedTextArea createLineNumberedArea(String value, int rows) {
        return new LineNumberedTextArea(value, rows);
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

    private static Node createDialogHeader(Dialog<?> dialog) {
        Label dialogTitleLabel = new Label();
        dialogTitleLabel.textProperty().bind(Bindings.createStringBinding(
                () -> isBlank(dialog.getTitle()) ? "ReportForge" : dialog.getTitle(),
                dialog.titleProperty()
        ));
        dialogTitleLabel.getStyleClass().addAll("window-title", "project-title-name");

        Label dialogSubtitleLabel = new Label();
        dialogSubtitleLabel.textProperty().bind(Bindings.createStringBinding(
                () -> isBlank(dialog.getHeaderText()) ? "" : dialog.getHeaderText(),
                dialog.headerTextProperty()
        ));
        dialogSubtitleLabel.visibleProperty().bind(Bindings.createBooleanBinding(
                () -> !isBlank(dialog.getHeaderText()),
                dialog.headerTextProperty()
        ));
        dialogSubtitleLabel.managedProperty().bind(dialogSubtitleLabel.visibleProperty());
        dialogSubtitleLabel.setWrapText(true);
        dialogSubtitleLabel.getStyleClass().addAll("supporting-text", "dialog-header-caption");

        VBox titleBox = new VBox(4, dialogTitleLabel, dialogSubtitleLabel);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        Node purposeBadge = createDialogPurposeBadge(dialog);
        HBox titleGroup = purposeBadge == null
                ? new HBox(titleBox)
                : new HBox(12, purposeBadge, titleBox);
        titleGroup.setAlignment(Pos.CENTER_LEFT);

        Button closeButton = createWindowControlButton("fas-times", dialog::close, true);
        HBox windowControls = new HBox(8, closeButton);
        windowControls.getStyleClass().add("window-controls");

        Label applicationTitleLabel = new Label("ReportForge");
        applicationTitleLabel.getStyleClass().addAll("window-title", "dialog-brand-title");
        applicationTitleLabel.setMinWidth(Label.USE_PREF_SIZE);

        HBox leftBrandBox = new HBox(applicationTitleLabel);
        leftBrandBox.setAlignment(Pos.CENTER_LEFT);
        double sideWidth = Math.max(132, applicationTitleLabel.prefWidth(-1) + 18);
        leftBrandBox.setMinWidth(sideWidth);
        leftBrandBox.setPrefWidth(sideWidth);
        leftBrandBox.setMaxWidth(sideWidth);
        windowControls.setMinWidth(sideWidth);
        windowControls.setPrefWidth(sideWidth);
        windowControls.setMaxWidth(sideWidth);

        StackPane centeredTitle = new StackPane(titleGroup);
        centeredTitle.setAlignment(Pos.CENTER);
        HBox.setHgrow(centeredTitle, Priority.ALWAYS);

        HBox headerRow = new HBox(leftBrandBox, centeredTitle, windowControls);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.getStyleClass().addAll("start-window-header", "dialog-window-header");
        enableWindowDragging(headerRow);
        return headerRow;
    }

    private static Button createWindowControlButton(String iconLiteral, Runnable action, boolean closeButton) {
        Button button = new Button();
        button.setFocusTraversable(false);
        button.setOnAction(event -> action.run());
        button.setGraphic(IconSupport.createWindowControlIcon(iconLiteral));
        button.getStyleClass().addAll("window-control-button", closeButton ? "window-close-button" : "window-minimize-button");
        return button;
    }

    private static void enableWindowDragging(Node dragHandle) {
        double[] dragOffsetX = new double[1];
        double[] dragOffsetY = new double[1];
        dragHandle.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            Window window = dragHandle.getScene() == null ? null : dragHandle.getScene().getWindow();
            if (window == null) {
                return;
            }
            dragOffsetX[0] = window.getX() - event.getScreenX();
            dragOffsetY[0] = window.getY() - event.getScreenY();
        });
        dragHandle.setOnMouseDragged(event -> {
            if (!event.isPrimaryButtonDown()) {
                return;
            }
            Window window = dragHandle.getScene() == null ? null : dragHandle.getScene().getWindow();
            if (window == null) {
                return;
            }
            window.setX(event.getScreenX() + dragOffsetX[0]);
            window.setY(event.getScreenY() + dragOffsetY[0]);
        });
    }

    private static void configureDialogScene(Scene scene) {
        scene.setFill(Color.TRANSPARENT);
        Window window = scene.getWindow();
        if (window instanceof Stage stage) {
            stage.setResizable(false);
        }
    }

    private static void applyDialogButtonStyles(Dialog<?> dialog) {
        DialogPane dialogPane = dialog.getDialogPane();
        for (ButtonType buttonType : dialogPane.getButtonTypes()) {
            Node buttonNode = dialogPane.lookupButton(buttonType);
            if (!(buttonNode instanceof Button button)) {
                continue;
            }
            button.getStyleClass().removeAll("app-button", "primary-button", "secondary-button", "accent-button");
            button.getStyleClass().add("app-button");
            button.getStyleClass().add(buttonType.getButtonData().isDefaultButton() ? "accent-button" : "secondary-button");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Node createExportCompleteContent(Path exportPath) {
        Label messageLabel = new Label("The HTML report was exported to the location below.");
        messageLabel.setWrapText(true);
        messageLabel.getStyleClass().add("supporting-text");

        Label pathLabel = new Label("Exported File");
        pathLabel.getStyleClass().add("section-heading");

        TextArea pathArea = new TextArea(exportPath == null ? "" : exportPath.toString());
        pathArea.setEditable(false);
        pathArea.setWrapText(true);
        pathArea.setPrefRowCount(3);
        pathArea.getStyleClass().add("dialog-path-area");

        VBox contentBox = new VBox(10, messageLabel, pathLabel, pathArea);
        contentBox.setPadding(new Insets(18, 20, 6, 20));
        contentBox.getStyleClass().add("dialog-content-box");
        return contentBox;
    }

    private static Node createDialogPurposeBadge(Dialog<?> dialog) {
        DialogPurpose purpose = resolveDialogPurpose(dialog);
        if (purpose == null) {
            return null;
        }
        StackPane badge = new StackPane(IconSupport.createDialogIcon(purpose.iconLiteral));
        badge.getStyleClass().addAll("dialog-purpose-badge", purpose.styleClass);
        badge.setMinSize(34, 34);
        badge.setPrefSize(34, 34);
        badge.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return badge;
    }

    private static DialogPurpose resolveDialogPurpose(Dialog<?> dialog) {
        Object explicitPurpose = dialog.getDialogPane().getProperties().get(DIALOG_PURPOSE_KEY);
        if (explicitPurpose instanceof DialogPurpose dialogPurpose) {
            return dialogPurpose;
        }
        if (dialog instanceof Alert alert) {
            return switch (alert.getAlertType()) {
                case INFORMATION -> DialogPurpose.INFO;
                case WARNING -> DialogPurpose.WARNING;
                case ERROR -> DialogPurpose.ERROR;
                case CONFIRMATION -> DialogPurpose.CONFIRMATION;
                default -> DialogPurpose.FORM;
            };
        }
        if (dialog instanceof TextInputDialog) {
            return DialogPurpose.INPUT;
        }
        if (dialog instanceof ChoiceDialog<?>) {
            return DialogPurpose.SELECTION;
        }
        return DialogPurpose.FORM;
    }

    private enum DialogPurpose {
        INFO("fas-info-circle", "dialog-purpose-info"),
        WARNING("fas-exclamation-triangle", "dialog-purpose-warning"),
        ERROR("fas-times-circle", "dialog-purpose-error"),
        CONFIRMATION("fas-question-circle", "dialog-purpose-confirmation"),
        SUCCESS("fas-check-circle", "dialog-purpose-success"),
        INPUT("fas-keyboard", "dialog-purpose-input"),
        SELECTION("fas-list-ul", "dialog-purpose-selection"),
        FORM("fas-edit", "dialog-purpose-form");

        private final String iconLiteral;
        private final String styleClass;

        DialogPurpose(String iconLiteral, String styleClass) {
            this.iconLiteral = iconLiteral;
            this.styleClass = styleClass;
        }
    }
}
