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
import in.wilsonl.minifyhtml.Configuration;
import in.wilsonl.minifyhtml.MinifyHtml;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.loader.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER = LoggerFactory.getLogger(HtmlReportExporter.class);
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

    /**
     * Prevents instantiation of this utility class.
     */
    private HtmlReportExporter() {
    }

    /**
     * Renders the complete HTML report and writes it to the specified output file path.
     *
     * @param outputPath        destination file path
     * @param project           project context information
     * @param report            report metadata
     * @param fields            custom report fields
     * @param applications      listed applications
     * @param environment       environment details
     * @param executionSnapshot aggregated testing metrics and run data
     * @param evidenceResolver  function to resolve evidence files
     * @throws IOException if disk write fails
     */
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

    /**
     * Generates a fully minified HTML representation of the report.
     *
     * @param project           project context information
     * @param report            report metadata
     * @param fields            custom report fields
     * @param applications      listed applications
     * @param environment       environment details
     * @param executionSnapshot aggregated testing metrics and run data
     * @param evidenceResolver  function to resolve evidence files
     * @return minified HTML string
     */
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

    /**
     * Generates an unminified HTML representation of the report, useful for debugging.
     *
     * @param project           project context information
     * @param report            report metadata
     * @param fields            custom report fields
     * @param applications      listed applications
     * @param environment       environment details
     * @param executionSnapshot aggregated testing metrics and run data
     * @param evidenceResolver  function to resolve evidence files
     * @return unminified HTML string
     */
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

    /**
     * Core orchestration method that builds the view model and evaluates the Pebble template.
     *
     * @param project           project context information
     * @param report            report metadata
     * @param fields            custom report fields
     * @param applications      listed applications
     * @param environment       environment details
     * @param executionSnapshot aggregated testing metrics and run data
     * @param evidenceResolver  function to resolve evidence files
     * @return rendered HTML markup
     */
    private static String renderReportMarkup(
            ProjectSummary project,
            ReportRecord report,
            Map<String, String> fields,
            List<ApplicationEntry> applications,
            EnvironmentRecord environment,
            ExecutionReportSnapshot executionSnapshot,
            Function<String, Path> evidenceResolver
    ) {
        HtmlExportViewModel viewModel = buildViewModel(
                project,
                report,
                fields,
                applications,
                environment,
                executionSnapshot,
                evidenceResolver
        );
        return renderTemplate(buildTemplateContext(viewModel));
    }

    /**
     * Constructs the comprehensive view model containing all sections and UI elements for the template.
     *
     * @param project           project context information
     * @param report            report metadata
     * @param fields            custom report fields
     * @param applications      listed applications
     * @param environment       environment details
     * @param executionSnapshot aggregated testing metrics and run data
     * @param evidenceResolver  function to resolve evidence files
     * @return populated view model
     */
    static HtmlExportViewModel buildViewModel(
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

        LinkedHashMap<String, String> heroFields = new LinkedHashMap<>();
        heroFields.put("Prepared By", fields.get("projectOverview.preparedBy"));
        heroFields.put("Prepared Date", formatDate(fields.get("projectOverview.preparedDate")));
        heroFields.put("Release / Iteration", fields.get("projectOverview.releaseIterationName"));
        heroFields.put("Stakeholder Audience", fields.get("projectOverview.stakeholderAudience"));
        heroFields.put("Revision Note", fields.get("projectOverview.revisionNote"));
        heroFields.put("Report Updated", formatTimestamp(report.getUpdatedAt()));
        List<HtmlExportViewModel.DisplayItem> heroMetaItems = buildNonBlankDisplayItems(heroFields);
        HtmlExportViewModel.HeroSection heroSection = new HtmlExportViewModel.HeroSection(
                buildHeroChips(projectName, environment, report, generatedAt),
                heroMetaItems,
                heroMetaItems.isEmpty(),
                buildHeroHighlights(executionSnapshot.getMetrics(), passRate)
        );

        LinkedHashMap<String, String> overviewFields = new LinkedHashMap<>();
        overviewFields.put("Project Name", projectName);
        overviewFields.put("Report Title", report.getTitle());
        overviewFields.put("Report Type", reportType);
        overviewFields.put("Environment", environment.getName());
        overviewFields.put("Status", report.getStatus().getDisplayName());
        LinkedHashMap<String, String> filteredOverviewFields = filterFields(overviewFields, skipEmptyContent);
        List<HtmlExportViewModel.DisplayItem> projectOverviewItems = buildFieldItems(filteredOverviewFields);
        boolean showProjectOverviewSection = !skipEmptyContent || hasNonBlankValues(filteredOverviewFields) || !isBlank(projectDescription);
        boolean showProjectDescriptionBlock = !skipEmptyContent || !isBlank(projectDescription);
        HtmlExportViewModel.DataGridSection projectOverviewSection = new HtmlExportViewModel.DataGridSection(
                showProjectOverviewSection,
                !projectOverviewItems.isEmpty(),
                projectOverviewItems
        );
        HtmlExportViewModel.TextSection projectDescriptionSection = new HtmlExportViewModel.TextSection(
                showProjectOverviewSection,
                showProjectDescriptionBlock,
                !isBlank(projectDescription),
                isBlank(projectDescription) ? "" : formatMultiline(projectDescription)
        );

        boolean showApplicationsSection = !skipEmptyContent || !applications.isEmpty();
        HtmlExportViewModel.ApplicationsSection applicationsSection = new HtmlExportViewModel.ApplicationsSection(
                showApplicationsSection,
                applications.isEmpty(),
                buildApplicationRows(applications)
        );

        LinkedHashMap<String, String> environmentFields = new LinkedHashMap<>();
        environmentFields.put("Environment Name", environment.getName());
        environmentFields.put("Environment Type", environment.getType());
        environmentFields.put("Base URL / Endpoint", environment.getBaseUrl());
        environmentFields.put("OS / Platform", environment.getOsPlatform());
        environmentFields.put("Browser / Client", environment.getBrowserClient());
        environmentFields.put("Backend Version", environment.getBackendVersion());
        environmentFields.put("Environment Notes", environment.getNotes());
        LinkedHashMap<String, String> filteredEnvironmentFields = filterFields(environmentFields, skipEmptyContent);
        List<HtmlExportViewModel.DisplayItem> environmentItems = buildFieldItems(filteredEnvironmentFields);
        HtmlExportViewModel.DataGridSection environmentSection = new HtmlExportViewModel.DataGridSection(
                !skipEmptyContent || hasNonBlankValues(filteredEnvironmentFields),
                !environmentItems.isEmpty(),
                environmentItems
        );

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
        List<HtmlExportViewModel.TextPanel> scopePanels = buildTextPanels(filteredScopeFields);
        HtmlExportViewModel.PanelSection scopeSection = new HtmlExportViewModel.PanelSection(
                !skipEmptyContent || hasNonBlankValues(filteredScopeFields),
                scopePanels.isEmpty(),
                scopePanels
        );

        HtmlExportViewModel.TextSection buildReleaseInfoSection =
                buildTextSectionFromField(fields.get("buildReleaseInfo.content"), skipEmptyContent);

        String executionNarrative = fields.get("executionSummary.summaryNarrative");
        HtmlExportViewModel.ExecutionSummarySection executionSummarySection = new HtmlExportViewModel.ExecutionSummarySection(
                buildMetricCards(executionSnapshot.getMetrics(), passRate),
                buildOptionalTextBlock(executionNarrative, skipEmptyContent)
        );

        List<HtmlExportViewModel.RunCard> runs = buildRunCards(
                executionSnapshot.getRuns(),
                evidenceResolver,
                skipEmptyContent,
                includeEvidenceImages
        );
        HtmlExportViewModel.ExecutionRunsSection executionRunsSection = new HtmlExportViewModel.ExecutionRunsSection(
                !skipEmptyContent || !executionSnapshot.getRuns().isEmpty(),
                runs.isEmpty(),
                runs
        );

        HtmlExportViewModel.TextSection testCoverageSection =
                buildTextSectionFromField(fields.get("testCoverage.content"), skipEmptyContent);

        HtmlExportViewModel.TextSection riskAssessmentSection =
                buildTextSectionFromField(fields.get("riskAssessment.content"), skipEmptyContent);

        HtmlExportViewModel.TextSection reportNotesSection =
                buildTextSectionFromField(fields.get("notes.content"), skipEmptyContent);

        LinkedHashMap<String, String> conclusionFields = new LinkedHashMap<>();
        conclusionFields.put("Overall Conclusion", fields.get("conclusion.overallConclusion"));
        conclusionFields.put("Recommendations", fields.get("conclusion.recommendations"));
        conclusionFields.put("Known Limitations", fields.get("conclusion.knownLimitations"));
        conclusionFields.put("Follow-up Actions", fields.get("conclusion.followUpActions"));
        conclusionFields.put("Open Concerns", fields.get("conclusion.openConcerns"));
        LinkedHashMap<String, String> filteredConclusionFields = filterFields(conclusionFields, skipEmptyContent);
        List<HtmlExportViewModel.TextPanel> conclusionPanels = buildTextPanels(filteredConclusionFields);
        HtmlExportViewModel.PanelSection conclusionSection = new HtmlExportViewModel.PanelSection(
                !skipEmptyContent || hasNonBlankValues(filteredConclusionFields),
                conclusionPanels.isEmpty(),
                conclusionPanels
        );

        return new HtmlExportViewModel(
                escapeHtml(report.getTitle()) + " - ReportForge Export",
                HTML_EXPORT_CSS,
                HTML_EXPORT_JS,
                escapeHtml(report.getTitle()),
                escapeHtml(reportType) + " for " + escapeHtml(projectName),
                escapeHtml(generatedAt),
                heroSection,
                projectOverviewSection,
                projectDescriptionSection,
                applicationsSection,
                environmentSection,
                scopeSection,
                buildReleaseInfoSection,
                executionSummarySection,
                executionRunsSection,
                testCoverageSection,
                riskAssessmentSection,
                reportNotesSection,
                conclusionSection,
                includeEvidenceImages,
                escapeHtml(generatedAt)
        );
    }

    /**
     * Maps the strongly-typed view model into a dynamic context dictionary for the Pebble template engine.
     *
     * @param viewModel the fully constructed view model
     * @return template context map
     */
    private static Map<String, Object> buildTemplateContext(HtmlExportViewModel viewModel) {
        LinkedHashMap<String, Object> model = new LinkedHashMap<>();
        model.put("pageTitleHtml", viewModel.pageTitleHtml());
        model.put("styles", viewModel.styles());
        model.put("script", viewModel.script());
        model.put("reportTitleHtml", viewModel.reportTitleHtml());
        model.put("heroSubtitleHtml", viewModel.heroSubtitleHtml());
        model.put("generatedAtHtml", viewModel.generatedAtHtml());
        model.put("heroChips", toTemplateChips(viewModel.heroSection().chips()));
        model.put("heroMetaItems", toTemplateDisplayItems(viewModel.heroSection().metaItems()));
        model.put("showHeroMetaEmptyState", viewModel.heroSection().showMetaEmptyState());
        model.put("heroHighlights", toTemplateHighlightCards(viewModel.heroSection().highlights()));
        model.put("showProjectOverviewSection", viewModel.projectOverviewSection().showSection());
        model.put("showProjectOverviewDataGrid", viewModel.projectOverviewSection().showDataGrid());
        model.put("projectOverviewFields", toTemplateDisplayItems(viewModel.projectOverviewSection().fields()));
        putTextSectionBlock(model, "projectDescription", viewModel.projectDescriptionSection());
        model.put("showApplicationsSection", viewModel.applicationsSection().showSection());
        model.put("showApplicationsEmptyState", viewModel.applicationsSection().showEmptyState());
        model.put("applications", toTemplateApplicationRows(viewModel.applicationsSection().rows()));
        model.put("showEnvironmentSection", viewModel.environmentSection().showSection());
        model.put("showEnvironmentDataGrid", viewModel.environmentSection().showDataGrid());
        model.put("environmentFields", toTemplateDisplayItems(viewModel.environmentSection().fields()));
        model.put("showScopeSection", viewModel.scopeSection().showSection());
        model.put("showScopeEmptyState", viewModel.scopeSection().showEmptyState());
        model.put("scopePanels", toTemplateTextPanels(viewModel.scopeSection().panels()));
        putTextSection(model, "buildReleaseInfo", viewModel.buildReleaseInfoSection());
        model.put("metricCards", toTemplateMetricCards(viewModel.executionSummarySection().metricCards()));
        putTextSectionBlock(model, "executionNarrative", viewModel.executionSummarySection().narrativeSection());
        model.put("showExecutionRunsSection", viewModel.executionRunsSection().showSection());
        model.put("showExecutionRunsEmptyState", viewModel.executionRunsSection().showEmptyState());
        model.put("runs", toTemplateRunCards(viewModel.executionRunsSection().runs()));
        putTextSection(model, "testCoverage", viewModel.testCoverageSection());
        putTextSection(model, "riskAssessment", viewModel.riskAssessmentSection());
        putTextSection(model, "reportNotes", viewModel.reportNotesSection());
        model.put("showConclusionSection", viewModel.conclusionSection().showSection());
        model.put("showConclusionEmptyState", viewModel.conclusionSection().showEmptyState());
        model.put("conclusionPanels", toTemplateTextPanels(viewModel.conclusionSection().panels()));
        model.put("showLightbox", viewModel.showLightbox());
        model.put("footerGeneratedAtHtml", viewModel.footerGeneratedAtHtml());
        return model;
    }

    /**
     * Constructs a text section view model from a given raw text field mapping.
     *
     * @param fieldValue       the text field data
     * @param skipEmptyContent true to skip if mapping has no text
     * @return constructed text section
     */
    private static HtmlExportViewModel.TextSection buildTextSectionFromField(String fieldValue, boolean skipEmptyContent) {
        boolean hasContent = !isBlank(fieldValue);
        boolean showSection = !skipEmptyContent || hasContent;
        return new HtmlExportViewModel.TextSection(
                showSection,
                showSection,
                hasContent,
                hasContent ? formatMultiline(fieldValue) : ""
        );
    }

    /**
     * Constructs an optional inline text block primarily for narrative summary fields.
     *
     * @param fieldValue       the explicit string text body
     * @param skipEmptyContent true to skip if text is blank
     * @return constructed text section
     */
    private static HtmlExportViewModel.TextSection buildOptionalTextBlock(String fieldValue, boolean skipEmptyContent) {
        boolean hasContent = !isBlank(fieldValue);
        return new HtmlExportViewModel.TextSection(
                true,
                !skipEmptyContent || hasContent,
                hasContent,
                hasContent ? formatMultiline(fieldValue) : ""
        );
    }

    /**
     * Injects a fully rendered display text section block into the raw template context parameters.
     *
     * @param model    the template dictionary map
     * @param baseName the target map key variable name
     * @param section  the constructed text section items
     */
    private static void putTextSection(Map<String, Object> model, String baseName, HtmlExportViewModel.TextSection section) {
        String name = upperCamelCase(baseName);
        model.put("show" + name + "Section", section.showSection());
        putTextSectionBlock(model, baseName, section);
    }

    /**
     * Injects the explicit attributes of a text section block into the map.
     *
     * @param model    the dictionary map
     * @param baseName the target map key string
     * @param section  the text block properties
     */
    private static void putTextSectionBlock(Map<String, Object> model, String baseName, HtmlExportViewModel.TextSection section) {
        String name = lowerCamelCase(baseName);
        String titleName = upperCamelCase(baseName);
        model.put("show" + titleName + "Block", section.showBlock());
        model.put(name + "HasContent", section.hasContent());
        model.put(name + "Html", section.html());
    }

    /**
     * Formats a raw key string into strict upper camel case.
     *
     * @param value the raw string
     * @return the camel case string
     */
    private static String upperCamelCase(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /**
     * Formats a raw key string into strict lower camel case.
     *
     * @param value the raw string
     * @return the camel case string
     */
    private static String lowerCamelCase(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    /**
     * Maps view model chip items into plain template dictionary objects.
     *
     * @param chips the view model chips
     * @return the mapped template item dictionaries
     */
    private static List<Map<String, Object>> toTemplateChips(List<HtmlExportViewModel.Chip> chips) {
        return chips.stream()
                .map(chip -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("classSuffix", chip.classSuffix());
                    item.put("showLabel", chip.showLabel());
                    item.put("labelHtml", chip.labelHtml());
                    item.put("valueHtml", chip.valueHtml());
                    return item;
                })
                .toList();
    }

    /**
     * Maps view model display items into plain template dictionary objects.
     *
     * @param items the view model display items
     * @return the mapped template item dictionaries
     */
    private static List<Map<String, Object>> toTemplateDisplayItems(List<HtmlExportViewModel.DisplayItem> items) {
        return items.stream()
                .map(item -> {
                    Map<String, Object> templateItem = new LinkedHashMap<>();
                    templateItem.put("labelHtml", item.labelHtml());
                    templateItem.put("valueHtml", item.valueHtml());
                    return templateItem;
                })
                .toList();
    }

    /**
     * Maps view model highlight cards into plain template dictionary objects.
     *
     * @param cards the view model spotlight cards
     * @return the mapped template item dictionaries
     */
    private static List<Map<String, Object>> toTemplateHighlightCards(List<HtmlExportViewModel.HighlightCard> cards) {
        return cards.stream()
                .map(card -> {
                    Map<String, Object> templateCard = new LinkedHashMap<>();
                    templateCard.put("classSuffix", card.classSuffix());
                    templateCard.put("labelHtml", card.labelHtml());
                    templateCard.put("valueHtml", card.valueHtml());
                    return templateCard;
                })
                .toList();
    }

    /**
     * Maps execution metrics summary cards into plain template dictionary objects.
     *
     * @param cards the view model metric cards
     * @return the mapped template item dictionaries
     */
    private static List<Map<String, Object>> toTemplateMetricCards(List<HtmlExportViewModel.MetricCard> cards) {
        return cards.stream()
                .map(card -> {
                    Map<String, Object> templateCard = new LinkedHashMap<>();
                    templateCard.put("classSuffix", card.classSuffix());
                    templateCard.put("labelHtml", card.labelHtml());
                    templateCard.put("valueHtml", card.valueHtml());
                    return templateCard;
                })
                .toList();
    }

    /**
     * Maps expanding text panels into plain template dictionary objects.
     *
     * @param panels the view model text panes
     * @return the mapped template item dictionaries
     */
    private static List<Map<String, Object>> toTemplateTextPanels(List<HtmlExportViewModel.TextPanel> panels) {
        return panels.stream()
                .map(panel -> {
                    Map<String, Object> templatePanel = new LinkedHashMap<>();
                    templatePanel.put("labelHtml", panel.labelHtml());
                    templatePanel.put("valueHtml", panel.valueHtml());
                    return templatePanel;
                })
                .toList();
    }

    /**
     * Maps application descriptor table rows into plain template dictionary objects.
     *
     * @param rows the application entries
     * @return the mapped template item dictionaries
     */
    private static List<Map<String, Object>> toTemplateApplicationRows(List<HtmlExportViewModel.ApplicationRow> rows) {
        return rows.stream()
                .map(row -> {
                    Map<String, Object> templateRow = new LinkedHashMap<>();
                    templateRow.put("nameHtml", row.nameHtml());
                    templateRow.put("versionHtml", row.versionHtml());
                    templateRow.put("modulesHtml", row.modulesHtml());
                    templateRow.put("platformHtml", row.platformHtml());
                    templateRow.put("primaryHtml", row.primaryHtml());
                    templateRow.put("descriptionHtml", row.descriptionHtml());
                    templateRow.put("relatedServicesHtml", row.relatedServicesHtml());
                    return templateRow;
                })
                .toList();
    }

    /**
     * Maps ordered test steps line arrays into plain template dictionary objects.
     *
     * @param items the step details
     * @return the mapped template item dictionaries
     */
    private static List<Map<String, Object>> toTemplateLineItems(List<HtmlExportViewModel.LineItem> items) {
        return items.stream()
                .map(item -> {
                    Map<String, Object> templateItem = new LinkedHashMap<>();
                    templateItem.put("number", item.number());
                    templateItem.put("textHtml", item.textHtml());
                    return templateItem;
                })
                .toList();
    }

    /**
     * Maps execution runtime evidence images into plain template dictionary objects.
     *
     * @param items the evidence records
     * @return the mapped template item dictionaries
     */
    private static List<Map<String, Object>> toTemplateEvidenceItems(List<HtmlExportViewModel.EvidenceItem> items) {
        return items.stream()
                .map(item -> {
                    Map<String, Object> templateItem = new LinkedHashMap<>();
                    templateItem.put("showImage", item.showImage());
                    templateItem.put("displayNameHtml", item.displayNameHtml());
                    templateItem.put("dataUri", item.dataUri());
                    templateItem.put("openAriaLabelHtml", item.openAriaLabelHtml());
                    return templateItem;
                })
                .toList();
    }

    /**
     * Maps individual execution run articles into plain template dictionary objects.
     *
     * @param runs the run histories
     * @return the mapped template item dictionaries
     */
    private static List<Map<String, Object>> toTemplateRunCards(List<HtmlExportViewModel.RunCard> runs) {
        return runs.stream()
                .map(run -> {
                    Map<String, Object> templateRun = new LinkedHashMap<>();
                    templateRun.put("articleClass", run.articleClass());
                    templateRun.put("displayLabelHtml", run.displayLabelHtml());
                    templateRun.put("chips", toTemplateChips(run.chips()));
                    templateRun.put("showDataFields", run.showDataFields());
                    templateRun.put("dataFields", toTemplateDisplayItems(run.dataFields()));
                    templateRun.put("showTextPanels", run.showTextPanels());
                    templateRun.put("textPanels", toTemplateTextPanels(run.textPanels()));
                    templateRun.put("showTestSteps", run.showTestSteps());
                    templateRun.put("testSteps", toTemplateLineItems(run.testSteps()));
                    templateRun.put("showEvidenceSection", run.showEvidenceSection());
                    templateRun.put("evidences", toTemplateEvidenceItems(run.evidences()));
                    return templateRun;
                })
                .toList();
    }

    /**
     * Constructs the primary top-level metadata chips displayed in the report header.
     *
     * @param projectName the name of the project
     * @param environment the current environment context
     * @param report      the report entity containing status
     * @param generatedAt the formatted timestamp of generation
     * @return the constructed label chips
     */
    private static List<HtmlExportViewModel.Chip> buildHeroChips(
            String projectName,
            EnvironmentRecord environment,
            ReportRecord report,
            String generatedAt
    ) {
        List<HtmlExportViewModel.Chip> chips = new ArrayList<>();
        chips.add(buildChip("Project", projectName, false, null));
        chips.add(buildChip("Environment", environment.getName(), false, null));
        chips.add(buildChip("Generated", generatedAt, false, null));
        chips.add(buildChip("Report Status", report.getStatus().getDisplayName(), false, null));
        return chips;
    }

    /**
     * Builds the prominent highlight cards shown in the report header based on execution metrics.
     *
     * @param metrics  the total aggregated metrics
     * @param passRate the formatted percentage pass string
     * @return the list of highlight cards
     */
    private static List<HtmlExportViewModel.HighlightCard> buildHeroHighlights(ExecutionMetrics metrics, String passRate) {
        List<HtmlExportViewModel.HighlightCard> highlights = new ArrayList<>();
        highlights.add(buildHighlightCard("Overall Outcome", normalizeStatus(metrics.getOverallOutcome()), toneForStatus(metrics.getOverallOutcome())));
        highlights.add(buildHighlightCard("Pass Rate", passRate, "accent"));
        highlights.add(buildHighlightCard("Execution Runs", Integer.toString(metrics.getExecutionRunCount()), "neutral"));
        highlights.add(buildHighlightCard("Linked Issues", Integer.toString(metrics.getIssueCount()), metrics.getIssueCount() > 0 ? "danger" : "success"));
        return highlights;
    }

    /**
     * Constructs the detailed metric result cards for the main execution summary section.
     *
     * @param metrics  the aggregated metrics
     * @param passRate the formatted percentage pass string
     * @return the complete list of metric cards
     */
    private static List<HtmlExportViewModel.MetricCard> buildMetricCards(ExecutionMetrics metrics, String passRate) {
        List<HtmlExportViewModel.MetricCard> cards = new ArrayList<>();
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

    /**
     * Translates raw execution snapshots into render-ready run card view models.
     *
     * @param runs                  the list of execution snapshots
     * @param evidenceResolver      function determining local image paths
     * @param skipEmptyContent      whether to hide empty data fields
     * @param includeEvidenceImages whether to natively embed attached photos
     * @return the comprehensive run cards list
     */
    private static List<HtmlExportViewModel.RunCard> buildRunCards(
            List<ExecutionRunSnapshot> runs,
            Function<String, Path> evidenceResolver,
            boolean skipEmptyContent,
            boolean includeEvidenceImages
    ) {
        List<HtmlExportViewModel.RunCard> runCards = new ArrayList<>();
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
            List<HtmlExportViewModel.DisplayItem> runFieldItems = buildFieldItems(filteredRunFields);

            LinkedHashMap<String, String> runTextFields = new LinkedHashMap<>();
            runTextFields.put("Comments", run.getComments());
            runTextFields.put("Notes", run.getNotes());
            runTextFields.put("Expected Result Summary", run.getExpectedResultSummary());
            runTextFields.put("Actual Result", run.getActualResult());
            runTextFields.put("Blocked Reason", run.getBlockedReason());
            runTextFields.put("Remarks", run.getRemarks());
            runTextFields.put("Defect Summary", run.getDefectSummary());
            LinkedHashMap<String, String> filteredRunTextFields = filterFields(runTextFields, skipEmptyContent);
            List<HtmlExportViewModel.TextPanel> runTextPanels = buildTextPanels(filteredRunTextFields);

            List<HtmlExportViewModel.LineItem> testSteps = buildLineItems(run.getTestSteps());
            List<HtmlExportViewModel.EvidenceItem> evidenceItems = includeEvidenceImages
                    ? buildEvidenceItems(runSnapshot.getEvidences(), evidenceResolver)
                    : List.of();

            runCards.add(new HtmlExportViewModel.RunCard(
                    "run-card run-card-" + escapeHtml(runStatusClass),
                    escapeHtml(run.getDisplayLabel()),
                    buildRunChips(run, runStatusClass),
                    !runFieldItems.isEmpty(),
                    runFieldItems,
                    !runTextPanels.isEmpty(),
                    runTextPanels,
                    !testSteps.isEmpty(),
                    testSteps,
                    includeEvidenceImages && !evidenceItems.isEmpty(),
                    evidenceItems
            ));
        }
        return runCards;
    }

    /**
     * Generates standard inline tag chips identifying individual test run metadata.
     *
     * @param run            the test run record
     * @param runStatusClass the mapped CSS styling class
     * @return the itemized chips
     */
    private static List<HtmlExportViewModel.Chip> buildRunChips(ExecutionRunRecord run, String runStatusClass) {
        List<HtmlExportViewModel.Chip> chips = new ArrayList<>();
        chips.add(buildChip("Status", normalizeStatus(run.getStatus()), true, runStatusClass));
        chips.add(buildChip("Execution ID", run.getExecutionKey(), false, null));
        chips.add(buildChip("Execution Date", formatDate(firstNonBlank(run.getExecutionDate(), run.getStartDate(), run.getEndDate())), false, null));
        return chips;
    }

    /**
     * Converts a map of key-value pairs into view model display items.
     *
     * @param fields the map of fields and values
     * @return the list of display items
     */
    private static List<HtmlExportViewModel.DisplayItem> buildFieldItems(Map<String, String> fields) {
        List<HtmlExportViewModel.DisplayItem> items = new ArrayList<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            items.add(buildDisplayItem(entry.getKey(), formatMultiline(valueOrDash(entry.getValue()))));
        }
        return items;
    }

    /**
     * Converts a map of key-value pairs into display items, explicitly skipping any blank values.
     *
     * @param fields the map of fields and values
     * @return the filtered list of display items
     */
    private static List<HtmlExportViewModel.DisplayItem> buildNonBlankDisplayItems(Map<String, String> fields) {
        List<HtmlExportViewModel.DisplayItem> items = new ArrayList<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (isBlank(entry.getValue())) {
                continue;
            }
            items.add(buildDisplayItem(entry.getKey(), formatMultiline(entry.getValue())));
        }
        return items;
    }

    /**
     * Transforms map entries into collapsible text panel view models, omitting blank values.
     *
     * @param fields the map of panel titles and text contents
     * @return the list of constructed text panels
     */
    private static List<HtmlExportViewModel.TextPanel> buildTextPanels(Map<String, String> fields) {
        List<HtmlExportViewModel.TextPanel> panels = new ArrayList<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (isBlank(entry.getValue())) {
                continue;
            }
            panels.add(new HtmlExportViewModel.TextPanel(
                    escapeHtml(entry.getKey()),
                    formatMultiline(entry.getValue())
            ));
        }
        return panels;
    }

    /**
     * Transforms a list of application records into layout-ready table row models.
     *
     * @param applications the listed application records
     * @return the view model rows for the applications table
     */
    private static List<HtmlExportViewModel.ApplicationRow> buildApplicationRows(List<ApplicationEntry> applications) {
        List<HtmlExportViewModel.ApplicationRow> rows = new ArrayList<>();
        for (ApplicationEntry application : applications) {
            rows.add(new HtmlExportViewModel.ApplicationRow(
                    escapeHtml(valueOrDash(application.getName())),
                    escapeHtml(valueOrDash(application.getVersionOrBuild())),
                    formatMultiline(valueOrDash(application.getModuleList())),
                    escapeHtml(valueOrDash(application.getPlatform())),
                    application.isPrimary() ? "Yes" : "No",
                    formatMultiline(valueOrDash(application.getDescription())),
                    formatMultiline(valueOrDash(application.getRelatedServices()))
            ));
        }
        return rows;
    }

    /**
     * Splits a multi-line text block into ordered line item view properties suitable for ordered lists.
     *
     * @param value the raw multi-line string text
     * @return the sequentially numbered line items
     */
    private static List<HtmlExportViewModel.LineItem> buildLineItems(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        String normalized = normalizeLineEndings(value);
        String[] lines = normalized.split("\n", -1);
        List<HtmlExportViewModel.LineItem> items = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            items.add(new HtmlExportViewModel.LineItem(
                    Integer.toString(index + 1),
                    lines[index].isEmpty() ? "&nbsp;" : escapeHtml(lines[index])
            ));
        }
        return items;
    }

    /**
     * Constructs the evidence attachment display models for a given list of run evidence records.
     *
     * @param evidences        the raw evidence records
     * @param evidenceResolver function to resolve absolute file paths
     * @return the list of display-ready evidence items
     */
    private static List<HtmlExportViewModel.EvidenceItem> buildEvidenceItems(
            List<ExecutionRunEvidenceRecord> evidences,
            Function<String, Path> evidenceResolver
    ) {
        List<HtmlExportViewModel.EvidenceItem> items = new ArrayList<>();
        for (ExecutionRunEvidenceRecord evidence : evidences) {
            String displayName = firstNonBlank(evidence.getDisplayName(), "Evidence Preview");
            String dataUri = resolveEvidenceDataUri(evidence, evidenceResolver);
            items.add(new HtmlExportViewModel.EvidenceItem(
                    dataUri != null,
                    escapeHtml(displayName),
                    dataUri == null ? "" : escapeHtml(dataUri),
                    escapeHtml("Open evidence " + displayName + " in lightbox")
            ));
        }
        return items;
    }

    /**
     * Helper to manually construct a basic metadata chip.
     *
     * @param label       the chip label
     * @param value       the chip value
     * @param statusChip  whether this is a status indicator chip
     * @param statusClass the CSS color tone class
     * @return the constructed chip
     */
    private static HtmlExportViewModel.Chip buildChip(String label, String value, boolean statusChip, String statusClass) {
        return new HtmlExportViewModel.Chip(
                statusChip && !isBlank(statusClass) ? " status-chip status-" + escapeHtml(statusClass) : "",
                !isBlank(label),
                escapeHtml(label),
                escapeHtml(valueOrDash(value))
        );
    }

    /**
     * Creates a single large highlight card for the report hero section.
     *
     * @param label     the card label text
     * @param value     the card primary value
     * @param toneClass the visual tone CSS class
     * @return the constructed highlight card
     */
    private static HtmlExportViewModel.HighlightCard buildHighlightCard(String label, String value, String toneClass) {
        return new HtmlExportViewModel.HighlightCard(
                isBlank(toneClass) ? "" : " tone-" + escapeHtml(toneClass),
                escapeHtml(label),
                escapeHtml(valueOrDash(value))
        );
    }

    /**
     * Creates a small granular metric card for the execution summary.
     *
     * @param label     the label text
     * @param value     the count or percentage value
     * @param toneClass the visual tone CSS class
     * @return the constructed metric card
     */
    private static HtmlExportViewModel.MetricCard buildMetricCard(String label, String value, String toneClass) {
        return new HtmlExportViewModel.MetricCard(
                isBlank(toneClass) ? "" : " metric-" + escapeHtml(toneClass),
                escapeHtml(label),
                escapeHtml(valueOrDash(value))
        );
    }

    /**
     * Creates a generic label-value display property pair.
     *
     * @param label     the label key
     * @param valueHtml the HTML-safe value string
     * @return the constructed display item
     */
    private static HtmlExportViewModel.DisplayItem buildDisplayItem(String label, String valueHtml) {
        return new HtmlExportViewModel.DisplayItem(
                escapeHtml(label),
                valueHtml
        );
    }

    /**
     * Evaluates the core Pebble HTML template against the provided context dictionary.
     *
     * @param model the fully populated context map
     * @return the rendered raw HTML string
     */
    private static String renderTemplate(Map<String, Object> model) {
        StringWriter writer = new StringWriter(24_000);
        try {
            TEMPLATE_ENGINE.getTemplate(HTML_EXPORT_TEMPLATE).evaluate(writer, model);
            return writer.toString();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to render HTML export template.", exception);
        }
    }

    /**
     * Configures and initializes the Pebble template engine with a custom classpath resource loader.
     *
     * @return the configured template engine
     */
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

    /**
     * Loads a raw text resource file completely into memory as a string.
     *
     * @param resourceName the relative name of the resource within the package
     * @return the complete file contents
     */
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

    /**
     * Custom Pebble template loader designed to resolve templates from module classpath resources.
     */
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

    /**
     * Compresses and minifies a raw HTML string into a highly optimized exportable string.
     *
     * @param html the raw HTML content
     * @return the minified HTML result
     */
    private static String minifyHtml(String html) {
        return MinifyHtml.minify(html, HTML_MINIFY_CONFIGURATION);
    }

    /**
     * Prunes a map of fields, optionally removing any entries that contain no data.
     *
     * @param fields           the original map fields
     * @param skipEmptyContent true to remove entries with blank values
     * @return a new filtered map containing valid fields
     */
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

    /**
     * Determines if a map contains at least one populated meaningful value string.
     *
     * @param fields the dictionary map to inspect
     * @return true if at least one value is not blank
     */
    private static boolean hasNonBlankValues(Map<String, String> fields) {
        for (String value : fields.values()) {
            if (!isBlank(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves an evidence file and encodes its content into a Base64 data URI string.
     *
     * @param evidence         the execution run evidence record
     * @param evidenceResolver the function to resolve the absolute file path
     * @return an optional string containing the inline data URI payload
     */
    private static String resolveEvidenceDataUri(
            ExecutionRunEvidenceRecord evidence,
            Function<String, Path> evidenceResolver
    ) {
        if (!evidence.isImage() || isBlank(evidence.getStoredPath())) {
            return null;
        }
        try {
            Path evidencePath = evidenceResolver.apply(evidence.getStoredPath());
            if (evidencePath == null) {
                LOGGER.warn("Evidence resolver returned no path for evidence '{}' ('{}').", evidence.getId(), evidence.getStoredPath());
                return null;
            }
            if (!Files.exists(evidencePath)) {
                LOGGER.warn("Evidence file '{}' for evidence '{}' does not exist during export.", evidencePath, evidence.getId());
                return null;
            }
            String mediaType = resolveEvidenceMediaType(evidence, evidencePath);
            byte[] originalBytes = Files.readAllBytes(evidencePath);
            String content = Base64.getEncoder().encodeToString(originalBytes);
            return "data:" + mediaType + ";base64," + content;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn(
                    "Unable to embed evidence '{}' from '{}'.",
                    evidence.getId(),
                    evidence.getStoredPath(),
                    exception
            );
            return null;
        }
    }



    /**
     * Resolves the effective MIME type using evidence rules, probing, or extensions.
     *
     * @param evidence     the original evidence
     * @param evidencePath the resolved local file path
     * @return the determined effective MIME media type
     */
    private static String resolveEvidenceMediaType(ExecutionRunEvidenceRecord evidence, Path evidencePath) {
        String mediaType = firstNonBlank(evidence.getMediaType(), probeContentType(evidencePath), inferMediaTypeFromPath(evidencePath));
        return isBlank(mediaType) ? "image/png" : mediaType.toLowerCase(Locale.ROOT);
    }

    /**
     * Probes the filesystem directly in an attempt to determine the content type.
     *
     * @param evidencePath the path to probe
     * @return the probed file type string, or null
     */
    private static String probeContentType(Path evidencePath) {
        try {
            return Files.probeContentType(evidencePath);
        } catch (IOException exception) {
            LOGGER.debug("Unable to probe media type for evidence '{}'.", evidencePath, exception);
            return null;
        }
    }

    /**
     * Infers a fallback MIME type based purely on explicit filename suffix extensions.
     *
     * @param evidencePath the named path
     * @return the correlated well-known extension type or null
     */
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



    /**
     * Calculates and formats the proportion of successful test iterations.
     *
     * @param metrics the overall metrics containing the status tally
     * @return a percentage string
     */
    private static String formatPassRate(ExecutionMetrics metrics) {
        if (metrics.getTotalExecuted() <= 0) {
            return "N/A";
        }
        double passRate = (metrics.getPassedCount() * 100.0) / metrics.getTotalExecuted();
        return String.format(Locale.US, "%.2f%%", passRate);
    }

    /**
     * Parses and formats a date string using the standard date formatter.
     *
     * @param value the raw date string
     * @return the formatted date string
     */
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

    /**
     * Parses and formats a timestamp string using the standard timestamp formatter.
     *
     * @param value the raw timestamp string
     * @return the formatted timestamp string
     */
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

    /**
     * Ensures strict UNIX newline usage across lines for template compatibility.
     *
     * @param value the raw multi-line mapped message
     * @return the normalized mapped format
     */
    private static String formatMultiline(String value) {
        String normalized = normalizeLineEndings(valueOrDash(value));
        return escapeHtml(normalized).replace("\n", "<br>");
    }

    /**
     * Normalizes a raw execution status string into a standard unified status label.
     *
     * @param value the raw programmatic status string
     * @return the normalized view status representation
     */
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

    /**
     * Resolves the CSS class for a status indicator based on its value.
     *
     * @param value the named status text
     * @return the corresponding lowercase styling class string
     */
    private static String statusClass(String value) {
        return normalizeStatus(value).toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Maps execution statuses to semantic CSS tone classes (success, danger, etc).
     *
     * @param value the normalized status string
     * @return the UI tone framework classification string
     */
    private static String toneForStatus(String value) {
        return switch (normalizeStatus(value)) {
            case "PASS" -> "success";
            case "FAIL" -> "danger";
            case "BLOCKED", "DEFERRED" -> "warning";
            case "NOT_RUN", "NOT_EXECUTED", "SKIPPED", "MIXED" -> "neutral";
            default -> "accent";
        };
    }

    /**
     * Maps an integer count to a specific tone class if non-zero, or neutral if zero.
     *
     * @param value       the count to inspect
     * @param nonZeroTone the class to use if greater than zero
     * @return the corresponding CSS tone class
     */
    private static String toneForCount(int value, String nonZeroTone) {
        return value > 0 ? nonZeroTone : "neutral";
    }

    /**
     * Converts all line breaking formats within a string natively to UNIX line structures.
     *
     * @param value the target multi-system string
     * @return the single platform line mapped text
     */
    private static String normalizeLineEndings(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * Returns the first non-blank string from the provided list of values.
     *
     * @param values an array of string values
     * @return the first non-blank string, or empty string if none found
     */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    /**
     * Evaluates a boolean value for a specific field in a map, with a default fallback.
     *
     * @param fields       the map of fields
     * @param fieldKey     the key to look for
     * @param defaultValue the fallback value if the key is missing or blank
     * @return true if the option is explicitly enabled or defaults to true
     */
    private static boolean presetEnabled(Map<String, String> fields, String fieldKey, boolean defaultValue) {
        String value = fields.get(fieldKey);
        if (isBlank(value)) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * Determines whether a given string is null or contains only whitespace.
     *
     * @param value the string to check
     * @return true if the string is empty or whitespace
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Substitutes a dash fallback character for an absent or empty text string.
     *
     * @param value the string value
     * @return the original string or a dash if blank
     */
    private static String valueOrDash(String value) {
        return isBlank(value) ? "-" : value;
    }

    /**
     * Escapes standard HTML special characters in the given input string.
     *
     * @param value the raw text to escape
     * @return the HTML escaped literal string
     */
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

}
