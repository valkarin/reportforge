package com.buraktok.reportforge.persistence;

import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ExecutionRunRecord;
import com.buraktok.reportforge.model.ExecutionRunSnapshot;
import com.buraktok.reportforge.model.ProjectWorkspace;
import com.buraktok.reportforge.model.ReportRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static com.buraktok.reportforge.persistence.PersistenceSupport.openConnection;
import static com.buraktok.reportforge.persistence.PersistenceSupport.setNullableInteger;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExecutionRunPersistenceMappingTest {
    @TempDir
    Path tempDir;

    private final ProjectContainerService service = new ProjectContainerService();

    @AfterEach
    void tearDown() {
        service.closeCurrentSession();
    }

    @Test
    void loadExecutionReportSnapshotMapsPersistedRunFieldsAndLegacyCounts() throws Exception {
        service.createProject(tempDir.resolve("mapping-fields.rforge"), "Execution Mapping", "QA", List.of("Portal"));
        ReportRecord report = createReport("Execution Mapping Report");
        ExecutionRunRecord persistedRun = service.loadExecutionReportSnapshot(report.getId()).getRuns().getFirst().getRun();

        ExecutionRunRecord expected = persistedRun.toBuilder()
                .executionKey("SMOKE-42")
                .suiteName("Smoke Cycle")
                .executedBy("QA Engineer")
                .executionDate("2026-03-04")
                .startDate("2026-03-04")
                .endDate("2026-03-05")
                .durationText("1h 15m")
                .dataSourceReference("testrail-run-42")
                .notes("Observed issue during verification.")
                .comments("Escalated to payments team.")
                .testSteps("Open portal\nLogin\nAttempt checkout")
                .testCaseKey("TC-CHECKOUT-42")
                .sectionName("Checkout")
                .subsectionName("Payments")
                .testCaseName("User can complete checkout")
                .priority("Critical")
                .moduleName("Payments")
                .status("BLOCKED")
                .executionTime("00:04:30")
                .expectedResultSummary("Checkout completes successfully.")
                .actualResult("Payment service timed out.")
                .relatedIssue("BUG-17, BUG-18")
                .remarks("Needs retry after backend fix.")
                .blockedReason("Payment gateway unavailable.")
                .defectSummary("Timeout defect still open.")
                .legacyTotalExecuted(null)
                .legacyPassedCount(0)
                .legacyFailedCount(2)
                .legacyBlockedCount(1)
                .legacyNotRunCount(null)
                .legacyDeferredCount(3)
                .legacySkippedCount(0)
                .legacyLinkedDefectCount(4)
                .legacyOverallOutcome("MIXED")
                .sortOrder(0)
                .createdAt("2026-03-04T08:00:00Z")
                .updatedAt("2026-03-05T09:30:00Z")
                .build();

        overwriteRunRow(expected);

        ExecutionRunRecord actual = service.loadExecutionReportSnapshot(report.getId()).getRuns().getFirst().getRun();

        assertRunMatches(expected, actual);
        assertAll(
                () -> assertNull(actual.getLegacyTotalExecuted()),
                () -> assertEquals(Integer.valueOf(0), actual.getLegacyPassedCount()),
                () -> assertEquals(Integer.valueOf(2), actual.getLegacyFailedCount()),
                () -> assertEquals(Integer.valueOf(1), actual.getLegacyBlockedCount()),
                () -> assertNull(actual.getLegacyNotRunCount()),
                () -> assertEquals(Integer.valueOf(3), actual.getLegacyDeferredCount()),
                () -> assertEquals(Integer.valueOf(0), actual.getLegacySkippedCount()),
                () -> assertEquals(Integer.valueOf(4), actual.getLegacyLinkedDefectCount())
        );
    }

    @Test
    void loadExecutionReportSnapshotOrdersRunsBySortOrderThenCreatedAt() throws Exception {
        service.createProject(tempDir.resolve("mapping-order.rforge"), "Execution Ordering", "QA", List.of("Portal"));
        ReportRecord report = createReport("Execution Ordering Report");

        ExecutionRunRecord firstRun = service.loadExecutionReportSnapshot(report.getId()).getRuns().getFirst().getRun();
        ExecutionRunRecord secondRun = service.createExecutionRun(report.getId());
        ExecutionRunRecord thirdRun = service.createExecutionRun(report.getId());

        renameRunId(secondRun.getId(), "run-z");
        renameRunId(thirdRun.getId(), "run-a");
        secondRun = secondRun.toBuilder().id("run-z").build();
        thirdRun = thirdRun.toBuilder().id("run-a").build();

        overwriteRunRow(firstRun.toBuilder()
                .suiteName("First Run")
                .executionKey("FIRST")
                .sortOrder(0)
                .createdAt("2026-03-04T10:00:00Z")
                .updatedAt("2026-03-04T10:00:00Z")
                .build());
        overwriteRunRow(secondRun.toBuilder()
                .suiteName("Second Run")
                .executionKey("SECOND")
                .sortOrder(1)
                .createdAt("2026-03-04T11:00:00Z")
                .updatedAt("2026-03-04T11:00:00Z")
                .build());
        overwriteRunRow(thirdRun.toBuilder()
                .suiteName("Third Run")
                .executionKey("THIRD")
                .sortOrder(1)
                .createdAt("2026-03-04T11:00:00Z")
                .updatedAt("2026-03-04T11:00:00Z")
                .build());

        List<ExecutionRunSnapshot> runs = service.loadExecutionReportSnapshot(report.getId()).getRuns();

        assertAll(
                () -> assertEquals(List.of(firstRun.getId(), "run-a", "run-z"),
                        runs.stream().map(snapshot -> snapshot.getRun().getId()).toList()),
                () -> assertEquals(List.of("First Run", "Third Run", "Second Run"),
                        runs.stream().map(snapshot -> snapshot.getRun().getSuiteName()).toList()),
                () -> assertEquals(List.of(0, 1, 1),
                        runs.stream().map(snapshot -> snapshot.getRun().getSortOrder()).toList())
        );
    }

    private ReportRecord createReport(String title) throws Exception {
        ProjectWorkspace workspace = service.loadWorkspace();
        EnvironmentRecord environment = workspace.getEnvironments().getFirst();
        return service.createTestExecutionReport(environment.getId(), title);
    }

    private void overwriteRunRow(ExecutionRunRecord run) throws Exception {
        try (Connection connection = openConnection(service.getCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE report_execution_runs
                     SET report_id = ?, execution_key = ?, suite_name = ?, executed_by = ?, execution_date = ?,
                         start_date = ?, end_date = ?, duration_text = ?, data_source_reference = ?, notes = ?,
                         comments_text = ?, test_steps_text = ?, test_case_key = ?, section_name = ?, subsection_name = ?,
                         test_case_name = ?, priority = ?, module_name = ?, status = ?, execution_time = ?,
                         expected_result_summary = ?, actual_result = ?, related_issue = ?, remarks = ?, blocked_reason = ?,
                         defect_summary = ?, legacy_total_executed = ?, legacy_passed_count = ?, legacy_failed_count = ?,
                         legacy_blocked_count = ?, legacy_not_run_count = ?, legacy_deferred_count = ?, legacy_skipped_count = ?,
                         legacy_linked_defect_count = ?, legacy_overall_outcome = ?, sort_order = ?, created_at = ?, updated_at = ?
                     WHERE id = ?
                     """)) {
            statement.setString(1, run.getReportId());
            statement.setString(2, run.getExecutionKey());
            statement.setString(3, run.getSuiteName());
            statement.setString(4, run.getExecutedBy());
            statement.setString(5, run.getExecutionDate());
            statement.setString(6, run.getStartDate());
            statement.setString(7, run.getEndDate());
            statement.setString(8, run.getDurationText());
            statement.setString(9, run.getDataSourceReference());
            statement.setString(10, run.getNotes());
            statement.setString(11, run.getComments());
            statement.setString(12, run.getTestSteps());
            statement.setString(13, run.getTestCaseKey());
            statement.setString(14, run.getSectionName());
            statement.setString(15, run.getSubsectionName());
            statement.setString(16, run.getTestCaseName());
            statement.setString(17, run.getPriority());
            statement.setString(18, run.getModuleName());
            statement.setString(19, run.getStatus());
            statement.setString(20, run.getExecutionTime());
            statement.setString(21, run.getExpectedResultSummary());
            statement.setString(22, run.getActualResult());
            statement.setString(23, run.getRelatedIssue());
            statement.setString(24, run.getRemarks());
            statement.setString(25, run.getBlockedReason());
            statement.setString(26, run.getDefectSummary());
            setNullableInteger(statement, 27, run.getLegacyTotalExecuted());
            setNullableInteger(statement, 28, run.getLegacyPassedCount());
            setNullableInteger(statement, 29, run.getLegacyFailedCount());
            setNullableInteger(statement, 30, run.getLegacyBlockedCount());
            setNullableInteger(statement, 31, run.getLegacyNotRunCount());
            setNullableInteger(statement, 32, run.getLegacyDeferredCount());
            setNullableInteger(statement, 33, run.getLegacySkippedCount());
            setNullableInteger(statement, 34, run.getLegacyLinkedDefectCount());
            statement.setString(35, PersistenceSupport.normalizeOutcome(run.getLegacyOverallOutcome()));
            statement.setInt(36, run.getSortOrder());
            statement.setString(37, run.getCreatedAt());
            statement.setString(38, run.getUpdatedAt());
            statement.setString(39, run.getId());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void renameRunId(String currentId, String newId) throws Exception {
        try (Connection connection = openConnection(service.getCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE report_execution_runs SET id = ? WHERE id = ?")) {
            statement.setString(1, newId);
            statement.setString(2, currentId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void assertRunMatches(ExecutionRunRecord expected, ExecutionRunRecord actual) {
        assertAll(
                () -> assertEquals(expected.getId(), actual.getId()),
                () -> assertEquals(expected.getReportId(), actual.getReportId()),
                () -> assertEquals(expected.getExecutionKey(), actual.getExecutionKey()),
                () -> assertEquals(expected.getSuiteName(), actual.getSuiteName()),
                () -> assertEquals(expected.getExecutedBy(), actual.getExecutedBy()),
                () -> assertEquals(expected.getExecutionDate(), actual.getExecutionDate()),
                () -> assertEquals(expected.getStartDate(), actual.getStartDate()),
                () -> assertEquals(expected.getEndDate(), actual.getEndDate()),
                () -> assertEquals(expected.getDurationText(), actual.getDurationText()),
                () -> assertEquals(expected.getDataSourceReference(), actual.getDataSourceReference()),
                () -> assertEquals(expected.getNotes(), actual.getNotes()),
                () -> assertEquals(expected.getComments(), actual.getComments()),
                () -> assertEquals(expected.getTestSteps(), actual.getTestSteps()),
                () -> assertEquals(expected.getTestCaseKey(), actual.getTestCaseKey()),
                () -> assertEquals(expected.getSectionName(), actual.getSectionName()),
                () -> assertEquals(expected.getSubsectionName(), actual.getSubsectionName()),
                () -> assertEquals(expected.getTestCaseName(), actual.getTestCaseName()),
                () -> assertEquals(expected.getPriority(), actual.getPriority()),
                () -> assertEquals(expected.getModuleName(), actual.getModuleName()),
                () -> assertEquals(expected.getStatus(), actual.getStatus()),
                () -> assertEquals(expected.getExecutionTime(), actual.getExecutionTime()),
                () -> assertEquals(expected.getExpectedResultSummary(), actual.getExpectedResultSummary()),
                () -> assertEquals(expected.getActualResult(), actual.getActualResult()),
                () -> assertEquals(expected.getRelatedIssue(), actual.getRelatedIssue()),
                () -> assertEquals(expected.getRemarks(), actual.getRemarks()),
                () -> assertEquals(expected.getBlockedReason(), actual.getBlockedReason()),
                () -> assertEquals(expected.getDefectSummary(), actual.getDefectSummary()),
                () -> assertEquals(expected.getLegacyOverallOutcome(), actual.getLegacyOverallOutcome()),
                () -> assertEquals(expected.getSortOrder(), actual.getSortOrder()),
                () -> assertEquals(expected.getCreatedAt(), actual.getCreatedAt()),
                () -> assertEquals(expected.getUpdatedAt(), actual.getUpdatedAt())
        );
    }
}
