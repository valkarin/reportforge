package com.buraktok.reportforge.model;

public class ExecutionRunEvidenceRecord {
    private final String id;
    private final String executionRunId;
    private final String storedPath;
    private final String originalFileName;
    private final String mediaType;
    private final int sortOrder;
    private final String createdAt;
    private final String updatedAt;

    public ExecutionRunEvidenceRecord(
            String id,
            String executionRunId,
            String storedPath,
            String originalFileName,
            String mediaType,
            int sortOrder,
            String createdAt,
            String updatedAt
    ) {
        this.id = id;
        this.executionRunId = executionRunId;
        this.storedPath = storedPath;
        this.originalFileName = originalFileName;
        this.mediaType = mediaType;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getExecutionRunId() {
        return executionRunId;
    }

    public String getStoredPath() {
        return storedPath;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public String getMediaType() {
        return mediaType;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public boolean isImage() {
        return mediaType != null && mediaType.startsWith("image/");
    }

    public String getDisplayName() {
        if (originalFileName != null && !originalFileName.isBlank()) {
            return originalFileName;
        }
        if (storedPath == null || storedPath.isBlank()) {
            return "Evidence";
        }
        int separatorIndex = storedPath.lastIndexOf('/');
        return separatorIndex >= 0 ? storedPath.substring(separatorIndex + 1) : storedPath;
    }
}
