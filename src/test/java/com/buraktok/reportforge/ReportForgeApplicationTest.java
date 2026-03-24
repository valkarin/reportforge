package com.buraktok.reportforge;

import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.ReportStatus;
import com.buraktok.reportforge.ui.ExecutionRunWorkspaceNode;
import com.buraktok.reportforge.ui.WorkspaceNode;
import com.buraktok.reportforge.ui.WorkspaceNodeType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReportForgeApplicationTest {

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

        assertAll(
                () -> assertSame(report, ReportForgeApplication.resolveReportSelection(reportNode)),
                () -> assertSame(report, ReportForgeApplication.resolveReportSelection(executionRunNode)),
                () -> assertNull(ReportForgeApplication.resolveReportSelection(projectNode)),
                () -> assertNull(ReportForgeApplication.resolveReportSelection(null))
        );
    }
}
