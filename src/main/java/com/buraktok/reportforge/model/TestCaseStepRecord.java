package com.buraktok.reportforge.model;

public class TestCaseStepRecord {
    private final String id;
    private final String testCaseResultId;
    private final Integer stepNumber;
    private final String stepAction;
    private final String expectedResult;
    private final String actualResult;
    private final String status;
    private final int sortOrder;
    private final String createdAt;
    private final String updatedAt;

    public TestCaseStepRecord(
            String id,
            String testCaseResultId,
            Integer stepNumber,
            String stepAction,
            String expectedResult,
            String actualResult,
            String status,
            int sortOrder,
            String createdAt,
            String updatedAt
    ) {
        this.id = id;
        this.testCaseResultId = testCaseResultId;
        this.stepNumber = stepNumber;
        this.stepAction = stepAction;
        this.expectedResult = expectedResult;
        this.actualResult = actualResult;
        this.status = status;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getTestCaseResultId() {
        return testCaseResultId;
    }

    public Integer getStepNumber() {
        return stepNumber;
    }

    public String getStepAction() {
        return stepAction;
    }

    public String getExpectedResult() {
        return expectedResult;
    }

    public String getActualResult() {
        return actualResult;
    }

    public String getStatus() {
        return status;
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

    @Override
    public String toString() {
        return "Step " + (stepNumber == null ? sortOrder + 1 : stepNumber);
    }
}
