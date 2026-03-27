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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlExportViewModelTest {
    private static final byte[] SAMPLE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9WlH0j8AAAAASUVORK5CYII="
    );

    @TempDir
    Path tempDir;

    @Test
    void buildViewModelCreatesTypedSectionsAndRunCards() throws Exception {
        Path evidencePath = tempDir.resolve("evidence.png");
        Files.write(evidencePath, SAMPLE_PNG);

        ProjectSummary project = new ProjectSummary("project-1", "Checkout Platform", "", "2026-03-01T10:00:00Z", "2026-03-05T10:00:00Z");
        ReportRecord report = new ReportRecord(
                "report-1",
                "env-1",
                "Release Validation",
                ReportStatus.REVIEW,
                "2026-03-05T10:00:00Z",
                "2026-03-06T08:30:00Z",
                "EXECUTION_SUMMARY"
        );
        Map<String, String> fields = Map.of(
                "projectOverview.projectName", "Checkout Platform",
                "projectOverview.preparedBy", "QA Team",
                "scope.objectiveSummary", "Validate checkout flow.",
                "executionSummary.summaryNarrative", "One known failure remains.",
                "conclusion.overallConclusion", "Not release ready."
        );
        List<ApplicationEntry> applications = List.of(new ApplicationEntry(
                "app-1",
                "Web Portal",
                "2026.03.7",
                "Checkout",
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
                .testSteps("Open portal\nLogin")
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

        HtmlExportViewModel viewModel = HtmlReportExporter.buildViewModel(
                project,
                report,
                fields,
                applications,
                environment,
                executionSnapshot,
                ignored -> evidencePath
        );

        HtmlExportViewModel.RunCard runCard = viewModel.executionRunsSection().runs().get(0);
        assertAll(
                () -> assertEquals("Release Validation - ReportForge Export", viewModel.pageTitleHtml()),
                () -> assertFalse(viewModel.heroSection().chips().isEmpty()),
                () -> assertEquals("QA Team", viewModel.heroSection().metaItems().get(0).valueHtml()),
                () -> assertTrue(viewModel.projectOverviewSection().showSection()),
                () -> assertFalse(viewModel.projectOverviewSection().fields().isEmpty()),
                () -> assertFalse(viewModel.applicationsSection().rows().isEmpty()),
                () -> assertFalse(viewModel.executionSummarySection().metricCards().isEmpty()),
                () -> assertTrue(viewModel.executionRunsSection().showSection()),
                () -> assertFalse(viewModel.executionRunsSection().showEmptyState()),
                () -> assertFalse(runCard.chips().isEmpty()),
                () -> assertTrue(runCard.showTestSteps()),
                () -> assertEquals("1", runCard.testSteps().get(0).number()),
                () -> assertTrue(runCard.showEvidenceSection()),
                () -> assertTrue(runCard.evidences().get(0).showImage()),
                () -> assertTrue(viewModel.showLightbox())
        );
    }

    @Test
    void buildViewModelUsesConsistentTextSectionVisibilityRules() {
        ProjectSummary project = new ProjectSummary("project-1", "Checkout Platform", "", "2026-03-01T10:00:00Z", "2026-03-05T10:00:00Z");
        ReportRecord report = new ReportRecord(
                "report-1",
                "env-1",
                "Release Validation",
                ReportStatus.DRAFT,
                "2026-03-05T10:00:00Z",
                "2026-03-06T08:30:00Z",
                "EXECUTION_SUMMARY"
        );
        EnvironmentRecord environment = new EnvironmentRecord("env-1", "QA", "", "", "", "", "", "", 0);
        ExecutionMetrics metrics = new ExecutionMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", "", "");
        ExecutionReportSnapshot executionSnapshot = new ExecutionReportSnapshot("report-1", List.of(), metrics);

        HtmlExportViewModel viewModel = HtmlReportExporter.buildViewModel(
                project,
                report,
                Map.of("exportPresets.skipEmptyContent", "true"),
                List.of(),
                environment,
                executionSnapshot,
                ignored -> tempDir.resolve("missing.png")
        );

        assertAll(
                () -> assertFalse(viewModel.buildReleaseInfoSection().showSection()),
                () -> assertFalse(viewModel.buildReleaseInfoSection().showBlock()),
                () -> assertFalse(viewModel.buildReleaseInfoSection().hasContent()),
                () -> assertEquals("", viewModel.buildReleaseInfoSection().html()),
                () -> assertFalse(viewModel.testCoverageSection().showSection()),
                () -> assertFalse(viewModel.riskAssessmentSection().showSection()),
                () -> assertFalse(viewModel.reportNotesSection().showSection()),
                () -> assertTrue(viewModel.executionSummarySection().narrativeSection().showSection()),
                () -> assertFalse(viewModel.executionSummarySection().narrativeSection().showBlock())
        );
    }
}
