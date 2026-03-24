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

    private static ExecutionRunRecord createRun(String executionKey, String suiteName, int sortOrder) {
        return new ExecutionRunRecord(
                "run-" + sortOrder,
                "report-1",
                executionKey,
                suiteName,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "NOT_RUN",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "NOT_EXECUTED",
                sortOrder,
                "2026-03-06T08:00:00Z",
                "2026-03-06T08:10:00Z"
        );
    }
}
