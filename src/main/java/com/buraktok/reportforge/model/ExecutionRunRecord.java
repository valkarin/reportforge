package com.buraktok.reportforge.model;

public class ExecutionRunRecord {
    private final String id;
    private final String reportId;
    private final String executionKey;
    private final String suiteName;
    private final String executedBy;
    private final String executionDate;
    private final String startDate;
    private final String endDate;
    private final String durationText;
    private final String dataSourceReference;
    private final String notes;
    private final Integer legacyTotalExecuted;
    private final Integer legacyPassedCount;
    private final Integer legacyFailedCount;
    private final Integer legacyBlockedCount;
    private final Integer legacyNotRunCount;
    private final Integer legacyDeferredCount;
    private final Integer legacySkippedCount;
    private final Integer legacyLinkedDefectCount;
    private final String legacyOverallOutcome;
    private final int sortOrder;
    private final String createdAt;
    private final String updatedAt;

    public ExecutionRunRecord(
            String id,
            String reportId,
            String executionKey,
            String suiteName,
            String executedBy,
            String executionDate,
            String startDate,
            String endDate,
            String durationText,
            String dataSourceReference,
            String notes,
            Integer legacyTotalExecuted,
            Integer legacyPassedCount,
            Integer legacyFailedCount,
            Integer legacyBlockedCount,
            Integer legacyNotRunCount,
            Integer legacyDeferredCount,
            Integer legacySkippedCount,
            Integer legacyLinkedDefectCount,
            String legacyOverallOutcome,
            int sortOrder,
            String createdAt,
            String updatedAt
    ) {
        this.id = id;
        this.reportId = reportId;
        this.executionKey = executionKey;
        this.suiteName = suiteName;
        this.executedBy = executedBy;
        this.executionDate = executionDate;
        this.startDate = startDate;
        this.endDate = endDate;
        this.durationText = durationText;
        this.dataSourceReference = dataSourceReference;
        this.notes = notes;
        this.legacyTotalExecuted = legacyTotalExecuted;
        this.legacyPassedCount = legacyPassedCount;
        this.legacyFailedCount = legacyFailedCount;
        this.legacyBlockedCount = legacyBlockedCount;
        this.legacyNotRunCount = legacyNotRunCount;
        this.legacyDeferredCount = legacyDeferredCount;
        this.legacySkippedCount = legacySkippedCount;
        this.legacyLinkedDefectCount = legacyLinkedDefectCount;
        this.legacyOverallOutcome = legacyOverallOutcome;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getReportId() {
        return reportId;
    }

    public String getExecutionKey() {
        return executionKey;
    }

    public String getSuiteName() {
        return suiteName;
    }

    public String getExecutedBy() {
        return executedBy;
    }

    public String getExecutionDate() {
        return executionDate;
    }

    public String getStartDate() {
        return startDate;
    }

    public String getEndDate() {
        return endDate;
    }

    public String getDurationText() {
        return durationText;
    }

    public String getDataSourceReference() {
        return dataSourceReference;
    }

    public String getNotes() {
        return notes;
    }

    public Integer getLegacyTotalExecuted() {
        return legacyTotalExecuted;
    }

    public Integer getLegacyPassedCount() {
        return legacyPassedCount;
    }

    public Integer getLegacyFailedCount() {
        return legacyFailedCount;
    }

    public Integer getLegacyBlockedCount() {
        return legacyBlockedCount;
    }

    public Integer getLegacyNotRunCount() {
        return legacyNotRunCount;
    }

    public Integer getLegacyDeferredCount() {
        return legacyDeferredCount;
    }

    public Integer getLegacySkippedCount() {
        return legacySkippedCount;
    }

    public Integer getLegacyLinkedDefectCount() {
        return legacyLinkedDefectCount;
    }

    public String getLegacyOverallOutcome() {
        return legacyOverallOutcome;
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

    public String getDisplayLabel() {
        if (suiteName != null && !suiteName.isBlank()) {
            return suiteName;
        }
        if (executionKey != null && !executionKey.isBlank()) {
            return executionKey;
        }
        if (executionDate != null && !executionDate.isBlank()) {
            return "Run " + executionDate;
        }
        return "Execution Run " + (sortOrder + 1);
    }

    @Override
    public String toString() {
        return getDisplayLabel();
    }
}
