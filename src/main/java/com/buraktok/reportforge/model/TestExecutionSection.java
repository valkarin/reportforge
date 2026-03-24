package com.buraktok.reportforge.model;

import java.util.List;

public enum TestExecutionSection {
    PROJECT_OVERVIEW("Project Overview"),
    APPLICATIONS_UNDER_TEST("Applications under Test"),
    TEST_ENVIRONMENT("Test Environment"),
    TEST_OBJECTIVES_SCOPE("Test Objectives and Scope"),
    BUILD_RELEASE_INFORMATION("Build/Release Information"),
    EXECUTION_SUMMARY("Execution Summary"),
    TEST_COVERAGE("Test Coverage"),
    RISK_ASSESSMENT("Risk Assessment"),
    COMMENTS_AND_NOTES("Report Notes"),
    EXPORT_PRESETS("Export Presets"),
    CONCLUSION_AND_RECOMMENDATIONS("Conclusion and Recommendations");

    private final String displayName;

    TestExecutionSection(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public static List<TestExecutionSection> defaultOrder() {
        return List.of(values());
    }

    public static TestExecutionSection fromKey(String value) {
        if ("DETAILED_TEST_RESULTS".equalsIgnoreCase(value)
                || "DEFECT_SUMMARY".equalsIgnoreCase(value)
                || "TEST_EVIDENCE".equalsIgnoreCase(value)) {
            return EXECUTION_SUMMARY;
        }
        for (TestExecutionSection section : values()) {
            if (section.name().equalsIgnoreCase(value)) {
                return section;
            }
        }
        return PROJECT_OVERVIEW;
    }
}
