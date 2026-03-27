package com.buraktok.reportforge.persistence;

import com.buraktok.reportforge.model.ApplicationEntry;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ExecutionMetrics;
import com.buraktok.reportforge.model.ExecutionReportSnapshot;
import com.buraktok.reportforge.model.ExecutionRunEvidenceRecord;
import com.buraktok.reportforge.model.ExecutionRunRecord;
import com.buraktok.reportforge.model.ExecutionRunSnapshot;
import com.buraktok.reportforge.model.ProjectWorkspace;
import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.ReportStatus;
import com.buraktok.reportforge.model.TestExecutionSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void saveAndReopenPreservesRunOwnedFieldsEvidenceAndReportNotes() throws Exception {
        Path projectFile = tempDir.resolve("execution-validation.rforge");
        service.createProject(projectFile, "Execution Validation", "QA", List.of("Portal"));
        ReportRecord report = createReport("Release Validation");
        service.updateReportField(report.getId(), "notes.content", "Release readiness note");
        service.updateReportField(report.getId(), "exportPresets.skipEmptyContent", "true");
        service.updateReportField(report.getId(), "exportPresets.includeEvidenceImages", "false");
        String today = LocalDate.now().toString();

        ExecutionRunSnapshot firstSnapshot = service.loadExecutionReportSnapshot(report.getId()).getRuns().getFirst();
        assertAll(
                () -> assertEquals("1", firstSnapshot.getRun().getExecutionKey()),
                () -> assertEquals(today, firstSnapshot.getRun().getStartDate())
        );
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
                "",
                "",
                ""
        );
        service.updateExecutionRun(passedRun);

        ExecutionRunRecord createdRun = service.createExecutionRun(report.getId());
        assertAll(
                () -> assertEquals("2", createdRun.getExecutionKey()),
                () -> assertEquals(today, createdRun.getStartDate())
        );
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
                "Observed intermittently in QA.",
                "Needs triage with the payments team.",
                "Open checkout page\nSubmit payment\nObserve timeout banner"
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
        Map<String, String> reportFields = service.loadReportFields(report.getId());

        assertAll(
                () -> assertEquals(2, reopened.getRuns().size()),
                () -> assertEquals(2, metrics.getExecutionRunCount()),
                () -> assertEquals(2, metrics.getTestCaseCount()),
                () -> assertEquals(1, metrics.getPassedCount()),
                () -> assertEquals(1, metrics.getFailedCount()),
                () -> assertEquals(1, metrics.getIssueCount()),
                () -> assertEquals(1, metrics.getEvidenceCount()),
                () -> assertEquals("MIXED", metrics.getOverallOutcome()),
                () -> assertEquals(today, metrics.getEarliestDate()),
                () -> assertEquals("2026-03-02", metrics.getLatestDate()),
                () -> assertEquals("FAIL", reopenedFailedRun.getRun().getStatus()),
                () -> assertEquals("Checkout fails on timeout", reopenedFailedRun.getRun().getTestCaseName()),
                () -> assertEquals("Payment service timeout.", reopenedFailedRun.getRun().getDefectSummary()),
                () -> assertEquals("Observed intermittently in QA.", reopenedFailedRun.getRun().getNotes()),
                () -> assertEquals("Needs triage with the payments team.", reopenedFailedRun.getRun().getComments()),
                () -> assertEquals("Open checkout page\nSubmit payment\nObserve timeout banner", reopenedFailedRun.getRun().getTestSteps()),
                () -> assertEquals(1, reopenedFailedRun.getEvidences().size()),
                () -> assertTrue(evidence.getStoredPath().startsWith("evidence/")),
                () -> assertTrue(Files.exists(service.resolveProjectPath(evidence.getStoredPath()))),
                () -> assertEquals("Release readiness note", reportFields.get("notes.content")),
                () -> assertEquals("true", reportFields.get("exportPresets.skipEmptyContent")),
                () -> assertEquals("false", reportFields.get("exportPresets.includeEvidenceImages"))
        );
    }

    @Test
    void saveAndReopenPreservesUpdatedReportMetadata() throws Exception {
        Path projectFile = tempDir.resolve("report-metadata-validation.rforge");
        service.createProject(projectFile, "Report Metadata Validation", "QA", List.of("Portal"));
        ReportRecord report = createReport("Initial Report Title");

        service.updateReportTitle(report.getId(), "  Release Signoff  ");
        service.updateReportStatus(report.getId(), ReportStatus.FINAL);
        service.updateLastSelectedSection(report.getId(), TestExecutionSection.EXPORT_PRESETS);

        service.saveProject();
        service.closeCurrentSession();
        service.openProject(projectFile);

        ProjectWorkspace reopenedWorkspace = service.loadWorkspace();
        ReportRecord reopenedReport = reopenedWorkspace.getReportsForEnvironment(report.getEnvironmentId()).stream()
                .filter(candidate -> report.getId().equals(candidate.getId()))
                .findFirst()
                .orElseThrow();

        assertAll(
                () -> assertEquals("Release Signoff", reopenedReport.getTitle()),
                () -> assertEquals(ReportStatus.FINAL, reopenedReport.getStatus()),
                () -> assertEquals(TestExecutionSection.EXPORT_PRESETS.name(), reopenedReport.getLastSelectedSection())
        );
    }

    @Test
    void saveAndReopenPreservesProjectAndReportApplicationEdits() throws Exception {
        Path projectFile = tempDir.resolve("application-edit-validation.rforge");
        service.createProject(projectFile, "Application Edit Validation", "QA", List.of("Portal"));
        ReportRecord report = createReport("Application Edit Report");

        ApplicationEntry projectApplication = new ApplicationEntry(
                "project-admin",
                "Admin Console",
                "1.2.3",
                "Users, Roles",
                "Web",
                "Administrative console",
                "Auth Service",
                true,
                -1
        );
        service.upsertProjectApplication(projectApplication);

        ApplicationEntry reportApplication = new ApplicationEntry(
                "report-payments-api",
                "Payments API",
                "2026.03",
                "Checkout",
                "Service",
                "Report-specific dependency",
                "Gateway",
                true,
                -1
        );
        service.upsertReportApplication(report.getId(), reportApplication);

        service.saveProject();
        service.closeCurrentSession();
        service.openProject(projectFile);

        ProjectWorkspace reopenedWorkspace = service.loadWorkspace();
        List<ApplicationEntry> projectApplications = reopenedWorkspace.getProjectApplications();
        ApplicationEntry reopenedProjectApplication = projectApplications.stream()
                .filter(application -> projectApplication.getId().equals(application.getId()))
                .findFirst()
                .orElseThrow();
        List<ApplicationEntry> reportApplications = service.loadReportApplications(report.getId());
        ApplicationEntry reopenedReportApplication = reportApplications.stream()
                .filter(application -> reportApplication.getId().equals(application.getId()))
                .findFirst()
                .orElseThrow();

        assertAll(
                () -> assertEquals(2, projectApplications.size()),
                () -> assertTrue(projectApplications.stream().anyMatch(application -> "Portal".equals(application.getName()))),
                () -> assertEquals(1L, projectApplications.stream().filter(ApplicationEntry::isPrimary).count()),
                () -> assertTrue(reopenedProjectApplication.isPrimary()),
                () -> assertEquals("1.2.3", reopenedProjectApplication.getVersionOrBuild()),
                () -> assertEquals("Auth Service", reopenedProjectApplication.getRelatedServices()),
                () -> assertEquals(2, reportApplications.size()),
                () -> assertEquals(1L, reportApplications.stream().filter(ApplicationEntry::isPrimary).count()),
                () -> assertTrue(reopenedReportApplication.isPrimary()),
                () -> assertEquals("Checkout", reopenedReportApplication.getModuleList()),
                () -> assertEquals("Gateway", reopenedReportApplication.getRelatedServices())
        );
    }

    @Test
    void saveAndReopenPromotesRemainingApplicationsAfterPrimaryDeletion() throws Exception {
        Path projectFile = tempDir.resolve("application-delete-validation.rforge");
        service.createProject(projectFile, "Application Delete Validation", "QA", List.of("Portal"));
        ReportRecord report = createReport("Application Delete Report");

        ApplicationEntry projectApplication = new ApplicationEntry(
                "project-admin-delete",
                "Admin Console",
                "",
                "",
                "Web",
                "",
                "",
                true,
                -1
        );
        service.upsertProjectApplication(projectApplication);
        service.deleteProjectApplication(projectApplication.getId());

        ApplicationEntry reportApplication = new ApplicationEntry(
                "report-api-delete",
                "Payments API",
                "",
                "",
                "Service",
                "",
                "",
                true,
                -1
        );
        service.upsertReportApplication(report.getId(), reportApplication);
        service.deleteReportApplication(reportApplication.getId(), report.getId());

        service.saveProject();
        service.closeCurrentSession();
        service.openProject(projectFile);

        List<ApplicationEntry> projectApplications = service.loadWorkspace().getProjectApplications();
        List<ApplicationEntry> reportApplications = service.loadReportApplications(report.getId());

        assertAll(
                () -> assertEquals(1, projectApplications.size()),
                () -> assertEquals("Portal", projectApplications.getFirst().getName()),
                () -> assertTrue(projectApplications.getFirst().isPrimary()),
                () -> assertEquals(1, reportApplications.size()),
                () -> assertEquals("Portal", reportApplications.getFirst().getName()),
                () -> assertTrue(reportApplications.getFirst().isPrimary())
        );
    }

    @Test
    void copyReportToEnvironmentPreservesRunCommentsStepsAndReportNotes() throws Exception {
        service.createProject(tempDir.resolve("copy-validation.rforge"), "Copy Validation", "QA", List.of("Portal"));
        ReportRecord report = createReport("Copy Validation Report");
        service.updateReportField(report.getId(), "notes.content", "Copied report note");
        service.updateReportField(report.getId(), "exportPresets.skipEmptyContent", "true");
        service.updateReportField(report.getId(), "exportPresets.includeEvidenceImages", "false");

        ExecutionRunSnapshot initialSnapshot = service.loadExecutionReportSnapshot(report.getId()).getRuns().getFirst();
        ExecutionRunRecord updatedRun = copyRun(
                initialSnapshot.getRun(),
                "Copy Cycle",
                "QA Engineer",
                "2026-03-03",
                "TC-COPY-01",
                "Copied run keeps text fields",
                "BLOCKED",
                "Blocked by external dependency.",
                "BUG-77",
                "Waiting on an upstream fix.",
                "Run note copied with the report.",
                "Run comment copied with the report.",
                "Open workflow\nTrigger dependency\nVerify blocker"
        );
        service.updateExecutionRun(updatedRun);

        EnvironmentRecord targetEnvironment = service.createEnvironment("Staging");
        ReportRecord copiedReport = service.copyReportToEnvironment(report.getId(), targetEnvironment.getId());

        ExecutionRunRecord copiedRun = service.loadExecutionReportSnapshot(copiedReport.getId()).getRuns().getFirst().getRun();
        Map<String, String> copiedFields = service.loadReportFields(copiedReport.getId());

        assertAll(
                () -> assertEquals("Run note copied with the report.", copiedRun.getNotes()),
                () -> assertEquals("Run comment copied with the report.", copiedRun.getComments()),
                () -> assertEquals("Open workflow\nTrigger dependency\nVerify blocker", copiedRun.getTestSteps()),
                () -> assertEquals("BLOCKED", copiedRun.getStatus()),
                () -> assertEquals("Copied report note", copiedFields.get("notes.content")),
                () -> assertEquals("true", copiedFields.get("exportPresets.skipEmptyContent")),
                () -> assertEquals("false", copiedFields.get("exportPresets.includeEvidenceImages"))
        );
    }

    @Test
    void copyReportToEnvironmentRollsBackWhenEvidenceCopyFails() throws Exception {
        service.createProject(tempDir.resolve("copy-rollback-validation.rforge"), "Copy Validation", "QA", List.of("Portal"));
        ReportRecord report = createReport("Copy Rollback Report");

        ExecutionRunSnapshot initialSnapshot = service.loadExecutionReportSnapshot(report.getId()).getRuns().getFirst();
        service.addExecutionRunEvidence(report.getId(), initialSnapshot.getRun().getId(), "failure.png", "image/png", SAMPLE_PNG);
        corruptEvidencePath(initialSnapshot.getRun().getId(), "..\\..\\outside.png");

        EnvironmentRecord targetEnvironment = service.createEnvironment("Staging");

        assertThrows(IllegalArgumentException.class, () -> service.copyReportToEnvironment(report.getId(), targetEnvironment.getId()));

        ProjectWorkspace workspace = service.loadWorkspace();
        assertAll(
                () -> assertEquals(1, workspace.getReportsForEnvironment(report.getEnvironmentId()).size()),
                () -> assertTrue(workspace.getReportsForEnvironment(targetEnvironment.getId()).isEmpty())
        );
    }

    @Test
    void deleteExecutionRunEvidenceRollsBackWhenFileCleanupFails() throws Exception {
        service.createProject(tempDir.resolve("evidence-delete-rollback.rforge"), "Evidence Delete Rollback", "QA", List.of("Portal"));
        ReportRecord report = createReport("Evidence Delete Rollback Report");

        ExecutionRunSnapshot initialSnapshot = service.loadExecutionReportSnapshot(report.getId()).getRuns().getFirst();
        ExecutionRunEvidenceRecord evidence = service.addExecutionRunEvidence(
                report.getId(),
                initialSnapshot.getRun().getId(),
                "failure.png",
                "image/png",
                SAMPLE_PNG
        );
        corruptEvidencePath(initialSnapshot.getRun().getId(), "..\\..\\outside.png");

        assertThrows(IllegalArgumentException.class, () -> service.deleteExecutionRunEvidence(report.getId(), evidence.getId()));

        ExecutionRunSnapshot snapshotAfterFailure = service.loadExecutionReportSnapshot(report.getId()).getRuns().getFirst();
        assertAll(
                () -> assertEquals(1, snapshotAfterFailure.getEvidences().size()),
                () -> assertEquals(evidence.getId(), snapshotAfterFailure.getEvidences().getFirst().getId()),
                () -> assertEquals("..\\..\\outside.png", snapshotAfterFailure.getEvidences().getFirst().getStoredPath())
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
            String notes,
            String comments,
            String testSteps
    ) {
        return original.toBuilder()
                .suiteName(suiteName)
                .executedBy(executedBy)
                .executionDate(executionDate)
                .notes(notes)
                .comments(comments)
                .testSteps(testSteps)
                .testCaseKey(testCaseKey)
                .testCaseName(testCaseName)
                .status(status)
                .actualResult(actualResult)
                .relatedIssue(relatedIssue)
                .defectSummary(defectSummary)
                .build();
    }

    private void corruptEvidencePath(String executionRunId, String storedPath) throws Exception {
        ProjectSession session = service.getCurrentSession();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + session.databasePath());
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE report_execution_run_evidence SET stored_path = ? WHERE execution_run_id = ?")) {
            statement.setString(1, storedPath);
            statement.setString(2, executionRunId);
            statement.executeUpdate();
        }
    }

}
