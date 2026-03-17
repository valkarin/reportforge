package com.buraktok.reportforge.model;

public class TestExecutionRecord {
    private final String id;
    private final String reportId;
    private final String startDate;
    private final String endDate;
    private final Integer totalExecuted;
    private final Integer passedCount;
    private final Integer failedCount;
    private final Integer blockedCount;
    private final Integer notRunCount;
    private final Integer deferredCount;
    private final Integer skipCount;
    private final Integer linkedDefectCount;
    private final String cycleName;
    private final String executedBy;
    private final String overallOutcome;
    private final String executionWindow;
    private final String dataSourceReference;
    private final boolean blockedExecutionFlag;
    private final int sortOrder;
    private final String createdAt;
    private final String updatedAt;

    public TestExecutionRecord(
            String id,
            String reportId,
            String startDate,
            String endDate,
            Integer totalExecuted,
            Integer passedCount,
            Integer failedCount,
            Integer blockedCount,
            Integer notRunCount,
            Integer deferredCount,
            Integer skipCount,
            Integer linkedDefectCount,
            String cycleName,
            String executedBy,
            String overallOutcome,
            String executionWindow,
            String dataSourceReference,
            boolean blockedExecutionFlag,
            int sortOrder,
            String createdAt,
            String updatedAt
    ) {
        this.id = id;
        this.reportId = reportId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.totalExecuted = totalExecuted;
        this.passedCount = passedCount;
        this.failedCount = failedCount;
        this.blockedCount = blockedCount;
        this.notRunCount = notRunCount;
        this.deferredCount = deferredCount;
        this.skipCount = skipCount;
        this.linkedDefectCount = linkedDefectCount;
        this.cycleName = cycleName;
        this.executedBy = executedBy;
        this.overallOutcome = overallOutcome;
        this.executionWindow = executionWindow;
        this.dataSourceReference = dataSourceReference;
        this.blockedExecutionFlag = blockedExecutionFlag;
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

    public String getStartDate() {
        return startDate;
    }

    public String getEndDate() {
        return endDate;
    }

    public Integer getTotalExecuted() {
        return totalExecuted;
    }

    public Integer getPassedCount() {
        return passedCount;
    }

    public Integer getFailedCount() {
        return failedCount;
    }

    public Integer getBlockedCount() {
        return blockedCount;
    }

    public Integer getNotRunCount() {
        return notRunCount;
    }

    public Integer getDeferredCount() {
        return deferredCount;
    }

    public Integer getSkipCount() {
        return skipCount;
    }

    public Integer getLinkedDefectCount() {
        return linkedDefectCount;
    }

    public String getCycleName() {
        return cycleName;
    }

    public String getExecutedBy() {
        return executedBy;
    }

    public String getOverallOutcome() {
        return overallOutcome;
    }

    public String getExecutionWindow() {
        return executionWindow;
    }

    public String getDataSourceReference() {
        return dataSourceReference;
    }

    public boolean isBlockedExecutionFlag() {
        return blockedExecutionFlag;
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
        if (cycleName != null && !cycleName.isBlank()) {
            return cycleName;
        }
        if (startDate != null && !startDate.isBlank() && endDate != null && !endDate.isBlank()) {
            return startDate + " - " + endDate;
        }
        if (startDate != null && !startDate.isBlank()) {
            return "Execution starting " + startDate;
        }
        return "Execution " + (sortOrder + 1);
    }

    @Override
    public String toString() {
        String outcomeMarker = overallOutcome == null || overallOutcome.isBlank()
                ? ""
                : " [" + overallOutcome.replace('_', ' ') + "]";
        return getDisplayLabel() + outcomeMarker;
    }
}
