package com.buraktok.reportforge.ui;

public enum ThemeMode {
    DARK("dark", "theme-dark"),
    LIGHT("light", "theme-light");

    private final String preferenceValue;
    private final String cssClass;

    ThemeMode(String preferenceValue, String cssClass) {
        this.preferenceValue = preferenceValue;
        this.cssClass = cssClass;
    }

    public static ThemeMode fromPreferenceValue(String value) {
        for (ThemeMode candidate : values()) {
            if (candidate.preferenceValue.equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        return DARK;
    }

    public String preferenceValue() {
        return preferenceValue;
    }

    public String cssClass() {
        return cssClass;
    }
}
