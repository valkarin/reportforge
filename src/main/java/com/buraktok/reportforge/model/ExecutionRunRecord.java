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

    private ExecutionRunRecord(
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

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
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

    public static final class Builder {
        private String id;
        private String reportId;
        private String executionKey;
        private String suiteName;
        private String executedBy;
        private String executionDate;
        private String startDate;
        private String endDate;
        private String durationText;
        private String dataSourceReference;
        private String notes;
        private String comments;
        private String testSteps;
        private String testCaseKey;
        private String sectionName;
        private String subsectionName;
        private String testCaseName;
        private String priority;
        private String moduleName;
        private String status;
        private String executionTime;
        private String expectedResultSummary;
        private String actualResult;
        private String relatedIssue;
        private String remarks;
        private String blockedReason;
        private String defectSummary;
        private Integer legacyTotalExecuted;
        private Integer legacyPassedCount;
        private Integer legacyFailedCount;
        private Integer legacyBlockedCount;
        private Integer legacyNotRunCount;
        private Integer legacyDeferredCount;
        private Integer legacySkippedCount;
        private Integer legacyLinkedDefectCount;
        private String legacyOverallOutcome;
        private int sortOrder;
        private String createdAt;
        private String updatedAt;

        private Builder() {
        }

        private Builder(ExecutionRunRecord source) {
            this.id = source.id;
            this.reportId = source.reportId;
            this.executionKey = source.executionKey;
            this.suiteName = source.suiteName;
            this.executedBy = source.executedBy;
            this.executionDate = source.executionDate;
            this.startDate = source.startDate;
            this.endDate = source.endDate;
            this.durationText = source.durationText;
            this.dataSourceReference = source.dataSourceReference;
            this.notes = source.notes;
            this.comments = source.comments;
            this.testSteps = source.testSteps;
            this.testCaseKey = source.testCaseKey;
            this.sectionName = source.sectionName;
            this.subsectionName = source.subsectionName;
            this.testCaseName = source.testCaseName;
            this.priority = source.priority;
            this.moduleName = source.moduleName;
            this.status = source.status;
            this.executionTime = source.executionTime;
            this.expectedResultSummary = source.expectedResultSummary;
            this.actualResult = source.actualResult;
            this.relatedIssue = source.relatedIssue;
            this.remarks = source.remarks;
            this.blockedReason = source.blockedReason;
            this.defectSummary = source.defectSummary;
            this.legacyTotalExecuted = source.legacyTotalExecuted;
            this.legacyPassedCount = source.legacyPassedCount;
            this.legacyFailedCount = source.legacyFailedCount;
            this.legacyBlockedCount = source.legacyBlockedCount;
            this.legacyNotRunCount = source.legacyNotRunCount;
            this.legacyDeferredCount = source.legacyDeferredCount;
            this.legacySkippedCount = source.legacySkippedCount;
            this.legacyLinkedDefectCount = source.legacyLinkedDefectCount;
            this.legacyOverallOutcome = source.legacyOverallOutcome;
            this.sortOrder = source.sortOrder;
            this.createdAt = source.createdAt;
            this.updatedAt = source.updatedAt;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder reportId(String reportId) {
            this.reportId = reportId;
            return this;
        }

        public Builder executionKey(String executionKey) {
            this.executionKey = executionKey;
            return this;
        }

        public Builder suiteName(String suiteName) {
            this.suiteName = suiteName;
            return this;
        }

        public Builder executedBy(String executedBy) {
            this.executedBy = executedBy;
            return this;
        }

        public Builder executionDate(String executionDate) {
            this.executionDate = executionDate;
            return this;
        }

        public Builder startDate(String startDate) {
            this.startDate = startDate;
            return this;
        }

        public Builder endDate(String endDate) {
            this.endDate = endDate;
            return this;
        }

        public Builder durationText(String durationText) {
            this.durationText = durationText;
            return this;
        }

        public Builder dataSourceReference(String dataSourceReference) {
            this.dataSourceReference = dataSourceReference;
            return this;
        }

        public Builder notes(String notes) {
            this.notes = notes;
            return this;
        }

        public Builder comments(String comments) {
            this.comments = comments;
            return this;
        }

        public Builder testSteps(String testSteps) {
            this.testSteps = testSteps;
            return this;
        }

        public Builder testCaseKey(String testCaseKey) {
            this.testCaseKey = testCaseKey;
            return this;
        }

        public Builder sectionName(String sectionName) {
            this.sectionName = sectionName;
            return this;
        }

        public Builder subsectionName(String subsectionName) {
            this.subsectionName = subsectionName;
            return this;
        }

        public Builder testCaseName(String testCaseName) {
            this.testCaseName = testCaseName;
            return this;
        }

        public Builder priority(String priority) {
            this.priority = priority;
            return this;
        }

        public Builder moduleName(String moduleName) {
            this.moduleName = moduleName;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder executionTime(String executionTime) {
            this.executionTime = executionTime;
            return this;
        }

        public Builder expectedResultSummary(String expectedResultSummary) {
            this.expectedResultSummary = expectedResultSummary;
            return this;
        }

        public Builder actualResult(String actualResult) {
            this.actualResult = actualResult;
            return this;
        }

        public Builder relatedIssue(String relatedIssue) {
            this.relatedIssue = relatedIssue;
            return this;
        }

        public Builder remarks(String remarks) {
            this.remarks = remarks;
            return this;
        }

        public Builder blockedReason(String blockedReason) {
            this.blockedReason = blockedReason;
            return this;
        }

        public Builder defectSummary(String defectSummary) {
            this.defectSummary = defectSummary;
            return this;
        }

        public Builder legacyTotalExecuted(Integer legacyTotalExecuted) {
            this.legacyTotalExecuted = legacyTotalExecuted;
            return this;
        }

        public Builder legacyPassedCount(Integer legacyPassedCount) {
            this.legacyPassedCount = legacyPassedCount;
            return this;
        }

        public Builder legacyFailedCount(Integer legacyFailedCount) {
            this.legacyFailedCount = legacyFailedCount;
            return this;
        }

        public Builder legacyBlockedCount(Integer legacyBlockedCount) {
            this.legacyBlockedCount = legacyBlockedCount;
            return this;
        }

        public Builder legacyNotRunCount(Integer legacyNotRunCount) {
            this.legacyNotRunCount = legacyNotRunCount;
            return this;
        }

        public Builder legacyDeferredCount(Integer legacyDeferredCount) {
            this.legacyDeferredCount = legacyDeferredCount;
            return this;
        }

        public Builder legacySkippedCount(Integer legacySkippedCount) {
            this.legacySkippedCount = legacySkippedCount;
            return this;
        }

        public Builder legacyLinkedDefectCount(Integer legacyLinkedDefectCount) {
            this.legacyLinkedDefectCount = legacyLinkedDefectCount;
            return this;
        }

        public Builder legacyOverallOutcome(String legacyOverallOutcome) {
            this.legacyOverallOutcome = legacyOverallOutcome;
            return this;
        }

        public Builder sortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
            return this;
        }

        public Builder createdAt(String createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(String updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public ExecutionRunRecord build() {
            return new ExecutionRunRecord(
                    id,
                    reportId,
                    executionKey,
                    suiteName,
                    executedBy,
                    executionDate,
                    startDate,
                    endDate,
                    durationText,
                    dataSourceReference,
                    notes,
                    comments,
                    testSteps,
                    testCaseKey,
                    sectionName,
                    subsectionName,
                    testCaseName,
                    priority,
                    moduleName,
                    status,
                    executionTime,
                    expectedResultSummary,
                    actualResult,
                    relatedIssue,
                    remarks,
                    blockedReason,
                    defectSummary,
                    legacyTotalExecuted,
                    legacyPassedCount,
                    legacyFailedCount,
                    legacyBlockedCount,
                    legacyNotRunCount,
                    legacyDeferredCount,
                    legacySkippedCount,
                    legacyLinkedDefectCount,
                    legacyOverallOutcome,
                    sortOrder,
                    createdAt,
                    updatedAt
            );
        }
    }
}
