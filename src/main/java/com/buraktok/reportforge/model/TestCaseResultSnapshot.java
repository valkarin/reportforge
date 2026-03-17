package com.buraktok.reportforge.model;

import java.util.List;

public class TestCaseResultSnapshot {
    private final TestCaseResultRecord result;
    private final List<TestCaseStepRecord> steps;

    public TestCaseResultSnapshot(TestCaseResultRecord result, List<TestCaseStepRecord> steps) {
        this.result = result;
        this.steps = List.copyOf(steps);
    }

    public TestCaseResultRecord getResult() {
        return result;
    }

    public List<TestCaseStepRecord> getSteps() {
        return steps;
    }
}
