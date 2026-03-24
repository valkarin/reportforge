package com.buraktok.reportforge;

import com.luciad.imageio.webp.WebPWriteParam;
import com.buraktok.reportforge.model.ApplicationEntry;
import com.buraktok.reportforge.model.ExecutionMetrics;
import com.buraktok.reportforge.model.ExecutionReportSnapshot;
import com.buraktok.reportforge.model.ExecutionRunEvidenceRecord;
import com.buraktok.reportforge.model.ExecutionRunRecord;
import com.buraktok.reportforge.model.ExecutionRunSnapshot;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ProjectSummary;
import com.buraktok.reportforge.model.ReportRecord;
import in.wilsonl.minifyhtml.Configuration;
import in.wilsonl.minifyhtml.MinifyHtml;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.loader.Loader;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

public final class HtmlReportExporter {
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault());
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.getDefault()).withZone(ZoneId.systemDefault());
    private static final String HTML_EXPORT_CSS = loadResourceText("html-export.css");
    private static final String HTML_EXPORT_JS = loadResourceText("html-export.js");
    private static final String HTML_EXPORT_TEMPLATE = "html-export";
    private static final String WEBP_MIME_TYPE = "image/webp";
    private static final PebbleEngine TEMPLATE_ENGINE = createTemplateEngine();
    private static final Configuration HTML_MINIFY_CONFIGURATION = new Configuration.Builder()
            .setMinifyCss(true)
            .setMinifyJs(true)
            .build();

    private HtmlReportExporter() {
    }

    public static void writeReport(
            Path outputPath,
            ProjectSummary project,
            ReportRecord report,
            Map<String, String> fields,
            List<ApplicationEntry> applications,
            EnvironmentRecord environment,
            ExecutionReportSnapshot executionSnapshot,
            Function<String, Path> evidenceResolver
    ) throws IOException {
        Files.writeString(
                outputPath,
                renderReport(project, report, fields, applications, environment, executionSnapshot, evidenceResolver),
                StandardCharsets.UTF_8
        );
    }

    static String renderReport(
            ProjectSummary project,
            ReportRecord report,
            Map<String, String> fields,
            List<ApplicationEntry> applications,
            EnvironmentRecord environment,
            ExecutionReportSnapshot executionSnapshot,
            Function<String, Path> evidenceResolver
    ) {
        return minifyHtml(renderReportMarkup(project, report, fields, applications, environment, executionSnapshot, evidenceResolver));
    }

    static String renderUnminifiedReport(
            ProjectSummary project,
            ReportRecord report,
            Map<String, String> fields,
            List<ApplicationEntry> applications,
            EnvironmentRecord environment,
            ExecutionReportSnapshot executionSnapshot,
            Function<String, Path> evidenceResolver
    ) {
        return renderReportMarkup(project, report, fields, applications, environment, executionSnapshot, evidenceResolver);
    }

    private static String renderReportMarkup(
            ProjectSummary project,
            ReportRecord report,
            Map<String, String> fields,
            List<ApplicationEntry> applications,
            EnvironmentRecord environment,
            ExecutionReportSnapshot executionSnapshot,
            Function<String, Path> evidenceResolver
    ) {
        String projectName = firstNonBlank(fields.get("projectOverview.projectName"), project.getName());
        String reportType = firstNonBlank(fields.get("projectOverview.reportType"), "Test Execution Report");
        String projectDescription = firstNonBlank(fields.get("projectOverview.projectDescription"), project.getDescription());
        String passRate = formatPassRate(executionSnapshot.getMetrics());
        boolean skipEmptyContent = presetEnabled(fields, "exportPresets.skipEmptyContent", false);
        boolean includeEvidenceImages = presetEnabled(fields, "exportPresets.includeEvidenceImages", true);
        String generatedAt = formatTimestamp(Instant.now().toString());

        LinkedHashMap<String, Object> model = new LinkedHashMap<>();
        model.put("pageTitleHtml", escapeHtml(report.getTitle()) + " - ReportForge Export");
        model.put("styles", HTML_EXPORT_CSS);
        model.put("script", HTML_EXPORT_JS);
        model.put("reportTitleHtml", escapeHtml(report.getTitle()));
        model.put("heroSubtitleHtml", escapeHtml(reportType) + " for " + escapeHtml(projectName));
        model.put("generatedAtHtml", escapeHtml(generatedAt));
        model.put("heroChips", buildHeroChips(projectName, environment, report, generatedAt));

        LinkedHashMap<String, String> heroFields = new LinkedHashMap<>();
        heroFields.put("Prepared By", fields.get("projectOverview.preparedBy"));
        heroFields.put("Prepared Date", formatDate(fields.get("projectOverview.preparedDate")));
        heroFields.put("Release / Iteration", fields.get("projectOverview.releaseIterationName"));
        heroFields.put("Stakeholder Audience", fields.get("projectOverview.stakeholderAudience"));
        heroFields.put("Revision Note", fields.get("projectOverview.revisionNote"));
        heroFields.put("Report Updated", formatTimestamp(report.getUpdatedAt()));
        List<Map<String, Object>> heroMetaItems = buildNonBlankDisplayItems(heroFields);
        model.put("heroMetaItems", heroMetaItems);
        model.put("showHeroMetaEmptyState", heroMetaItems.isEmpty());
        model.put("heroHighlights", buildHeroHighlights(executionSnapshot.getMetrics(), passRate));

        LinkedHashMap<String, String> overviewFields = new LinkedHashMap<>();
        overviewFields.put("Project Name", projectName);
        overviewFields.put("Report Title", report.getTitle());
        overviewFields.put("Report Type", reportType);
        overviewFields.put("Environment", environment.getName());
        overviewFields.put("Status", report.getStatus().getDisplayName());
        LinkedHashMap<String, String> filteredOverviewFields = filterFields(overviewFields, skipEmptyContent);
        List<Map<String, Object>> projectOverviewItems = buildFieldItems(filteredOverviewFields);
        boolean showProjectOverviewSection = !skipEmptyContent || hasNonBlankValues(filteredOverviewFields) || !isBlank(projectDescription);
        boolean showProjectDescriptionBlock = !skipEmptyContent || !isBlank(projectDescription);
        model.put("showProjectOverviewSection", showProjectOverviewSection);
        model.put("showProjectOverviewDataGrid", !projectOverviewItems.isEmpty());
        model.put("projectOverviewFields", projectOverviewItems);
        model.put("showProjectDescriptionBlock", showProjectDescriptionBlock);
        model.put("projectDescriptionHasContent", !isBlank(projectDescription));
        model.put("projectDescriptionHtml", isBlank(projectDescription) ? "" : formatMultiline(projectDescription));

        boolean showApplicationsSection = !skipEmptyContent || !applications.isEmpty();
        model.put("showApplicationsSection", showApplicationsSection);
        model.put("showApplicationsEmptyState", applications.isEmpty());
        model.put("applications", buildApplicationRows(applications));

        LinkedHashMap<String, String> environmentFields = new LinkedHashMap<>();
        environmentFields.put("Environment Name", environment.getName());
        environmentFields.put("Environment Type", environment.getType());
        environmentFields.put("Base URL / Endpoint", environment.getBaseUrl());
        environmentFields.put("OS / Platform", environment.getOsPlatform());
        environmentFields.put("Browser / Client", environment.getBrowserClient());
        environmentFields.put("Backend Version", environment.getBackendVersion());
        environmentFields.put("Environment Notes", environment.getNotes());
        LinkedHashMap<String, String> filteredEnvironmentFields = filterFields(environmentFields, skipEmptyContent);
        List<Map<String, Object>> environmentItems = buildFieldItems(filteredEnvironmentFields);
        model.put("showEnvironmentSection", !skipEmptyContent || hasNonBlankValues(filteredEnvironmentFields));
        model.put("showEnvironmentDataGrid", !environmentItems.isEmpty());
        model.put("environmentFields", environmentItems);

        LinkedHashMap<String, String> scopeFields = new LinkedHashMap<>();
        scopeFields.put("Test Objective Summary", fields.get("scope.objectiveSummary"));
        scopeFields.put("In-Scope Items", fields.get("scope.inScopeItems"));
        scopeFields.put("Out-of-Scope Items", fields.get("scope.outOfScopeItems"));
        scopeFields.put("Test Strategy Note", fields.get("scope.testStrategyNote"));
        scopeFields.put("Assumptions", fields.get("scope.assumptions"));
        scopeFields.put("Dependencies", fields.get("scope.dependencies"));
        scopeFields.put("Entry Criteria", fields.get("scope.entryCriteria"));
        scopeFields.put("Exit Criteria", fields.get("scope.exitCriteria"));
        LinkedHashMap<String, String> filteredScopeFields = filterFields(scopeFields, skipEmptyContent);
        List<Map<String, Object>> scopePanels = buildTextPanels(filteredScopeFields);
        model.put("showScopeSection", !skipEmptyContent || hasNonBlankValues(filteredScopeFields));
        model.put("showScopeEmptyState", scopePanels.isEmpty());
        model.put("scopePanels", scopePanels);

        String buildReleaseInfo = fields.get("buildReleaseInfo.content");
        model.put("showBuildReleaseInfoSection", !skipEmptyContent || !isBlank(buildReleaseInfo));
        model.put("showBuildReleaseInfoBlock", !skipEmptyContent || !isBlank(buildReleaseInfo));
        model.put("buildReleaseInfoHasContent", !isBlank(buildReleaseInfo));
        model.put("buildReleaseInfoHtml", isBlank(buildReleaseInfo) ? "" : formatMultiline(buildReleaseInfo));

        model.put("metricCards", buildMetricCards(executionSnapshot.getMetrics(), passRate));
        String executionNarrative = fields.get("executionSummary.summaryNarrative");
        model.put("showExecutionNarrativeBlock", !skipEmptyContent || !isBlank(executionNarrative));
        model.put("executionNarrativeHasContent", !isBlank(executionNarrative));
        model.put("executionNarrativeHtml", isBlank(executionNarrative) ? "" : formatMultiline(executionNarrative));

        List<Map<String, Object>> runs = buildRunCards(executionSnapshot.getRuns(), evidenceResolver, skipEmptyContent, includeEvidenceImages);
        model.put("showExecutionRunsSection", !skipEmptyContent || !executionSnapshot.getRuns().isEmpty());
        model.put("showExecutionRunsEmptyState", runs.isEmpty());
        model.put("runs", runs);

        String testCoverage = fields.get("testCoverage.content");
        model.put("showTestCoverageSection", !skipEmptyContent || !isBlank(testCoverage));
        model.put("showTestCoverageBlock", !skipEmptyContent || !isBlank(testCoverage));
        model.put("testCoverageHasContent", !isBlank(testCoverage));
        model.put("testCoverageHtml", isBlank(testCoverage) ? "" : formatMultiline(testCoverage));

        String riskAssessment = fields.get("riskAssessment.content");
        model.put("showRiskAssessmentSection", !skipEmptyContent || !isBlank(riskAssessment));
        model.put("showRiskAssessmentBlock", !skipEmptyContent || !isBlank(riskAssessment));
        model.put("riskAssessmentHasContent", !isBlank(riskAssessment));
        model.put("riskAssessmentHtml", isBlank(riskAssessment) ? "" : formatMultiline(riskAssessment));

        String reportNotes = fields.get("notes.content");
        model.put("showReportNotesSection", !skipEmptyContent || !isBlank(reportNotes));
        model.put("showReportNotesBlock", !skipEmptyContent || !isBlank(reportNotes));
        model.put("reportNotesHasContent", !isBlank(reportNotes));
        model.put("reportNotesHtml", isBlank(reportNotes) ? "" : formatMultiline(reportNotes));

        LinkedHashMap<String, String> conclusionFields = new LinkedHashMap<>();
        conclusionFields.put("Overall Conclusion", fields.get("conclusion.overallConclusion"));
        conclusionFields.put("Recommendations", fields.get("conclusion.recommendations"));
        conclusionFields.put("Known Limitations", fields.get("conclusion.knownLimitations"));
        conclusionFields.put("Follow-up Actions", fields.get("conclusion.followUpActions"));
        conclusionFields.put("Open Concerns", fields.get("conclusion.openConcerns"));
        LinkedHashMap<String, String> filteredConclusionFields = filterFields(conclusionFields, skipEmptyContent);
        List<Map<String, Object>> conclusionPanels = buildTextPanels(filteredConclusionFields);
        model.put("showConclusionSection", !skipEmptyContent || hasNonBlankValues(filteredConclusionFields));
        model.put("showConclusionEmptyState", conclusionPanels.isEmpty());
        model.put("conclusionPanels", conclusionPanels);

        model.put("showLightbox", includeEvidenceImages);
        model.put("footerGeneratedAtHtml", escapeHtml(generatedAt));
        return renderTemplate(model);
    }

    private static List<Map<String, Object>> buildHeroChips(
            String projectName,
            EnvironmentRecord environment,
            ReportRecord report,
            String generatedAt
    ) {
        List<Map<String, Object>> chips = new ArrayList<>();
        chips.add(buildChip("Project", projectName, false, null));
        chips.add(buildChip("Environment", environment.getName(), false, null));
        chips.add(buildChip("Generated", generatedAt, false, null));
        chips.add(buildChip("Report Status", report.getStatus().getDisplayName(), false, null));
        return chips;
    }

    private static List<Map<String, Object>> buildHeroHighlights(ExecutionMetrics metrics, String passRate) {
        List<Map<String, Object>> highlights = new ArrayList<>();
        highlights.add(buildHighlightCard("Overall Outcome", normalizeStatus(metrics.getOverallOutcome()), toneForStatus(metrics.getOverallOutcome())));
        highlights.add(buildHighlightCard("Pass Rate", passRate, "accent"));
        highlights.add(buildHighlightCard("Execution Runs", Integer.toString(metrics.getExecutionRunCount()), "neutral"));
        highlights.add(buildHighlightCard("Linked Issues", Integer.toString(metrics.getIssueCount()), metrics.getIssueCount() > 0 ? "danger" : "success"));
        return highlights;
    }

    private static List<Map<String, Object>> buildMetricCards(ExecutionMetrics metrics, String passRate) {
        List<Map<String, Object>> cards = new ArrayList<>();
        cards.add(buildMetricCard("Overall Outcome", normalizeStatus(metrics.getOverallOutcome()), toneForStatus(metrics.getOverallOutcome())));
        cards.add(buildMetricCard("Pass Rate", passRate, "accent"));
        cards.add(buildMetricCard("Execution Runs", Integer.toString(metrics.getExecutionRunCount()), "neutral"));
        cards.add(buildMetricCard("Total Test Cases", Integer.toString(metrics.getTestCaseCount()), "neutral"));
        cards.add(buildMetricCard("Passed", Integer.toString(metrics.getPassedCount()), toneForCount(metrics.getPassedCount(), "success")));
        cards.add(buildMetricCard("Failed", Integer.toString(metrics.getFailedCount()), toneForCount(metrics.getFailedCount(), "danger")));
        cards.add(buildMetricCard("Blocked", Integer.toString(metrics.getBlockedCount()), toneForCount(metrics.getBlockedCount(), "warning")));
        cards.add(buildMetricCard("Not Run", Integer.toString(metrics.getNotRunCount()), toneForCount(metrics.getNotRunCount(), "neutral")));
        cards.add(buildMetricCard("Deferred", Integer.toString(metrics.getDeferredCount()), toneForCount(metrics.getDeferredCount(), "warning")));
        cards.add(buildMetricCard("Skipped", Integer.toString(metrics.getSkippedCount()), toneForCount(metrics.getSkippedCount(), "neutral")));
        cards.add(buildMetricCard("Linked Issues", Integer.toString(metrics.getIssueCount()), metrics.getIssueCount() > 0 ? "danger" : "success"));
        cards.add(buildMetricCard("Evidence", Integer.toString(metrics.getEvidenceCount()), metrics.getEvidenceCount() > 0 ? "accent" : "neutral"));
        return cards;
    }

    private static List<Map<String, Object>> buildRunCards(
            List<ExecutionRunSnapshot> runs,
            Function<String, Path> evidenceResolver,
            boolean skipEmptyContent,
            boolean includeEvidenceImages
    ) {
        List<Map<String, Object>> runCards = new ArrayList<>();
        for (ExecutionRunSnapshot runSnapshot : runs) {
            ExecutionRunRecord run = runSnapshot.getRun();
            String runStatusClass = statusClass(run.getStatus());

            LinkedHashMap<String, String> runFields = new LinkedHashMap<>();
            runFields.put("Suite / Cycle Name", run.getSuiteName());
            runFields.put("Executed By", run.getExecutedBy());
            runFields.put("Execution Date", formatDate(run.getExecutionDate()));
            runFields.put("Execution Start Date", formatDate(run.getStartDate()));
            runFields.put("Execution End Date", formatDate(run.getEndDate()));
            runFields.put("Duration", run.getDurationText());
            runFields.put("Data Source / Reference", run.getDataSourceReference());
            runFields.put("Test Case ID", run.getTestCaseKey());
            runFields.put("Section", run.getSectionName());
            runFields.put("Subsection", run.getSubsectionName());
            runFields.put("Test Case Name", run.getTestCaseName());
            runFields.put("Priority", run.getPriority());
            runFields.put("Module / Component", run.getModuleName());
            runFields.put("Execution Time", run.getExecutionTime());
            runFields.put("Related Issue", run.getRelatedIssue());
            LinkedHashMap<String, String> filteredRunFields = filterFields(runFields, skipEmptyContent);
            List<Map<String, Object>> runFieldItems = buildFieldItems(filteredRunFields);

            LinkedHashMap<String, String> runTextFields = new LinkedHashMap<>();
            runTextFields.put("Comments", run.getComments());
            runTextFields.put("Notes", run.getNotes());
            runTextFields.put("Expected Result Summary", run.getExpectedResultSummary());
            runTextFields.put("Actual Result", run.getActualResult());
            runTextFields.put("Blocked Reason", run.getBlockedReason());
            runTextFields.put("Remarks", run.getRemarks());
            runTextFields.put("Defect Summary", run.getDefectSummary());
            LinkedHashMap<String, String> filteredRunTextFields = filterFields(runTextFields, skipEmptyContent);
            List<Map<String, Object>> runTextPanels = buildTextPanels(filteredRunTextFields);

            List<Map<String, Object>> testSteps = buildLineItems(run.getTestSteps());
            List<Map<String, Object>> evidenceItems = includeEvidenceImages
                    ? buildEvidenceItems(runSnapshot.getEvidences(), evidenceResolver)
                    : List.of();

            LinkedHashMap<String, Object> runCard = new LinkedHashMap<>();
            runCard.put("articleClass", "run-card run-card-" + escapeHtml(runStatusClass));
            runCard.put("displayLabelHtml", escapeHtml(run.getDisplayLabel()));
            runCard.put("chips", buildRunChips(run, runStatusClass));
            runCard.put("showDataFields", !runFieldItems.isEmpty());
            runCard.put("dataFields", runFieldItems);
            runCard.put("showTextPanels", !runTextPanels.isEmpty());
            runCard.put("textPanels", runTextPanels);
            runCard.put("showTestSteps", !testSteps.isEmpty());
            runCard.put("testSteps", testSteps);
            runCard.put("showEvidenceSection", includeEvidenceImages && !evidenceItems.isEmpty());
            runCard.put("evidences", evidenceItems);
            runCards.add(runCard);
        }
        return runCards;
    }

    private static List<Map<String, Object>> buildRunChips(ExecutionRunRecord run, String runStatusClass) {
        List<Map<String, Object>> chips = new ArrayList<>();
        chips.add(buildChip("Status", normalizeStatus(run.getStatus()), true, runStatusClass));
        chips.add(buildChip("Execution ID", run.getExecutionKey(), false, null));
        chips.add(buildChip("Execution Date", formatDate(firstNonBlank(run.getExecutionDate(), run.getStartDate(), run.getEndDate())), false, null));
        return chips;
    }

    private static List<Map<String, Object>> buildFieldItems(Map<String, String> fields) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            items.add(buildDisplayItem(entry.getKey(), formatMultiline(valueOrDash(entry.getValue()))));
        }
        return items;
    }

    private static List<Map<String, Object>> buildNonBlankDisplayItems(Map<String, String> fields) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (isBlank(entry.getValue())) {
                continue;
            }
            items.add(buildDisplayItem(entry.getKey(), formatMultiline(entry.getValue())));
        }
        return items;
    }

    private static List<Map<String, Object>> buildTextPanels(Map<String, String> fields) {
        List<Map<String, Object>> panels = new ArrayList<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (isBlank(entry.getValue())) {
                continue;
            }
            LinkedHashMap<String, Object> panel = new LinkedHashMap<>();
            panel.put("labelHtml", escapeHtml(entry.getKey()));
            panel.put("valueHtml", formatMultiline(entry.getValue()));
            panels.add(panel);
        }
        return panels;
    }

    private static List<Map<String, Object>> buildApplicationRows(List<ApplicationEntry> applications) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ApplicationEntry application : applications) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("nameHtml", escapeHtml(valueOrDash(application.getName())));
            row.put("versionHtml", escapeHtml(valueOrDash(application.getVersionOrBuild())));
            row.put("modulesHtml", formatMultiline(valueOrDash(application.getModuleList())));
            row.put("platformHtml", escapeHtml(valueOrDash(application.getPlatform())));
            row.put("primaryHtml", application.isPrimary() ? "Yes" : "No");
            row.put("descriptionHtml", formatMultiline(valueOrDash(application.getDescription())));
            row.put("relatedServicesHtml", formatMultiline(valueOrDash(application.getRelatedServices())));
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> buildLineItems(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        String normalized = normalizeLineEndings(value);
        String[] lines = normalized.split("\n", -1);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("number", Integer.toString(index + 1));
            item.put("textHtml", lines[index].isEmpty() ? "&nbsp;" : escapeHtml(lines[index]));
            items.add(item);
        }
        return items;
    }

    private static List<Map<String, Object>> buildEvidenceItems(
            List<ExecutionRunEvidenceRecord> evidences,
            Function<String, Path> evidenceResolver
    ) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ExecutionRunEvidenceRecord evidence : evidences) {
            String displayName = firstNonBlank(evidence.getDisplayName(), "Evidence Preview");
            String dataUri = resolveEvidenceDataUri(evidence, evidenceResolver);
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("showImage", dataUri != null);
            item.put("displayNameHtml", escapeHtml(displayName));
            item.put("dataUri", dataUri == null ? "" : escapeHtml(dataUri));
            item.put("openAriaLabelHtml", escapeHtml("Open evidence " + displayName + " in lightbox"));
            items.add(item);
        }
        return items;
    }

    private static Map<String, Object> buildChip(String label, String value, boolean statusChip, String statusClass) {
        LinkedHashMap<String, Object> chip = new LinkedHashMap<>();
        chip.put("classSuffix", statusChip && !isBlank(statusClass) ? " status-chip status-" + escapeHtml(statusClass) : "");
        chip.put("showLabel", !isBlank(label));
        chip.put("labelHtml", escapeHtml(label));
        chip.put("valueHtml", escapeHtml(valueOrDash(value)));
        return chip;
    }

    private static Map<String, Object> buildHighlightCard(String label, String value, String toneClass) {
        LinkedHashMap<String, Object> card = new LinkedHashMap<>();
        card.put("classSuffix", isBlank(toneClass) ? "" : " tone-" + escapeHtml(toneClass));
        card.put("labelHtml", escapeHtml(label));
        card.put("valueHtml", escapeHtml(valueOrDash(value)));
        return card;
    }

    private static Map<String, Object> buildMetricCard(String label, String value, String toneClass) {
        LinkedHashMap<String, Object> card = new LinkedHashMap<>();
        card.put("classSuffix", isBlank(toneClass) ? "" : " metric-" + escapeHtml(toneClass));
        card.put("labelHtml", escapeHtml(label));
        card.put("valueHtml", escapeHtml(valueOrDash(value)));
        return card;
    }

    private static Map<String, Object> buildDisplayItem(String label, String valueHtml) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("labelHtml", escapeHtml(label));
        item.put("valueHtml", valueHtml);
        return item;
    }

    private static String renderTemplate(Map<String, Object> model) {
        StringWriter writer = new StringWriter(24_000);
        try {
            TEMPLATE_ENGINE.getTemplate(HTML_EXPORT_TEMPLATE).evaluate(writer, model);
            return writer.toString();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to render HTML export template.", exception);
        }
    }

    private static PebbleEngine createTemplateEngine() {
        ModuleResourceLoader loader = new ModuleResourceLoader();
        loader.setPrefix("com/buraktok/reportforge");
        loader.setSuffix(".peb");
        loader.setCharset(StandardCharsets.UTF_8.name());
        return new PebbleEngine.Builder()
                .loader(loader)
                .autoEscaping(false)
                .newLineTrimming(true)
                .cacheActive(true)
                .build();
    }

    private static String loadResourceText(String resourceName) {
        String resourcePath = "/com/buraktok/reportforge/" + resourceName;
        try (InputStream inputStream = HtmlReportExporter.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing HTML export resource: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load HTML export resource: " + resourcePath, exception);
        }
    }

    private static final class ModuleResourceLoader implements Loader<String> {
        private String charset = StandardCharsets.UTF_8.name();
        private String prefix = "";
        private String suffix = "";

        @Override
        public Reader getReader(String cacheKey) {
            String resourcePath = toResourcePath(cacheKey);
            InputStream inputStream = HtmlReportExporter.class.getResourceAsStream(resourcePath);
            if (inputStream == null) {
                throw new IllegalStateException("Missing HTML export template: " + resourcePath);
            }
            return new InputStreamReader(inputStream, Charset.forName(charset));
        }

        @Override
        public void setCharset(String charset) {
            this.charset = charset == null || charset.isBlank() ? StandardCharsets.UTF_8.name() : charset;
        }

        @Override
        public void setPrefix(String prefix) {
            this.prefix = trimResourceSegment(prefix);
        }

        @Override
        public void setSuffix(String suffix) {
            this.suffix = suffix == null ? "" : suffix;
        }

        @Override
        public String resolveRelativePath(String relativePath, String anchorPath) {
            Path anchor = Path.of(normalizeTemplateName(anchorPath));
            Path parent = anchor.getParent();
            Path resolved = parent == null
                    ? Path.of(normalizeTemplateName(relativePath))
                    : parent.resolve(normalizeTemplateName(relativePath));
            return normalizeTemplateName(resolved.normalize().toString());
        }

        @Override
        public String createCacheKey(String templateName) {
            return normalizeTemplateName(templateName);
        }

        @Override
        public boolean resourceExists(String templateName) {
            return HtmlReportExporter.class.getResource(toResourcePath(templateName)) != null;
        }

        private String toResourcePath(String templateName) {
            String normalizedName = normalizeTemplateName(templateName);
            StringBuilder builder = new StringBuilder("/");
            if (!prefix.isEmpty()) {
                builder.append(prefix).append("/");
            }
            builder.append(normalizedName);
            if (!suffix.isEmpty() && !normalizedName.endsWith(suffix)) {
                builder.append(suffix);
            }
            return builder.toString();
        }

        private String normalizeTemplateName(String templateName) {
            if (templateName == null || templateName.isBlank()) {
                return "";
            }
            String normalized = templateName.replace('\\', '/');
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            return normalized;
        }

        private String trimResourceSegment(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }
            String trimmed = value.replace('\\', '/');
            while (trimmed.startsWith("/")) {
                trimmed = trimmed.substring(1);
            }
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed;
        }
    }

    private static String minifyHtml(String html) {
        return MinifyHtml.minify(html, HTML_MINIFY_CONFIGURATION);
    }

    private static LinkedHashMap<String, String> filterFields(Map<String, String> fields, boolean skipEmptyContent) {
        LinkedHashMap<String, String> filteredFields = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (skipEmptyContent && isBlank(entry.getValue())) {
                continue;
            }
            filteredFields.put(entry.getKey(), entry.getValue());
        }
        return filteredFields;
    }

    private static boolean hasNonBlankValues(Map<String, String> fields) {
        for (String value : fields.values()) {
            if (!isBlank(value)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveEvidenceDataUri(
            ExecutionRunEvidenceRecord evidence,
            Function<String, Path> evidenceResolver
    ) {
        if (!evidence.isImage() || isBlank(evidence.getStoredPath())) {
            return null;
        }
        try {
            Path evidencePath = evidenceResolver.apply(evidence.getStoredPath());
            if (evidencePath == null || !Files.exists(evidencePath)) {
                return null;
            }
            String mediaType = resolveEvidenceMediaType(evidence, evidencePath);
            byte[] originalBytes = Files.readAllBytes(evidencePath);
            EmbeddedEvidencePayload payload = optimizeEvidencePayload(mediaType, originalBytes);
            String content = Base64.getEncoder().encodeToString(payload.bytes());
            return "data:" + payload.mediaType() + ";base64," + content;
        } catch (Exception exception) {
            return null;
        }
    }

    private static EmbeddedEvidencePayload optimizeEvidencePayload(String mediaType, byte[] originalBytes) throws IOException {
        if (!isWebpCandidate(mediaType)) {
            return new EmbeddedEvidencePayload(mediaType, originalBytes);
        }
        try {
            byte[] webpBytes = encodeLosslessWebp(originalBytes);
            if (webpBytes == null || webpBytes.length >= originalBytes.length) {
                return new EmbeddedEvidencePayload(mediaType, originalBytes);
            }
            return new EmbeddedEvidencePayload(WEBP_MIME_TYPE, webpBytes);
        } catch (IOException | RuntimeException | LinkageError exception) {
            return new EmbeddedEvidencePayload(mediaType, originalBytes);
        }
    }

    private static byte[] encodeLosslessWebp(byte[] originalBytes) throws IOException {
        BufferedImage image;
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(originalBytes)) {
            image = ImageIO.read(inputStream);
        }
        if (image == null) {
            return null;
        }

        var writers = ImageIO.getImageWritersByMIMEType(WEBP_MIME_TYPE);
        ImageWriter writer = writers.hasNext() ? writers.next() : null;
        if (writer == null) {
            return null;
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream(originalBytes.length);
             MemoryCacheImageOutputStream imageOutputStream = new MemoryCacheImageOutputStream(outputStream)) {
            WebPWriteParam writeParam = new WebPWriteParam(writer.getLocale() == null ? Locale.getDefault() : writer.getLocale());
            String[] compressionTypes = writeParam.getCompressionTypes();
            if (compressionTypes == null || compressionTypes.length <= WebPWriteParam.LOSSLESS_COMPRESSION) {
                return null;
            }
            writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParam.setCompressionType(compressionTypes[WebPWriteParam.LOSSLESS_COMPRESSION]);
            writeParam.setMethod(6);
            writer.setOutput(imageOutputStream);
            writer.write(null, new IIOImage(image, null, null), writeParam);
            imageOutputStream.flush();
            return outputStream.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static String resolveEvidenceMediaType(ExecutionRunEvidenceRecord evidence, Path evidencePath) {
        String mediaType = firstNonBlank(evidence.getMediaType(), probeContentType(evidencePath), inferMediaTypeFromPath(evidencePath));
        return isBlank(mediaType) ? "image/png" : mediaType.toLowerCase(Locale.ROOT);
    }

    private static String probeContentType(Path evidencePath) {
        try {
            return Files.probeContentType(evidencePath);
        } catch (IOException exception) {
            return null;
        }
    }

    private static String inferMediaTypeFromPath(Path evidencePath) {
        String fileName = evidencePath.getFileName() == null ? "" : evidencePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".png")) {
            return "image/png";
        }
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (fileName.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (fileName.endsWith(".webp")) {
            return WEBP_MIME_TYPE;
        }
        return null;
    }

    private static boolean isWebpCandidate(String mediaType) {
        return switch (mediaType) {
            case "image/png", "image/jpeg", "image/jpg", "image/bmp" -> true;
            default -> false;
        };
    }

    private static String formatPassRate(ExecutionMetrics metrics) {
        if (metrics.getTotalExecuted() <= 0) {
            return "N/A";
        }
        double passRate = (metrics.getPassedCount() * 100.0) / metrics.getTotalExecuted();
        return String.format(Locale.US, "%.2f%%", passRate);
    }

    private static String formatDate(String value) {
        if (isBlank(value)) {
            return "-";
        }
        try {
            return DATE_FORMATTER.format(LocalDate.parse(value));
        } catch (DateTimeParseException exception) {
            return value;
        }
    }

    private static String formatTimestamp(String value) {
        if (isBlank(value)) {
            return "-";
        }
        try {
            return TIMESTAMP_FORMATTER.format(Instant.parse(value));
        } catch (DateTimeParseException exception) {
            return value;
        }
    }

    private static String formatMultiline(String value) {
        String normalized = normalizeLineEndings(valueOrDash(value));
        return escapeHtml(normalized).replace("\n", "<br>");
    }

    private static String normalizeStatus(String value) {
        if (isBlank(value)) {
            return "NOT_RUN";
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "PASSED" -> "PASS";
            case "FAILED" -> "FAIL";
            case "NOT_EXECUTED" -> "NOT_RUN";
            case "SKIP" -> "SKIPPED";
            default -> value.trim().toUpperCase(Locale.ROOT);
        };
    }

    private static String statusClass(String value) {
        return normalizeStatus(value).toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String toneForStatus(String value) {
        return switch (normalizeStatus(value)) {
            case "PASS" -> "success";
            case "FAIL" -> "danger";
            case "BLOCKED", "DEFERRED" -> "warning";
            case "NOT_RUN", "NOT_EXECUTED", "SKIPPED", "MIXED" -> "neutral";
            default -> "accent";
        };
    }

    private static String toneForCount(int value, String nonZeroTone) {
        return value > 0 ? nonZeroTone : "neutral";
    }

    private static String normalizeLineEndings(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private static boolean presetEnabled(Map<String, String> fields, String fieldKey, boolean defaultValue) {
        String value = fields.get(fieldKey);
        if (isBlank(value)) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String valueOrDash(String value) {
        return isBlank(value) ? "-" : value;
    }

    private static String escapeHtml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private record EmbeddedEvidencePayload(String mediaType, byte[] bytes) {
    }
}
