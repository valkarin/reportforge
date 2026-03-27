package com.buraktok.reportforge;

import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.ReportStatus;
import com.buraktok.reportforge.ui.ExecutionRunWorkspaceNode;
import com.buraktok.reportforge.ui.WorkspaceNode;
import com.buraktok.reportforge.ui.WorkspaceNodeType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ToolbarAndExportCoordinatorTest {

    @Test
    void resolveReportSelectionSupportsReportBackedSelections() {
        ReportRecord report = new ReportRecord(
                "report-1",
                "env-1",
                "Release Validation",
                ReportStatus.REVIEW,
                "2026-03-24T12:00:00Z",
                "2026-03-24T12:05:00Z",
                "EXECUTION_SUMMARY"
        );
        WorkspaceNode reportNode = new WorkspaceNode(WorkspaceNodeType.REPORT, report.getId(), report.getTitle(), report);
        WorkspaceNode executionRunNode = new WorkspaceNode(
                WorkspaceNodeType.EXECUTION_RUN,
                "run-1",
                "Smoke Cycle",
                new ExecutionRunWorkspaceNode(report, null)
        );
        WorkspaceNode projectNode = new WorkspaceNode(WorkspaceNodeType.PROJECT, "project-1", "Project", null);

        assertSame(report, ToolbarAndExportCoordinator.resolveReportSelection(reportNode));
        assertSame(report, ToolbarAndExportCoordinator.resolveReportSelection(executionRunNode));
        assertNull(ToolbarAndExportCoordinator.resolveReportSelection(projectNode));
        assertNull(ToolbarAndExportCoordinator.resolveReportSelection(null));
    }

    @Test
    void ensureHtmlExtensionAppendsOnlyWhenNeeded() {
        assertEquals(Path.of("report.html"), ToolbarAndExportCoordinator.ensureHtmlExtension(Path.of("report")));
        assertEquals(Path.of("report.html"), ToolbarAndExportCoordinator.ensureHtmlExtension(Path.of("report.html")));
        assertNull(ToolbarAndExportCoordinator.ensureHtmlExtension(null));
    }

    @Test
    void sanitizeExportFileNameNormalizesReservedCharacters() {
        assertEquals("Release_Validation", ToolbarAndExportCoordinator.sanitizeExportFileName("Release:Validation"));
        assertEquals("report-export", ToolbarAndExportCoordinator.sanitizeExportFileName("   "));
        assertEquals("report-export", ToolbarAndExportCoordinator.sanitizeExportFileName(null));
    }
}
