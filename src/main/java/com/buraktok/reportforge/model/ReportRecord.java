package com.buraktok.reportforge.model;

public class ReportRecord {
    private final String id;
    private final String environmentId;
    private final String title;
    private final ReportStatus status;
    private final String createdAt;
    private final String updatedAt;
    private final String lastSelectedSection;

    public ReportRecord(
            String id,
            String environmentId,
            String title,
            ReportStatus status,
            String createdAt,
            String updatedAt,
            String lastSelectedSection
    ) {
        this.id = id;
        this.environmentId = environmentId;
        this.title = title;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastSelectedSection = lastSelectedSection;
    }

    public String getId() {
        return id;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public String getTitle() {
        return title;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public String getLastSelectedSection() {
        return lastSelectedSection;
    }

    @Override
    public String toString() {
        return title + " [" + status.getDisplayName() + "]";
    }
}
