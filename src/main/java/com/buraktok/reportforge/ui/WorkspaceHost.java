package com.buraktok.reportforge.ui;

import com.buraktok.reportforge.model.ApplicationEntry;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ProjectWorkspace;
import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.ReportStatus;
import com.buraktok.reportforge.persistence.ProjectContainerService;

public interface WorkspaceHost extends WindowContext {
    ProjectWorkspace getCurrentWorkspace();

    ProjectContainerService getProjectService();

    void onWorkspaceSelectionChanged(WorkspaceNode selection);

    void reloadWorkspaceAndReselect(WorkspaceNodeType nodeType, String nodeId);

    void selectNode(WorkspaceNodeType nodeType, String nodeId);

    void selectApplicationsNode();

    void markDirty(String message);

    void showInformation(String title, String message);

    void showError(String title, String message);

    void addProjectApplication();

    void editProjectApplication(ApplicationEntry selected);

    void createReportForEnvironment(EnvironmentRecord environment);

    void handleReportStatusChange(ReportRecord report, ReportStatus requestedStatus);
}
