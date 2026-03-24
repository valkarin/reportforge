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
    private final String comments;
    private final String testSteps;
    private final String testCaseKey;
    private final String sectionName;
    private final String subsectionName;
    private final String testCaseName;
    private final String priority;
    private final String moduleName;
    private final String status;
    private final String executionTime;
    private final String expectedResultSummary;
    private final String actualResult;
    private final String relatedIssue;
    private final String remarks;
    private final String blockedReason;
    private final String defectSummary;
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
            String comments,
            String testSteps,
            String testCaseKey,
            String sectionName,
            String subsectionName,
            String testCaseName,
            String priority,
            String moduleName,
            String status,
            String executionTime,
            String expectedResultSummary,
            String actualResult,
            String relatedIssue,
            String remarks,
            String blockedReason,
            String defectSummary,
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
        this.comments = comments;
        this.testSteps = testSteps;
        this.testCaseKey = testCaseKey;
        this.sectionName = sectionName;
        this.subsectionName = subsectionName;
        this.testCaseName = testCaseName;
        this.priority = priority;
        this.moduleName = moduleName;
        this.status = status;
        this.executionTime = executionTime;
        this.expectedResultSummary = expectedResultSummary;
        this.actualResult = actualResult;
        this.relatedIssue = relatedIssue;
        this.remarks = remarks;
        this.blockedReason = blockedReason;
        this.defectSummary = defectSummary;
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

    public String getComments() {
        return comments;
    }

    public String getTestSteps() {
        return testSteps;
    }

    public String getTestCaseKey() {
        return testCaseKey;
    }

    public String getSectionName() {
        return sectionName;
    }

    public String getSubsectionName() {
        return subsectionName;
    }

    public String getTestCaseName() {
        return testCaseName;
    }

    public String getPriority() {
        return priority;
    }

    public String getModuleName() {
        return moduleName;
    }

    public String getStatus() {
        return status;
    }

    public String getExecutionTime() {
        return executionTime;
    }

    public String getExpectedResultSummary() {
        return expectedResultSummary;
    }

    public String getActualResult() {
        return actualResult;
    }

    public String getRelatedIssue() {
        return relatedIssue;
    }

    public String getRemarks() {
        return remarks;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public String getDefectSummary() {
        return defectSummary;
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
            if (executionKey.chars().allMatch(Character::isDigit)) {
                return "Execution Run " + executionKey;
            }
            return executionKey;
        }
        if (testCaseKey != null && !testCaseKey.isBlank() && testCaseName != null && !testCaseName.isBlank()) {
            return testCaseKey + " - " + testCaseName;
        }
        if (testCaseName != null && !testCaseName.isBlank()) {
            return testCaseName;
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
