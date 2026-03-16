package com.buraktok.reportforge.model;

public enum ReportStatus {
    DRAFT("Draft"),
    REVIEW("Review"),
    FINAL("Final");

    private final String displayName;

    ReportStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public static ReportStatus fromDatabaseValue(String value) {
        for (ReportStatus status : values()) {
            if (status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        return DRAFT;
    }
}
