package com.buraktok.reportforge;

import com.buraktok.reportforge.export.HtmlReportExporter;
import com.buraktok.reportforge.model.ExecutionMetrics;
import com.buraktok.reportforge.model.ExecutionReportSnapshot;
import com.buraktok.reportforge.model.ExecutionRunEvidenceRecord;
import com.buraktok.reportforge.model.ExecutionRunRecord;
import com.buraktok.reportforge.model.ExecutionRunSnapshot;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ProjectSummary;
import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.ReportStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public final class ModulePathSmoke {
    private static final byte[] SAMPLE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9WlH0j8AAAAASUVORK5CYII="
    );

    private ModulePathSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path tempDirectory = Files.createTempDirectory("reportforge-module-smoke-");
        try {
            Path evidencePath = tempDirectory.resolve("evidence.png");
            Path exportPath = tempDirectory.resolve("module-smoke-export.html");
            Files.write(evidencePath, SAMPLE_PNG);

            ProjectSummary project = new ProjectSummary(
                    "project-1",
                    "Module Smoke Project",
                    "Smoke validation for module-path export.",
                    "2026-03-01T10:00:00Z",
                    "2026-03-05T10:00:00Z"
            );
            ReportRecord report = new ReportRecord(
                    "report-1",
                    "env-1",
                    "Module Smoke Report",
                    ReportStatus.REVIEW,
                    "2026-03-05T10:00:00Z",
                    "2026-03-06T08:30:00Z",
                    "EXECUTION_SUMMARY"
            );
            Map<String, String> fields = Map.of(
                    "projectOverview.projectName", "Module Smoke Project",
                    "notes.content", "Module smoke note"
            );
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
                    .notes("Run note")
                    .comments("Run comment")
                    .testSteps("Open portal\nVerify export")
                    .testCaseKey("TC-SMOKE-01")
                    .sectionName("Export")
                    .subsectionName("HTML")
                    .testCaseName("Module path export works")
                    .priority("High")
                    .moduleName("Exports")
                    .status("PASS")
                    .executionTime("15m")
                    .expectedResultSummary("Export succeeds.")
                    .actualResult("Export succeeded.")
                    .legacyOverallOutcome("NOT_EXECUTED")
                    .sortOrder(0)
                    .createdAt("2026-03-06T08:00:00Z")
                    .updatedAt("2026-03-06T08:10:00Z")
                    .build();
            ExecutionRunEvidenceRecord evidence = new ExecutionRunEvidenceRecord(
                    "evidence-1",
                    "run-1",
                    "evidence/execution-runs/run-1/evidence.png",
                    "smoke.png",
                    "image/png",
                    0,
                    "2026-03-06T08:00:00Z",
                    "2026-03-06T08:00:00Z"
            );
            ExecutionMetrics metrics = new ExecutionMetrics(1, 1, 1, 0, 0, 0, 0, 0, 0, 1, "PASS", "2026-03-06", "2026-03-06");
            ExecutionReportSnapshot snapshot = new ExecutionReportSnapshot(
                    "report-1",
                    List.of(new ExecutionRunSnapshot(run, List.of(evidence), metrics)),
                    metrics
            );

            HtmlReportExporter.writeReport(
                    exportPath,
                    project,
                    report,
                    fields,
                    List.of(),
                    environment,
                    snapshot,
                    ignored -> evidencePath
            );
            String html = Files.readString(exportPath);

            require(html.contains("Module Smoke Report"), "Report title was not rendered.");
            require(html.contains("Module Smoke Project"), "Project name was not rendered.");
            require(html.contains("data:image/"), "Evidence image was not embedded.");
            require(html.contains("Module smoke note"), "Report notes were not rendered.");
        } finally {
            deleteRecursively(tempDirectory);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var pathStream = Files.walk(root)) {
            pathStream.sorted((left, right) -> right.compareTo(left))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        }
    }
}
