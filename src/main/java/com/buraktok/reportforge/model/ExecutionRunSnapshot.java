package com.buraktok.reportforge.model;

import java.util.List;

public class ExecutionRunSnapshot {
    private final ExecutionRunRecord run;
    private final List<TestCaseResultSnapshot> testCaseResults;
    private final ExecutionMetrics metrics;

    public ExecutionRunSnapshot(
            ExecutionRunRecord run,
            List<TestCaseResultSnapshot> testCaseResults,
            ExecutionMetrics metrics
    ) {
        this.run = run;
        this.testCaseResults = List.copyOf(testCaseResults);
        this.metrics = metrics;
    }

    public ExecutionRunRecord getRun() {
        return run;
    }

    public List<TestCaseResultSnapshot> getTestCaseResults() {
        return testCaseResults;
    }

    public ExecutionMetrics getMetrics() {
        return metrics;
    }
}
