package com.buraktok.reportforge.model;

public class ExecutionMetrics {
    private final int executionRunCount;
    private final int testCaseCount;
    private final int passedCount;
    private final int failedCount;
    private final int blockedCount;
    private final int notRunCount;
    private final int deferredCount;
    private final int skippedCount;
    private final int issueCount;
    private final int evidenceCount;
    private final String overallOutcome;
    private final String earliestDate;
    private final String latestDate;

    public ExecutionMetrics(
            int executionRunCount,
            int testCaseCount,
            int passedCount,
            int failedCount,
            int blockedCount,
            int notRunCount,
            int deferredCount,
            int skippedCount,
            int issueCount,
            int evidenceCount,
            String overallOutcome,
            String earliestDate,
            String latestDate
    ) {
        this.executionRunCount = executionRunCount;
        this.testCaseCount = testCaseCount;
        this.passedCount = passedCount;
        this.failedCount = failedCount;
        this.blockedCount = blockedCount;
        this.notRunCount = notRunCount;
        this.deferredCount = deferredCount;
        this.skippedCount = skippedCount;
        this.issueCount = issueCount;
        this.evidenceCount = evidenceCount;
        this.overallOutcome = overallOutcome;
        this.earliestDate = earliestDate;
        this.latestDate = latestDate;
    }

    public int getExecutionRunCount() {
        return executionRunCount;
    }

    public int getTestCaseCount() {
        return testCaseCount;
    }

    public int getPassedCount() {
        return passedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public int getBlockedCount() {
        return blockedCount;
    }

    public int getNotRunCount() {
        return notRunCount;
    }

    public int getDeferredCount() {
        return deferredCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public int getIssueCount() {
        return issueCount;
    }

    public int getEvidenceCount() {
        return evidenceCount;
    }

    public String getOverallOutcome() {
        return overallOutcome;
    }

    public String getEarliestDate() {
        return earliestDate;
    }

    public String getLatestDate() {
        return latestDate;
    }

    public int getTotalExecuted() {
        return testCaseCount;
    }

    public int getLinkedDefectCount() {
        return issueCount;
    }
}
