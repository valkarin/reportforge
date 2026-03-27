package com.buraktok.reportforge.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionRunRecordTest {

    @Test
    void getDisplayLabelPrefixesNumericExecutionIdsButPreservesDescriptiveLabels() {
        ExecutionRunRecord numericIdRun = createRun("1", "", 0);
        ExecutionRunRecord customIdRun = createRun("AUTH-7", "", 1);
        ExecutionRunRecord namedRun = createRun("2", "Smoke Cycle", 2);

        assertAll(
                () -> assertEquals("Execution Run 1", numericIdRun.getDisplayLabel()),
                () -> assertEquals("AUTH-7", customIdRun.getDisplayLabel()),
                () -> assertEquals("Smoke Cycle", namedRun.getDisplayLabel())
        );
    }

    @Test
    void toBuilderCopiesExistingFieldsAndAppliesNamedUpdates() {
        ExecutionRunRecord original = createRun("1", "", 0);

        ExecutionRunRecord updated = original.toBuilder()
                .suiteName("Regression Cycle")
                .status("FAIL")
                .updatedAt("2026-03-06T08:30:00Z")
                .build();

        assertAll(
                () -> assertEquals(original.getId(), updated.getId()),
                () -> assertEquals(original.getReportId(), updated.getReportId()),
                () -> assertEquals("Regression Cycle", updated.getSuiteName()),
                () -> assertEquals("FAIL", updated.getStatus()),
                () -> assertEquals("2026-03-06T08:30:00Z", updated.getUpdatedAt())
        );
    }

    private static ExecutionRunRecord createRun(String executionKey, String suiteName, int sortOrder) {
        return ExecutionRunRecord.builder()
                .id("run-" + sortOrder)
                .reportId("report-1")
                .executionKey(executionKey)
                .suiteName(suiteName)
                .status("NOT_RUN")
                .legacyOverallOutcome("NOT_EXECUTED")
                .sortOrder(sortOrder)
                .createdAt("2026-03-06T08:00:00Z")
                .updatedAt("2026-03-06T08:10:00Z")
                .build();
    }
}
