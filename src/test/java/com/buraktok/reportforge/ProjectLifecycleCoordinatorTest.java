package com.buraktok.reportforge;

import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ProjectSummary;
import com.buraktok.reportforge.model.ProjectWorkspace;
import com.buraktok.reportforge.persistence.ManifestData;
import com.buraktok.reportforge.persistence.ProjectSession;
import com.buraktok.reportforge.ui.WorkspaceNode;
import com.buraktok.reportforge.ui.WorkspaceNodeType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ProjectLifecycleCoordinatorTest {

    @Test
    void createProjectLoadsWorkspaceAndShowsWorkspace() throws Exception {
        FakeProjectAccess projectAccess = new FakeProjectAccess();
        FakeRecentProjectsAccess recentProjectsAccess = new FakeRecentProjectsAccess();
        FakeAutosaveScheduler autosaveScheduler = new FakeAutosaveScheduler();
        FakeSupport support = new FakeSupport();
        ProjectLifecycleCoordinator coordinator = new ProjectLifecycleCoordinator(
                projectAccess,
                recentProjectsAccess,
                autosaveScheduler,
                support
        );

        coordinator.createProject(Path.of("demo.rforge"), "Demo Project", "QA", List.of("Portal"));

        assertEquals(Path.of("demo.rforge"), projectAccess.createdProjectPath);
        assertEquals("Demo Project", projectAccess.createdProjectName);
        assertEquals("QA", projectAccess.createdInitialEnvironmentName);
        assertEquals(List.of("Portal"), projectAccess.createdApplicationNames);
        assertSame(projectAccess.workspaceToLoad, coordinator.getCurrentWorkspace());
        assertEquals(1, support.showWorkspaceCalls);
        assertEquals(1, support.selectProjectNodeCalls);
        assertEquals("Project created.", support.infoStatus);
        assertEquals(1, recentProjectsAccess.touchCalls);
        assertFalse(coordinator.isDirty());
        assertFalse(coordinator.isProjectSaveInProgress());
    }

    @Test
    void markDirtySchedulesAutosaveAndFlushSavesProject() throws Exception {
        FakeProjectAccess projectAccess = new FakeProjectAccess();
        FakeRecentProjectsAccess recentProjectsAccess = new FakeRecentProjectsAccess();
        FakeAutosaveScheduler autosaveScheduler = new FakeAutosaveScheduler();
        FakeSupport support = new FakeSupport();
        ProjectLifecycleCoordinator coordinator = new ProjectLifecycleCoordinator(
                projectAccess,
                recentProjectsAccess,
                autosaveScheduler,
                support
        );
        coordinator.createProject(Path.of("demo.rforge"), "Demo Project", "QA", List.of());

        coordinator.markDirty("Report updated.");
        coordinator.flushAutosave();

        assertEquals(1, autosaveScheduler.scheduleCalls);
        assertEquals("Report updated.", support.infoStatus);
        assertEquals(1, projectAccess.saveProjectCalls);
        assertEquals(2, recentProjectsAccess.touchCalls);
        assertFalse(coordinator.isDirty());
        assertFalse(coordinator.isProjectSaveInProgress());
        assertEquals(3, support.updateProjectTitleDisplayCalls);
    }

    @Test
    void reloadWorkspacePreservingScrollRestoresMatchedSelection() throws Exception {
        FakeProjectAccess projectAccess = new FakeProjectAccess();
        FakeRecentProjectsAccess recentProjectsAccess = new FakeRecentProjectsAccess();
        FakeAutosaveScheduler autosaveScheduler = new FakeAutosaveScheduler();
        FakeSupport support = new FakeSupport();
        ProjectLifecycleCoordinator coordinator = new ProjectLifecycleCoordinator(
                projectAccess,
                recentProjectsAccess,
                autosaveScheduler,
                support
        );
        coordinator.createProject(Path.of("demo.rforge"), "Demo Project", "QA", List.of());

        ProjectWorkspace reloadedWorkspace = workspace("Reloaded Project");
        projectAccess.workspaceToLoad = reloadedWorkspace;
        support.currentSelection = new WorkspaceNode(WorkspaceNodeType.REPORT, "report-1", "Report", null);
        support.capturedScrollVvalue = 0.72;

        coordinator.reloadWorkspaceAndReselectPreservingScroll(WorkspaceNodeType.REPORT, "report-1");

        assertSame(reloadedWorkspace, coordinator.getCurrentWorkspace());
        assertEquals(2, projectAccess.loadWorkspaceCalls);
        assertEquals(1, support.rebuildWorkspaceTreeCalls);
        assertEquals(WorkspaceNodeType.REPORT, support.lastSelectedNodeType);
        assertEquals("report-1", support.lastSelectedNodeId);
        assertEquals(0.72, support.restoredScrollVvalue);
        assertEquals(1, support.updateChromeCalls);
        assertNull(support.currentSelection);
    }

    @Test
    void closeCurrentProjectSavesAndResetsWorkspaceUi() throws Exception {
        FakeProjectAccess projectAccess = new FakeProjectAccess();
        FakeRecentProjectsAccess recentProjectsAccess = new FakeRecentProjectsAccess();
        FakeAutosaveScheduler autosaveScheduler = new FakeAutosaveScheduler();
        FakeSupport support = new FakeSupport();
        ProjectLifecycleCoordinator coordinator = new ProjectLifecycleCoordinator(
                projectAccess,
                recentProjectsAccess,
                autosaveScheduler,
                support
        );
        coordinator.createProject(Path.of("demo.rforge"), "Demo Project", "QA", List.of());

        coordinator.closeCurrentProject();

        assertEquals(1, autosaveScheduler.cancelCalls);
        assertEquals(1, projectAccess.saveProjectCalls);
        assertEquals(1, projectAccess.closeCurrentSessionCalls);
        assertEquals(2, recentProjectsAccess.touchCalls);
        assertEquals(1, support.hideProjectTitleMenuCalls);
        assertEquals(1, support.clearWorkspaceViewCalls);
        assertEquals(1, support.updateChromeCalls);
        assertEquals(1, support.reopenStartScreenCalls);
        assertNull(coordinator.getCurrentWorkspace());
        assertFalse(coordinator.isDirty());
        assertFalse(coordinator.isProjectSaveInProgress());
    }

    @Test
    void openProjectMissingRecentEntryRemovesItAndShowsError() {
        FakeProjectAccess projectAccess = new FakeProjectAccess();
        projectAccess.openProjectIOException = new IOException("File not found.");
        FakeRecentProjectsAccess recentProjectsAccess = new FakeRecentProjectsAccess();
        FakeAutosaveScheduler autosaveScheduler = new FakeAutosaveScheduler();
        FakeSupport support = new FakeSupport();
        ProjectLifecycleCoordinator coordinator = new ProjectLifecycleCoordinator(
                projectAccess,
                recentProjectsAccess,
                autosaveScheduler,
                support
        );

        coordinator.openProject(Path.of("missing.rforge"), true);

        assertEquals(Path.of("missing.rforge"), recentProjectsAccess.removedProjectPath);
        assertEquals(1, support.reopenStartScreenCalls);
        assertEquals(1, support.hideProjectTitleMenuCalls);
        assertEquals(1, support.clearWorkspaceViewCalls);
        assertEquals("Unable to open project", support.errorTitle);
        assertEquals("File not found.", support.errorMessage);
        assertNull(coordinator.getCurrentWorkspace());
    }

    private static ProjectWorkspace workspace(String projectName) {
        EnvironmentRecord environment = new EnvironmentRecord("env-1", "QA", "", "", "", "", "", "", 0);
        return new ProjectWorkspace(
                new ProjectSummary("project-1", projectName, "", "2026-03-26T00:00:00Z", "2026-03-26T00:00:00Z"),
                List.of(),
                List.of(environment),
                Map.of(environment.getId(), List.of())
        );
    }

    private static ProjectSession session(Path projectPath, String projectName) {
        return new ProjectSession(
                projectPath,
                Path.of("workspace"),
                new ManifestData("1", "0.1.0", "project-1", projectName, "data/project.db", "2026-03-26T00:00:00Z", "2026-03-26T00:00:00Z")
        );
    }

    private static final class FakeProjectAccess implements ProjectLifecycleCoordinator.ProjectAccess {
        private Path createdProjectPath;
        private String createdProjectName;
        private String createdInitialEnvironmentName;
        private List<String> createdApplicationNames = List.of();
        private ProjectWorkspace workspaceToLoad = workspace("Demo Project");
        private ProjectSession currentSession = session(Path.of("demo.rforge"), "Demo Project");
        private IOException openProjectIOException;
        private SQLException openProjectSQLException;
        private int loadWorkspaceCalls;
        private int saveProjectCalls;
        private int closeCurrentSessionCalls;

        @Override
        public void createProject(Path projectPath, String projectName, String initialEnvironmentName, List<String> applicationNames) {
            createdProjectPath = projectPath;
            createdProjectName = projectName;
            createdInitialEnvironmentName = initialEnvironmentName;
            createdApplicationNames = List.copyOf(applicationNames);
            workspaceToLoad = workspace(projectName);
            currentSession = session(projectPath, projectName);
        }

        @Override
        public void openProject(Path projectPath) throws IOException, SQLException {
            if (openProjectIOException != null) {
                throw openProjectIOException;
            }
            if (openProjectSQLException != null) {
                throw openProjectSQLException;
            }
            currentSession = session(projectPath, workspaceToLoad.getProject().getName());
        }

        @Override
        public ProjectWorkspace loadWorkspace() {
            loadWorkspaceCalls++;
            return workspaceToLoad;
        }

        @Override
        public void saveProject() {
            saveProjectCalls++;
        }

        @Override
        public ProjectSession getCurrentSession() {
            return currentSession;
        }

        @Override
        public void closeCurrentSession() {
            closeCurrentSessionCalls++;
            currentSession = null;
        }
    }

    private static final class FakeRecentProjectsAccess implements ProjectLifecycleCoordinator.RecentProjectsAccess {
        private int touchCalls;
        private Path removedProjectPath;

        @Override
        public void touchProject(Path projectPath, String name) {
            touchCalls++;
        }

        @Override
        public void removeProject(Path projectPath) {
            removedProjectPath = projectPath;
        }
    }

    private static final class FakeAutosaveScheduler implements ProjectLifecycleCoordinator.AutosaveScheduler {
        private int scheduleCalls;
        private int cancelCalls;

        @Override
        public void schedule() {
            scheduleCalls++;
        }

        @Override
        public void cancel() {
            cancelCalls++;
        }
    }

    private static final class FakeSupport implements ProjectLifecycleCoordinator.Support {
        private WorkspaceNode currentSelection;
        private double capturedScrollVvalue = Double.NaN;
        private double restoredScrollVvalue = Double.NaN;
        private int rebuildWorkspaceTreeCalls;
        private int selectProjectNodeCalls;
        private int showWorkspaceCalls;
        private int reopenStartScreenCalls;
        private int hideProjectTitleMenuCalls;
        private int clearWorkspaceViewCalls;
        private int updateChromeCalls;
        private int updateProjectTitleDisplayCalls;
        private WorkspaceNodeType lastSelectedNodeType;
        private String lastSelectedNodeId;
        private String infoStatus;
        private String errorTitle;
        private String errorMessage;

        @Override
        public WorkspaceNode getCurrentSelection() {
            return currentSelection;
        }

        @Override
        public void clearCurrentSelection() {
            currentSelection = null;
        }

        @Override
        public double captureCenterScrollVvalue() {
            return capturedScrollVvalue;
        }

        @Override
        public void rebuildWorkspaceTree() {
            rebuildWorkspaceTreeCalls++;
        }

        @Override
        public void selectNode(WorkspaceNodeType nodeType, String nodeId) {
            lastSelectedNodeType = nodeType;
            lastSelectedNodeId = nodeId;
        }

        @Override
        public void restoreCenterScrollVvalue(double scrollVvalue) {
            restoredScrollVvalue = scrollVvalue;
        }

        @Override
        public void selectProjectNode() {
            selectProjectNodeCalls++;
        }

        @Override
        public void showWorkspace() {
            showWorkspaceCalls++;
        }

        @Override
        public void reopenStartScreen() {
            reopenStartScreenCalls++;
        }

        @Override
        public void hideProjectTitleMenu() {
            hideProjectTitleMenuCalls++;
        }

        @Override
        public void clearWorkspaceView() {
            clearWorkspaceViewCalls++;
        }

        @Override
        public void updateChrome() {
            updateChromeCalls++;
        }

        @Override
        public void updateProjectTitleDisplay() {
            updateProjectTitleDisplayCalls++;
        }

        @Override
        public void setInfoStatus(String message) {
            infoStatus = message;
        }

        @Override
        public void showError(String title, String message, Throwable exception) {
            errorTitle = title;
            errorMessage = message;
        }
    }
}
