package com.buraktok.reportforge.ui;

import javafx.scene.text.Font;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public final class FontSupport {
    private static final List<String> FONT_RESOURCES = List.of(
            "/com/buraktok/reportforge/Roboto-Regular.ttf",
            "/com/buraktok/reportforge/Roboto-SemiBold.ttf",
            "/com/buraktok/reportforge/Roboto-Bold.ttf"
    );

    private static boolean loaded;

    private FontSupport() {
    }

    public static void loadBundledFonts() {
        if (loaded) {
            return;
        }

        for (String fontResource : FONT_RESOURCES) {
            try (InputStream inputStream = FontSupport.class.getResourceAsStream(fontResource)) {
                if (inputStream == null) {
                    throw new IllegalStateException("Missing bundled font resource: " + fontResource);
                }

                Font font = Font.loadFont(inputStream, 12);
                if (font == null) {
                    throw new IllegalStateException("Unable to load bundled font resource: " + fontResource);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to load bundled font resource: " + fontResource, exception);
            }
        }

        loaded = true;
    }
}
