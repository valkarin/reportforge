package com.buraktok.reportforge.ui;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;

public final class LineNumberedTextArea extends HBox {
    private static final double GUTTER_BASE_WIDTH = 24;
    private static final double GUTTER_DIGIT_WIDTH = 8;
    private static final double EDITOR_WIDTH_FALLBACK_PADDING = 24;

    private final TextArea gutter;
    private final TextArea editor;
    private final Text measurementText = new Text();
    private final Text lineHeightText = new Text("Ay");
    private final ChangeListener<Number> contentWidthListener = (observable, previous, current) -> requestLineNumberRefresh();
    private Region editorContentRegion;
    private boolean refreshPending;

    public LineNumberedTextArea(String value, int rows) {
        getStyleClass().add("line-numbered-text-area");
        setSpacing(0);

        gutter = new TextArea();
        gutter.getStyleClass().add("line-number-gutter");
        gutter.setEditable(false);
        gutter.setFocusTraversable(false);
        gutter.setMouseTransparent(true);
        gutter.setWrapText(false);
        gutter.setPrefRowCount(rows);

        editor = UiSupport.createArea(value, rows);
        editor.getStyleClass().add("line-number-editor");
        editor.setWrapText(true);

        editor.scrollTopProperty().addListener((observable, previous, current) -> syncGutterScroll());
        editor.textProperty().addListener((observable, previous, current) -> requestLineNumberRefresh());
        editor.widthProperty().addListener((observable, previous, current) -> requestLineNumberRefresh());
        editor.fontProperty().addListener((observable, previous, current) -> requestLineNumberRefresh());
        editor.skinProperty().addListener((observable, previous, current) -> attachContentWidthListener());
        editor.sceneProperty().addListener((observable, previous, current) -> attachContentWidthListener());

        HBox.setHgrow(editor, Priority.ALWAYS);
        getChildren().addAll(gutter, editor);

        updateLineNumbers(value);
        attachContentWidthListener();
        requestLineNumberRefresh();
        syncGutterScroll();
    }

    public TextArea textArea() {
        return editor;
    }

    public String getText() {
        return editor.getText();
    }

    private void updateLineNumbers(String value) {
        String[] lines = normalizeLineEndings(value).split("\n", -1);
        int lineCount = Math.max(1, lines.length);
        double wrapWidth = calculateEditorWrapWidth();
        double lineHeight = calculateLineHeight();
        StringBuilder builder = new StringBuilder(lineCount * 4);
        for (int lineNumber = 1; lineNumber <= lineCount; lineNumber++) {
            if (lineNumber > 1) {
                builder.append('\n');
            }
            builder.append(lineNumber);
            int visualRows = countVisualRows(lines[lineNumber - 1], wrapWidth, lineHeight);
            for (int extraRow = 1; extraRow < visualRows; extraRow++) {
                builder.append('\n');
            }
        }
        gutter.setText(builder.toString());

        double gutterWidth = GUTTER_BASE_WIDTH + (Math.max(2, Integer.toString(lineCount).length()) * GUTTER_DIGIT_WIDTH);
        gutter.setMinWidth(gutterWidth);
        gutter.setPrefWidth(gutterWidth);
        gutter.setMaxWidth(gutterWidth);
        syncGutterScroll();
    }

    private void requestLineNumberRefresh() {
        if (refreshPending) {
            return;
        }
        refreshPending = true;
        Platform.runLater(() -> {
            refreshPending = false;
            updateLineNumbers(editor.getText());
        });
    }

    private void attachContentWidthListener() {
        Platform.runLater(() -> {
            Node contentNode = editor.lookup(".content");
            if (!(contentNode instanceof Region contentRegion)) {
                requestLineNumberRefresh();
                return;
            }
            if (editorContentRegion != contentRegion) {
                if (editorContentRegion != null) {
                    editorContentRegion.widthProperty().removeListener(contentWidthListener);
                }
                editorContentRegion = contentRegion;
                editorContentRegion.widthProperty().addListener(contentWidthListener);
            }
            requestLineNumberRefresh();
        });
    }

    private void syncGutterScroll() {
        double editorScrollTop = editor.getScrollTop();
        if (Double.compare(gutter.getScrollTop(), editorScrollTop) != 0) {
            gutter.setScrollTop(editorScrollTop);
        }
    }

    private int countVisualRows(String line, double wrapWidth, double lineHeight) {
        if (line == null || line.isEmpty() || wrapWidth <= 0 || lineHeight <= 0) {
            return 1;
        }
        measurementText.setFont(editor.getFont());
        measurementText.setWrappingWidth(wrapWidth);
        measurementText.setText(line);
        double measuredHeight = measurementText.getLayoutBounds().getHeight();
        return Math.max(1, (int) Math.ceil(measuredHeight / lineHeight));
    }

    private double calculateEditorWrapWidth() {
        if (editorContentRegion != null) {
            double wrapWidth = editorContentRegion.getWidth()
                    - editorContentRegion.snappedLeftInset()
                    - editorContentRegion.snappedRightInset();
            if (wrapWidth > 0) {
                return wrapWidth;
            }
        }
        double fallbackWidth = editor.getWidth() - EDITOR_WIDTH_FALLBACK_PADDING;
        return Math.max(0, fallbackWidth);
    }

    private double calculateLineHeight() {
        lineHeightText.setFont(editor.getFont());
        return Math.max(1, lineHeightText.getLayoutBounds().getHeight());
    }

    private String normalizeLineEndings(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }
}
