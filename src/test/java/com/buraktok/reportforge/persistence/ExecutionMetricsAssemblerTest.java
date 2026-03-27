package com.buraktok.reportforge.persistence;

import com.buraktok.reportforge.model.ExecutionMetrics;
import com.buraktok.reportforge.model.ExecutionRunEvidenceRecord;
import com.buraktok.reportforge.model.ExecutionRunRecord;
import com.buraktok.reportforge.model.ExecutionRunSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionMetricsAssemblerTest {
    private final ExecutionMetricsAssembler assembler = new ExecutionMetricsAssembler();

    @Test
    void buildRunMetricsNormalizesStatusAndCountsIssuesEvidenceAndDates() {
        ExecutionRunRecord run = runRecord(
                "run-1",
                "NOT_EXECUTED",
                "BUG-1, BUG-2;\nBUG-3",
                "2026-03-03",
                "2026-03-01",
                "2026-03-04"
        );

        ExecutionMetrics metrics = assembler.buildRunMetrics(
                run,
                List.of(
                        evidence("evidence-1", "run-1"),
                        evidence("evidence-2", "run-1")
                )
        );

        assertAll(
                () -> assertEquals(1, metrics.getExecutionRunCount()),
                () -> assertEquals(1, metrics.getTestCaseCount()),
                () -> assertEquals(1, metrics.getNotRunCount()),
                () -> assertEquals(3, metrics.getIssueCount()),
                () -> assertEquals(2, metrics.getEvidenceCount()),
                () -> assertEquals("NOT_RUN", metrics.getOverallOutcome()),
                () -> assertEquals("2026-03-01", metrics.getEarliestDate()),
                () -> assertEquals("2026-03-04", metrics.getLatestDate())
        );
    }

    @Test
    void buildReportMetricsAggregatesMixedRunOutcomesAndDateBoundaries() {
        ExecutionRunRecord passedRun = runRecord("run-1", "PASS", "", "", "2026-03-01", "2026-03-02");
        ExecutionMetrics passedMetrics = assembler.buildRunMetrics(passedRun, List.of());

        ExecutionRunRecord failedRun = runRecord("run-2", "FAIL", "BUG-9", "2026-03-05", "", "");
        ExecutionRunEvidenceRecord failedEvidence = evidence("evidence-3", "run-2");
        ExecutionMetrics failedMetrics = assembler.buildRunMetrics(failedRun, List.of(failedEvidence));

        ExecutionMetrics reportMetrics = assembler.buildReportMetrics(List.of(
                new ExecutionRunSnapshot(passedRun, List.of(), passedMetrics),
                new ExecutionRunSnapshot(failedRun, List.of(failedEvidence), failedMetrics)
        ));

        assertAll(
                () -> assertEquals(2, reportMetrics.getExecutionRunCount()),
                () -> assertEquals(2, reportMetrics.getTestCaseCount()),
                () -> assertEquals(1, reportMetrics.getPassedCount()),
                () -> assertEquals(1, reportMetrics.getFailedCount()),
                () -> assertEquals(1, reportMetrics.getIssueCount()),
                () -> assertEquals(1, reportMetrics.getEvidenceCount()),
                () -> assertEquals("MIXED", reportMetrics.getOverallOutcome()),
                () -> assertEquals("2026-03-01", reportMetrics.getEarliestDate()),
                () -> assertEquals("2026-03-05", reportMetrics.getLatestDate())
        );
    }

    private ExecutionRunRecord runRecord(
            String runId,
            String status,
            String relatedIssue,
            String executionDate,
            String startDate,
            String endDate
    ) {
        return ExecutionRunRecord.builder()
                .id(runId)
                .reportId("report-1")
                .executionKey("1")
                .executionDate(executionDate)
                .startDate(startDate)
                .endDate(endDate)
                .status(status)
                .relatedIssue(relatedIssue)
                .sortOrder(0)
                .createdAt("2026-03-01T10:00:00Z")
                .updatedAt("2026-03-01T10:00:00Z")
                .build();
    }

    private ExecutionRunEvidenceRecord evidence(String evidenceId, String executionRunId) {
        return new ExecutionRunEvidenceRecord(
                evidenceId,
                executionRunId,
                "evidence/" + evidenceId + ".png",
                "failure.png",
                "image/png",
                0,
                "2026-03-01T10:00:00Z",
                "2026-03-01T10:00:00Z"
        );
    }
}
