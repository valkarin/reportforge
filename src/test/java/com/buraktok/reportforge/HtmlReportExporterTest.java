package com.buraktok.reportforge;

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

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlReportExporterTest {
    private static final byte[] SAMPLE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9WlH0j8AAAAASUVORK5CYII="
    );
    private static final Pattern WEBP_DATA_URI_PATTERN = Pattern.compile("data:image/webp;base64,([A-Za-z0-9+/=]+)");

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
        ExecutionRunRecord run = new ExecutionRunRecord(
                "run-1",
                "report-1",
                "1",
                "Smoke Cycle",
                "QA Engineer",
                "2026-03-06",
                "2026-03-06",
                "2026-03-06",
                "15m",
                "suite-smoke",
                "Run note content",
                "Run comment content",
                "Open portal\nLogin\nAttempt checkout",
                "TC-CHECKOUT-01",
                "Checkout",
                "Happy Path",
                "User can complete checkout",
                "High",
                "Payments",
                "FAIL",
                "15m",
                "Checkout completes successfully.",
                "Payment timeout banner displayed.",
                "BUG-17",
                "Investigate service timeout.",
                "",
                "Timeout defect tracked by payments team.",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "NOT_EXECUTED",
                0,
                "2026-03-06T08:00:00Z",
                "2026-03-06T08:10:00Z"
        );
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
        ExecutionRunRecord run = new ExecutionRunRecord(
                "run-1",
                "report-1",
                "1",
                "",
                "",
                "2026-03-06",
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
                "PASS",
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
                0,
                "2026-03-06T08:00:00Z",
                "2026-03-06T08:10:00Z"
        );
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
    void writeReportConvertsLargePngEvidenceToSmallerWebp() throws Exception {
        Path evidencePath = tempDir.resolve("evidence-large.png");
        writeLargeScreenshotLikePng(evidencePath);
        byte[] originalBytes = Files.readAllBytes(evidencePath);

        ProjectSummary project = new ProjectSummary("project-1", "Checkout Platform", "Main checkout project", "2026-03-01T10:00:00Z", "2026-03-05T10:00:00Z");
        ReportRecord report = new ReportRecord(
                "report-1",
                "env-1",
                "Compressed Evidence Export",
                ReportStatus.REVIEW,
                "2026-03-05T10:00:00Z",
                "2026-03-06T08:30:00Z",
                "EXECUTION_SUMMARY"
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
        ExecutionRunRecord run = new ExecutionRunRecord(
                "run-1",
                "report-1",
                "1",
                "Smoke Cycle",
                "QA Engineer",
                "2026-03-06",
                "2026-03-06",
                "2026-03-06",
                "15m",
                "suite-smoke",
                "",
                "",
                "",
                "TC-CHECKOUT-01",
                "Checkout",
                "Happy Path",
                "User can complete checkout",
                "High",
                "Payments",
                "PASS",
                "15m",
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
                0,
                "2026-03-06T08:00:00Z",
                "2026-03-06T08:10:00Z"
        );
        ExecutionRunEvidenceRecord evidence = new ExecutionRunEvidenceRecord(
                "evidence-1",
                "run-1",
                "evidence/execution-runs/run-1/evidence-large.png",
                "evidence-large.png",
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

        Path exportPath = tempDir.resolve("compressed-evidence.html");
        HtmlReportExporter.writeReport(
                exportPath,
                project,
                report,
                Map.of("projectOverview.projectName", "Checkout Platform"),
                List.of(),
                environment,
                executionSnapshot,
                ignored -> evidencePath
        );

        String html = Files.readString(exportPath);
        Matcher matcher = WEBP_DATA_URI_PATTERN.matcher(html);
        assertTrue(matcher.find());
        byte[] embeddedBytes = Base64.getDecoder().decode(matcher.group(1));

        assertAll(
                () -> assertTrue(html.contains("data:image/webp;base64,")),
                () -> assertTrue(embeddedBytes.length < originalBytes.length)
        );
    }

    private static void writeLargeScreenshotLikePng(Path evidencePath) throws Exception {
        BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(245, 247, 250));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());

            graphics.setColor(new Color(31, 41, 55));
            graphics.fillRect(0, 0, image.getWidth(), 72);

            graphics.setColor(new Color(59, 130, 246));
            graphics.fillRoundRect(40, 120, 320, 64, 16, 16);
            graphics.setColor(Color.WHITE);
            graphics.drawString("Checkout Summary", 64, 160);

            graphics.setColor(new Color(229, 231, 235));
            for (int index = 0; index < 8; index++) {
                graphics.fillRoundRect(40, 220 + (index * 54), 1200, 36, 12, 12);
            }

            graphics.setColor(new Color(16, 185, 129));
            graphics.fillRoundRect(920, 120, 180, 64, 16, 16);
            graphics.setColor(Color.WHITE);
            graphics.drawString("PASS", 990, 160);
        } finally {
            graphics.dispose();
        }
        assertTrue(ImageIO.write(image, "png", evidencePath.toFile()));
        assertNotNull(ImageIO.read(evidencePath.toFile()));
    }
}
