package com.buraktok.reportforge.export;

import java.util.List;

/**
 * View model structure representing the complete data bound to the HTML export template.
 * Defines all sections, cards, chips, and items rendered into the final report output.
 */
record HtmlExportViewModel(
        String pageTitleHtml,
        String styles,
        String script,
        String reportTitleHtml,
        String heroSubtitleHtml,
        String generatedAtHtml,
        HeroSection heroSection,
        DataGridSection projectOverviewSection,
        TextSection projectDescriptionSection,
        ApplicationsSection applicationsSection,
        DataGridSection environmentSection,
        PanelSection scopeSection,
        TextSection buildReleaseInfoSection,
        ExecutionSummarySection executionSummarySection,
        ExecutionRunsSection executionRunsSection,
        TextSection testCoverageSection,
        TextSection riskAssessmentSection,
        TextSection reportNotesSection,
        PanelSection conclusionSection,
        boolean showLightbox,
        String footerGeneratedAtHtml
) {
    record HeroSection(
            List<Chip> chips,
            List<DisplayItem> metaItems,
            boolean showMetaEmptyState,
            List<HighlightCard> highlights
    ) {
    }

    record DataGridSection(
            boolean showSection,
            boolean showDataGrid,
            List<DisplayItem> fields
    ) {
    }

    record TextSection(
            boolean showSection,
            boolean showBlock,
            boolean hasContent,
            String html
    ) {
    }

    record ApplicationsSection(
            boolean showSection,
            boolean showEmptyState,
            List<ApplicationRow> rows
    ) {
    }

    record PanelSection(
            boolean showSection,
            boolean showEmptyState,
            List<TextPanel> panels
    ) {
    }

    record ExecutionSummarySection(
            List<MetricCard> metricCards,
            TextSection narrativeSection
    ) {
    }

    record ExecutionRunsSection(
            boolean showSection,
            boolean showEmptyState,
            List<RunCard> runs
    ) {
    }

    record Chip(
            String classSuffix,
            boolean showLabel,
            String labelHtml,
            String valueHtml
    ) {
        public String getClassSuffix() {
            return classSuffix;
        }

        public boolean isShowLabel() {
            return showLabel;
        }

        public String getLabelHtml() {
            return labelHtml;
        }

        public String getValueHtml() {
            return valueHtml;
        }
    }

    record DisplayItem(
            String labelHtml,
            String valueHtml
    ) {
        public String getLabelHtml() {
            return labelHtml;
        }

        public String getValueHtml() {
            return valueHtml;
        }
    }

    record HighlightCard(
            String classSuffix,
            String labelHtml,
            String valueHtml
    ) {
        public String getClassSuffix() {
            return classSuffix;
        }

        public String getLabelHtml() {
            return labelHtml;
        }

        public String getValueHtml() {
            return valueHtml;
        }
    }

    record MetricCard(
            String classSuffix,
            String labelHtml,
            String valueHtml
    ) {
        public String getClassSuffix() {
            return classSuffix;
        }

        public String getLabelHtml() {
            return labelHtml;
        }

        public String getValueHtml() {
            return valueHtml;
        }
    }

    record TextPanel(
            String labelHtml,
            String valueHtml
    ) {
        public String getLabelHtml() {
            return labelHtml;
        }

        public String getValueHtml() {
            return valueHtml;
        }
    }

    record ApplicationRow(
            String nameHtml,
            String versionHtml,
            String modulesHtml,
            String platformHtml,
            String primaryHtml,
            String descriptionHtml,
            String relatedServicesHtml
    ) {
        public String getNameHtml() {
            return nameHtml;
        }

        public String getVersionHtml() {
            return versionHtml;
        }

        public String getModulesHtml() {
            return modulesHtml;
        }

        public String getPlatformHtml() {
            return platformHtml;
        }

        public String getPrimaryHtml() {
            return primaryHtml;
        }

        public String getDescriptionHtml() {
            return descriptionHtml;
        }

        public String getRelatedServicesHtml() {
            return relatedServicesHtml;
        }
    }

    record LineItem(
            String number,
            String textHtml
    ) {
        public String getNumber() {
            return number;
        }

        public String getTextHtml() {
            return textHtml;
        }
    }

    record EvidenceItem(
            boolean showImage,
            String displayNameHtml,
            String dataUri,
            String openAriaLabelHtml
    ) {
        public boolean isShowImage() {
            return showImage;
        }

        public String getDisplayNameHtml() {
            return displayNameHtml;
        }

        public String getDataUri() {
            return dataUri;
        }

        public String getOpenAriaLabelHtml() {
            return openAriaLabelHtml;
        }
    }

    record RunCard(
            String articleClass,
            String displayLabelHtml,
            List<Chip> chips,
            boolean showDataFields,
            List<DisplayItem> dataFields,
            boolean showTextPanels,
            List<TextPanel> textPanels,
            boolean showTestSteps,
            List<LineItem> testSteps,
            boolean showEvidenceSection,
            List<EvidenceItem> evidences
    ) {
        public String getArticleClass() {
            return articleClass;
        }

        public String getDisplayLabelHtml() {
            return displayLabelHtml;
        }

        public List<Chip> getChips() {
            return chips;
        }

        public boolean isShowDataFields() {
            return showDataFields;
        }

        public List<DisplayItem> getDataFields() {
            return dataFields;
        }

        public boolean isShowTextPanels() {
            return showTextPanels;
        }

        public List<TextPanel> getTextPanels() {
            return textPanels;
        }

        public boolean isShowTestSteps() {
            return showTestSteps;
        }

        public List<LineItem> getTestSteps() {
            return testSteps;
        }

        public boolean isShowEvidenceSection() {
            return showEvidenceSection;
        }

        public List<EvidenceItem> getEvidences() {
            return evidences;
        }
    }
}
