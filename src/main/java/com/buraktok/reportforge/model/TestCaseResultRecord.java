package com.buraktok.reportforge.model;

public class TestCaseResultRecord {
    private final String id;
    private final String executionRunId;
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
    private final String attachmentReference;
    private final String remarks;
    private final String blockedReason;
    private final int sortOrder;
    private final String createdAt;
    private final String updatedAt;

    public TestCaseResultRecord(
            String id,
            String executionRunId,
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
            String attachmentReference,
            String remarks,
            String blockedReason,
            int sortOrder,
            String createdAt,
            String updatedAt
    ) {
        this.id = id;
        this.executionRunId = executionRunId;
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
        this.attachmentReference = attachmentReference;
        this.remarks = remarks;
        this.blockedReason = blockedReason;
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

    public String getAttachmentReference() {
        return attachmentReference;
    }

    public String getRemarks() {
        return remarks;
    }

    public String getBlockedReason() {
        return blockedReason;
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
        if (testCaseKey != null && !testCaseKey.isBlank() && testCaseName != null && !testCaseName.isBlank()) {
            return testCaseKey + " - " + testCaseName;
        }
        if (testCaseKey != null && !testCaseKey.isBlank()) {
            return testCaseKey;
        }
        if (testCaseName != null && !testCaseName.isBlank()) {
            return testCaseName;
        }
        return "Test Case " + (sortOrder + 1);
    }

    @Override
    public String toString() {
        return getDisplayLabel();
    }
}
