package com.buraktok.reportforge.export;

import com.buraktok.reportforge.model.ApplicationEntry;
import com.buraktok.reportforge.model.ExecutionMetrics;
import com.buraktok.reportforge.model.ExecutionReportSnapshot;
import com.buraktok.reportforge.model.ExecutionRunEvidenceRecord;
import com.buraktok.reportforge.model.ExecutionRunRecord;
import com.buraktok.reportforge.model.ExecutionRunSnapshot;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ProjectSummary;
import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.ReportStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlReportExporterTest {
    private static final byte[] SAMPLE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9WlH0j8AAAAASUVORK5CYII="
    );

    @TempDir
    Path tempDir;

    @Test
    void writeReportEmbedsEvidenceAndRunOwnedContent() throws Exception {
        Path evidencePath = tempDir.resolve("evidence.png");
        Files.write(evidencePath, SAMPLE_PNG);

        ProjectSummary project = new ProjectSummary("project-1", "Checkout Platform", "Main checkout project", "2026-03-01T10:00:00Z", "2026-03-05T10:00:00Z");
        ReportRecord report = new ReportRecord(
                "report-1",
                "env-1",
                "Release 2026.03 Validation",
                ReportStatus.REVIEW,
                "2026-03-05T10:00:00Z",
                "2026-03-06T08:30:00Z",
                "EXECUTION_SUMMARY"
        );
        Map<String, String> fields = Map.of(
                "projectOverview.projectName", "Checkout Platform",
                "projectOverview.projectDescription", "Release validation for checkout stability.",
                "projectOverview.releaseIterationName", "Sprint 24",
                "projectOverview.preparedBy", "QA Team",
                "projectOverview.preparedDate", "2026-03-06",
                "executionSummary.summaryNarrative", "Checkout is mostly stable with one blocking issue.",
                "notes.content", "Report-level note",
                "conclusion.overallConclusion", "Not ready for final release.",
                "conclusion.recommendations", "Resolve the blocking payment timeout before release."
        );
        List<ApplicationEntry> applications = List.of(new ApplicationEntry(
                "app-1",
                "Web Portal",
                "2026.03.7",
                "Checkout, Login",
                "Chrome",
                "Customer-facing application",
                "Payments API",
                true,
                0
        ));
        EnvironmentRecord environment = new EnvironmentRecord(
                "env-1",
                "QA",
                "Shared",
                "https://qa.example.test",
                "Windows 11",
                "Chrome 135",
                "2026.03.7",
                "Shared QA environment",
                0
        );
        ExecutionRunRecord run = ExecutionRunRecord.builder()
                .id("run-1")
                .reportId("report-1")
                .executionKey("1")
                .suiteName("Smoke Cycle")
                .executedBy("QA Engineer")
                .executionDate("2026-03-06")
                .startDate("2026-03-06")
                .endDate("2026-03-06")
                .durationText("15m")
                .dataSourceReference("suite-smoke")
                .notes("Run note content")
                .comments("Run comment content")
                .testSteps("Open portal\nLogin\nAttempt checkout")
                .testCaseKey("TC-CHECKOUT-01")
                .sectionName("Checkout")
                .subsectionName("Happy Path")
                .testCaseName("User can complete checkout")
                .priority("High")
                .moduleName("Payments")
                .status("FAIL")
                .executionTime("15m")
                .expectedResultSummary("Checkout completes successfully.")
                .actualResult("Payment timeout banner displayed.")
                .relatedIssue("BUG-17")
                .remarks("Investigate service timeout.")
                .defectSummary("Timeout defect tracked by payments team.")
                .legacyOverallOutcome("NOT_EXECUTED")
                .sortOrder(0)
                .createdAt("2026-03-06T08:00:00Z")
                .updatedAt("2026-03-06T08:10:00Z")
                .build();
        ExecutionRunEvidenceRecord evidence = new ExecutionRunEvidenceRecord(
                "evidence-1",
                "run-1",
                "evidence/execution-runs/run-1/evidence.png",
                "timeout.png",
                "image/png",
                0,
                "2026-03-06T08:00:00Z",
                "2026-03-06T08:00:00Z"
        );
        ExecutionMetrics metrics = new ExecutionMetrics(1, 1, 0, 1, 0, 0, 0, 0, 1, 1, "FAIL", "2026-03-06", "2026-03-06");
        ExecutionReportSnapshot executionSnapshot = new ExecutionReportSnapshot(
                "report-1",
                List.of(new ExecutionRunSnapshot(run, List.of(evidence), metrics)),
                metrics
        );

        Path exportPath = tempDir.resolve("release-validation.html");
        HtmlReportExporter.writeReport(
                exportPath,
                project,
                report,
                fields,
                applications,
                environment,
                executionSnapshot,
                ignored -> evidencePath
        );

        String html = Files.readString(exportPath);
        String rawHtml = HtmlReportExporter.renderUnminifiedReport(
                project,
                report,
                fields,
                applications,
                environment,
                executionSnapshot,
                ignored -> evidencePath
        );
        assertAll(
                () -> assertTrue(html.contains("Release 2026.03 Validation")),
                () -> assertTrue(html.contains("Checkout Platform")),
                () -> assertTrue(html.contains("Run comment content")),
                () -> assertTrue(html.contains("Run note content")),
                () -> assertTrue(html.contains("Attempt checkout")),
                () -> assertTrue(html.contains("Report-level note")),
                () -> assertTrue(html.contains("Overall Outcome")),
                () -> assertTrue(html.contains("data:image/")),
                () -> assertTrue(html.contains("class=line-number>1<td class=line-text>Open portal")),
                () -> assertTrue(html.contains("data-lightbox-trigger")),
                () -> assertTrue(html.contains("id=reportforge-lightbox")),
                () -> assertTrue(html.contains("data-lightbox-action=zoom-in")),
                () -> assertTrue(html.contains("Click to zoom")),
                () -> assertTrue(html.length() <= rawHtml.length() * 0.85),
                () -> assertFalse(html.contains("\n    const lightbox")),
                () -> assertFalse(html.contains("\n:root {"))
        );
    }

    @Test
    void writeReportRespectsExportPresetsForEmptyContentAndEvidenceEmbedding() throws Exception {
        Path evidencePath = tempDir.resolve("evidence.png");
        Files.write(evidencePath, SAMPLE_PNG);

        ProjectSummary project = new ProjectSummary("project-1", "Checkout Platform", "", "2026-03-01T10:00:00Z", "2026-03-05T10:00:00Z");
        ReportRecord report = new ReportRecord(
                "report-1",
                "env-1",
                "Lean Validation Export",
                ReportStatus.DRAFT,
                "2026-03-05T10:00:00Z",
                "2026-03-06T08:30:00Z",
                "EXECUTION_SUMMARY"
        );
        Map<String, String> fields = Map.of(
                "projectOverview.projectName", "Checkout Platform",
                "exportPresets.skipEmptyContent", "true",
                "exportPresets.includeEvidenceImages", "false"
        );
        EnvironmentRecord environment = new EnvironmentRecord(
                "env-1",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                0
        );
        ExecutionRunRecord run = ExecutionRunRecord.builder()
                .id("run-1")
                .reportId("report-1")
                .executionKey("1")
                .executionDate("2026-03-06")
                .status("PASS")
                .legacyOverallOutcome("NOT_EXECUTED")
                .sortOrder(0)
                .createdAt("2026-03-06T08:00:00Z")
                .updatedAt("2026-03-06T08:10:00Z")
                .build();
        ExecutionRunEvidenceRecord evidence = new ExecutionRunEvidenceRecord(
                "evidence-1",
                "run-1",
                "evidence/execution-runs/run-1/evidence.png",
                "timeout.png",
                "image/png",
                0,
                "2026-03-06T08:00:00Z",
                "2026-03-06T08:00:00Z"
        );
        ExecutionMetrics metrics = new ExecutionMetrics(1, 1, 1, 0, 0, 0, 0, 0, 0, 1, "PASS", "2026-03-06", "2026-03-06");
        ExecutionReportSnapshot executionSnapshot = new ExecutionReportSnapshot(
                "report-1",
                List.of(new ExecutionRunSnapshot(run, List.of(evidence), metrics)),
                metrics
        );

        Path exportPath = tempDir.resolve("lean-validation.html");
        HtmlReportExporter.writeReport(
                exportPath,
                project,
                report,
                fields,
                List.of(),
                environment,
                executionSnapshot,
                ignored -> evidencePath
        );

        String html = Files.readString(exportPath);
        assertAll(
                () -> assertTrue(html.contains("Lean Validation Export")),
                () -> assertFalse(html.contains("Applications under Test")),
                () -> assertFalse(html.contains("Build / Release Information")),
                () -> assertFalse(html.contains("Report Notes")),
                () -> assertFalse(html.contains("No content was provided for this section.")),
                () -> assertFalse(html.contains("data:image/")),
                () -> assertFalse(html.contains("<h4>Evidence</h4>")),
                () -> assertFalse(html.contains("id=\"reportforge-lightbox\"")),
                () -> assertFalse(html.contains("data-lightbox-action=\"zoom-in\""))
        );
    }

    @Test
    void writeReportSkipsMissingEvidenceFilesWithoutFailingExport() throws Exception {
        ProjectSummary project = new ProjectSummary("project-1", "Checkout Platform", "", "2026-03-01T10:00:00Z", "2026-03-05T10:00:00Z");
        ReportRecord report = new ReportRecord(
                "report-1",
                "env-1",
                "Missing Evidence Export",
                ReportStatus.DRAFT,
                "2026-03-05T10:00:00Z",
                "2026-03-06T08:30:00Z",
                "EXECUTION_SUMMARY"
        );
        EnvironmentRecord environment = new EnvironmentRecord(
                "env-1",
                "QA",
                "",
                "",
                "",
                "",
                "",
                "",
                0
        );
        ExecutionRunRecord run = ExecutionRunRecord.builder()
                .id("run-1")
                .reportId("report-1")
                .executionKey("1")
                .suiteName("Smoke")
                .executionDate("2026-03-06")
                .status("PASS")
                .legacyOverallOutcome("NOT_EXECUTED")
                .sortOrder(0)
                .createdAt("2026-03-06T08:00:00Z")
                .updatedAt("2026-03-06T08:10:00Z")
                .build();
        ExecutionRunEvidenceRecord evidence = new ExecutionRunEvidenceRecord(
                "evidence-1",
                "run-1",
                "evidence/execution-runs/run-1/missing.png",
                "missing.png",
                "image/png",
                0,
                "2026-03-06T08:00:00Z",
                "2026-03-06T08:00:00Z"
        );
        ExecutionMetrics metrics = new ExecutionMetrics(1, 1, 1, 0, 0, 0, 0, 0, 0, 1, "PASS", "2026-03-06", "2026-03-06");
        ExecutionReportSnapshot executionSnapshot = new ExecutionReportSnapshot(
                "report-1",
                List.of(new ExecutionRunSnapshot(run, List.of(evidence), metrics)),
                metrics
        );

        String html = HtmlReportExporter.renderUnminifiedReport(
                project,
                report,
                Map.of("projectOverview.projectName", "Checkout Platform"),
                List.of(),
                environment,
                executionSnapshot,
                ignored -> tempDir.resolve("does-not-exist.png")
        );

        assertAll(
                () -> assertTrue(html.contains("Missing Evidence Export")),
                () -> assertFalse(html.contains("data:image/"))
        );
    }

}
