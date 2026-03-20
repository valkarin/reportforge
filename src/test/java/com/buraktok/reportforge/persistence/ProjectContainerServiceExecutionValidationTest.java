package com.buraktok.reportforge.persistence;

import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ExecutionMetrics;
import com.buraktok.reportforge.model.ExecutionReportSnapshot;
import com.buraktok.reportforge.model.ExecutionRunEvidenceRecord;
import com.buraktok.reportforge.model.ExecutionRunRecord;
import com.buraktok.reportforge.model.ExecutionRunSnapshot;
import com.buraktok.reportforge.model.ProjectWorkspace;
import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.TestExecutionRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectContainerServiceExecutionValidationTest {
    private static final byte[] SAMPLE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9WlH0j8AAAAASUVORK5CYII="
    );

    @TempDir
    Path tempDir;

    private final ProjectContainerService service = new ProjectContainerService();

    @AfterEach
    void tearDown() {
        service.closeCurrentSession();
    }

    @Test
    void saveAndReopenPreservesRunOwnedMetricsAndEvidence() throws Exception {
        Path projectFile = tempDir.resolve("execution-validation.rforge");
        service.createProject(projectFile, "Execution Validation", "QA", List.of("Portal"));
        ReportRecord report = createReport("Release Validation");

        ExecutionRunSnapshot firstSnapshot = service.loadExecutionReportSnapshot(report.getId()).getRuns().getFirst();
        ExecutionRunRecord passedRun = copyRun(
                firstSnapshot.getRun(),
                "Smoke Cycle",
                "QA Engineer",
                "2026-03-01",
                "TC-LOGIN-01",
                "Login succeeds",
                "PASS",
                "User authenticated successfully.",
                "",
                "",
                ""
        );
        service.updateExecutionRun(passedRun);

        ExecutionRunRecord createdRun = service.createExecutionRun(report.getId());
        ExecutionRunRecord failedRun = copyRun(
                createdRun,
                "Regression Cycle",
                "QA Engineer",
                "2026-03-02",
                "TC-CHECKOUT-02",
                "Checkout fails on timeout",
                "FAIL",
                "Checkout failed because the payment service timed out.",
                "BUG-17",
                "Payment service timeout.",
                "Observed intermittently in QA."
        );
        service.updateExecutionRun(failedRun);
        service.addExecutionRunEvidence(report.getId(), failedRun.getId(), "failure.png", "image/png", SAMPLE_PNG);

        service.saveProject();
        service.closeCurrentSession();
        service.openProject(projectFile);

        ExecutionReportSnapshot reopened = service.loadExecutionReportSnapshot(report.getId());
        ExecutionMetrics metrics = reopened.getMetrics();
        ExecutionRunSnapshot reopenedFailedRun = reopened.getRuns().stream()
                .filter(runSnapshot -> failedRun.getId().equals(runSnapshot.getRun().getId()))
                .findFirst()
                .orElseThrow();
        ExecutionRunEvidenceRecord evidence = reopenedFailedRun.getEvidences().getFirst();

        assertAll(
                () -> assertEquals(2, reopened.getRuns().size()),
                () -> assertEquals(2, metrics.getExecutionRunCount()),
                () -> assertEquals(2, metrics.getTestCaseCount()),
                () -> assertEquals(1, metrics.getPassedCount()),
                () -> assertEquals(1, metrics.getFailedCount()),
                () -> assertEquals(1, metrics.getIssueCount()),
                () -> assertEquals(1, metrics.getEvidenceCount()),
                () -> assertEquals("MIXED", metrics.getOverallOutcome()),
                () -> assertEquals("2026-03-01", metrics.getEarliestDate()),
                () -> assertEquals("2026-03-02", metrics.getLatestDate()),
                () -> assertEquals("FAIL", reopenedFailedRun.getRun().getStatus()),
                () -> assertEquals("Checkout fails on timeout", reopenedFailedRun.getRun().getTestCaseName()),
                () -> assertEquals("Payment service timeout.", reopenedFailedRun.getRun().getDefectSummary()),
                () -> assertEquals(1, reopenedFailedRun.getEvidences().size()),
                () -> assertTrue(evidence.getStoredPath().startsWith("evidence/")),
                () -> assertTrue(Files.exists(service.resolveProjectPath(evidence.getStoredPath())))
        );
    }

    @Test
    void loadExecutionReportSnapshotMigratesLegacyExecutionSummaryIntoRun() throws Exception {
        service.createProject(tempDir.resolve("legacy-summary-validation.rforge"), "Legacy Summary Validation", "QA", List.of("Portal"));
        ReportRecord report = createReport("Legacy Summary Report");

        ExecutionRunSnapshot initialRun = service.loadExecutionReportSnapshot(report.getId()).getRuns().getFirst();
        service.deleteExecutionRun(report.getId(), initialRun.getRun().getId());

        TestExecutionRecord legacyExecution = service.createReportExecution(report.getId());
        service.updateReportExecution(new TestExecutionRecord(
                legacyExecution.getId(),
                legacyExecution.getReportId(),
                "2026-02-01",
                "2026-02-02",
                5,
                3,
                2,
                0,
                0,
                0,
                0,
                1,
                "Legacy Cycle",
                "Automation Runner",
                "FAILED",
                "02:00",
                "legacy-import",
                false,
                legacyExecution.getSortOrder(),
                legacyExecution.getCreatedAt(),
                legacyExecution.getUpdatedAt()
        ));

        ExecutionReportSnapshot migrated = service.loadExecutionReportSnapshot(report.getId());
        ExecutionRunRecord run = migrated.getRuns().getFirst().getRun();

        assertAll(
                () -> assertEquals(1, migrated.getRuns().size()),
                () -> assertEquals("Legacy Cycle", run.getSuiteName()),
                () -> assertEquals("FAIL", run.getStatus()),
                () -> assertTrue(run.getNotes().contains("Migrated from the earlier execution summary model.")),
                () -> assertTrue(run.getDefectSummary().contains("Failed items: 2")),
                () -> assertTrue(run.getDefectSummary().contains("Linked issues: 1")),
                () -> assertEquals(1, migrated.getMetrics().getExecutionRunCount()),
                () -> assertEquals(1, migrated.getMetrics().getFailedCount()),
                () -> assertEquals(1, migrated.getMetrics().getIssueCount()),
                () -> assertEquals("FAIL", migrated.getMetrics().getOverallOutcome())
        );
    }

    @Test
    void loadExecutionReportSnapshotCollapsesLegacyDetailedResultsIntoRunFields() throws Exception {
        service.createProject(tempDir.resolve("legacy-details-validation.rforge"), "Legacy Details Validation", "QA", List.of("Portal"));
        ReportRecord report = createReport("Legacy Details Report");

        ExecutionRunSnapshot initialSnapshot = service.loadExecutionReportSnapshot(report.getId()).getRuns().getFirst();
        ExecutionRunRecord blankStatusRun = copyRun(
                initialSnapshot.getRun(),
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "Original note"
        );
        service.updateExecutionRun(blankStatusRun);

        insertLegacyDetailedResult(blankStatusRun.getId());

        ExecutionReportSnapshot migrated = service.loadExecutionReportSnapshot(report.getId());
        ExecutionRunRecord run = migrated.getRuns().getFirst().getRun();

        assertAll(
                () -> assertEquals("FAIL", run.getStatus()),
                () -> assertEquals("TC-LOGIN-01", run.getTestCaseKey()),
                () -> assertEquals("Authentication", run.getSectionName()),
                () -> assertEquals("Login", run.getSubsectionName()),
                () -> assertEquals("Login should succeed", run.getTestCaseName()),
                () -> assertEquals("High", run.getPriority()),
                () -> assertEquals("Portal", run.getModuleName()),
                () -> assertTrue(run.getActualResult().contains("Authentication error shown.")),
                () -> assertTrue(run.getActualResult().contains("Step Trace:")),
                () -> assertTrue(run.getRelatedIssue().contains("BUG-17")),
                () -> assertTrue(run.getDefectSummary().contains("Issue: BUG-17")),
                () -> assertTrue(run.getNotes().contains("Collapsed 1 legacy detailed result row(s) into this execution run.")),
                () -> assertTrue(run.getNotes().contains("Legacy attachment references: legacy-screenshot.png")),
                () -> assertTrue(run.getNotes().contains("Legacy step trace was preserved in the Actual Result field.")),
                () -> assertEquals(1, migrated.getMetrics().getFailedCount()),
                () -> assertEquals(1, migrated.getMetrics().getIssueCount())
        );
    }

    private ReportRecord createReport(String title) throws Exception {
        ProjectWorkspace workspace = service.loadWorkspace();
        EnvironmentRecord environment = workspace.getEnvironments().getFirst();
        return service.createTestExecutionReport(environment.getId(), title);
    }

    private ExecutionRunRecord copyRun(
            ExecutionRunRecord original,
            String suiteName,
            String executedBy,
            String executionDate,
            String testCaseKey,
            String testCaseName,
            String status,
            String actualResult,
            String relatedIssue,
            String defectSummary,
            String notes
    ) {
        return new ExecutionRunRecord(
                original.getId(),
                original.getReportId(),
                original.getExecutionKey(),
                suiteName,
                executedBy,
                executionDate,
                original.getStartDate(),
                original.getEndDate(),
                original.getDurationText(),
                original.getDataSourceReference(),
                notes,
                testCaseKey,
                original.getSectionName(),
                original.getSubsectionName(),
                testCaseName,
                original.getPriority(),
                original.getModuleName(),
                status,
                original.getExecutionTime(),
                original.getExpectedResultSummary(),
                actualResult,
                relatedIssue,
                original.getRemarks(),
                original.getBlockedReason(),
                defectSummary,
                original.getLegacyTotalExecuted(),
                original.getLegacyPassedCount(),
                original.getLegacyFailedCount(),
                original.getLegacyBlockedCount(),
                original.getLegacyNotRunCount(),
                original.getLegacyDeferredCount(),
                original.getLegacySkippedCount(),
                original.getLegacyLinkedDefectCount(),
                original.getLegacyOverallOutcome(),
                original.getSortOrder(),
                original.getCreatedAt(),
                original.getUpdatedAt()
        );
    }

    private void insertLegacyDetailedResult(String executionRunId) throws Exception {
        ProjectSession session = service.getCurrentSession();
        String timestamp = Instant.now().toString();
        String resultId = UUID.randomUUID().toString();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + session.databasePath())) {
            try (Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA foreign_keys = ON");
            }

            try (PreparedStatement resultStatement = connection.prepareStatement("""
                    INSERT INTO report_test_case_results (
                        id, execution_run_id, test_case_key, section_name, subsection_name, test_case_name, priority,
                        module_name, status, execution_time, expected_result_summary, actual_result, related_issue,
                        attachment_reference, remarks, blocked_reason, sort_order, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                resultStatement.setString(1, resultId);
                resultStatement.setString(2, executionRunId);
                resultStatement.setString(3, "TC-LOGIN-01");
                resultStatement.setString(4, "Authentication");
                resultStatement.setString(5, "Login");
                resultStatement.setString(6, "Login should succeed");
                resultStatement.setString(7, "High");
                resultStatement.setString(8, "Portal");
                resultStatement.setString(9, "FAILED");
                resultStatement.setString(10, "15s");
                resultStatement.setString(11, "User signs in successfully.");
                resultStatement.setString(12, "Authentication error shown.");
                resultStatement.setString(13, "BUG-17");
                resultStatement.setString(14, "legacy-screenshot.png");
                resultStatement.setString(15, "Observed error toast.");
                resultStatement.setString(16, "");
                resultStatement.setInt(17, 0);
                resultStatement.setString(18, timestamp);
                resultStatement.setString(19, timestamp);
                resultStatement.executeUpdate();
            }

            try (PreparedStatement stepStatement = connection.prepareStatement("""
                    INSERT INTO report_test_case_steps (
                        id, test_case_result_id, step_number, step_action, expected_result, actual_result, status,
                        sort_order, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                stepStatement.setString(1, UUID.randomUUID().toString());
                stepStatement.setString(2, resultId);
                stepStatement.setInt(3, 1);
                stepStatement.setString(4, "Enter valid credentials");
                stepStatement.setString(5, "Dashboard opens");
                stepStatement.setString(6, "Error banner displayed");
                stepStatement.setString(7, "FAILED");
                stepStatement.setInt(8, 0);
                stepStatement.setString(9, timestamp);
                stepStatement.setString(10, timestamp);
                stepStatement.executeUpdate();
            }
        }
    }
}
