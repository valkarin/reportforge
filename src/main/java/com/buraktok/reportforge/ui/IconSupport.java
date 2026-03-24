package com.buraktok.reportforge.ui;

import org.kordamp.ikonli.javafx.FontIcon;

public final class IconSupport {
    private IconSupport() {
    }

    public static FontIcon createButtonIcon(String iconLiteral) {
        return create(iconLiteral, 14, "button-icon");
    }

    public static FontIcon createSectionIcon(String iconLiteral) {
        return create(iconLiteral, 15, "section-icon");
    }

    public static FontIcon createRecentProjectIcon(String iconLiteral) {
        return create(iconLiteral, 16, "recent-project-icon");
    }

    public static FontIcon createWindowControlIcon(String iconLiteral) {
        return create(iconLiteral, 11, "window-control-icon");
    }

    public static FontIcon createDialogIcon(String iconLiteral) {
        return create(iconLiteral, 16, "dialog-purpose-icon");
    }

    private static FontIcon create(String iconLiteral, int size, String styleClass) {
        FontIcon icon = new FontIcon();
        icon.setIconLiteral(iconLiteral + ":" + size);
        icon.getStyleClass().addAll("app-icon", styleClass);
        return icon;
    }
}
