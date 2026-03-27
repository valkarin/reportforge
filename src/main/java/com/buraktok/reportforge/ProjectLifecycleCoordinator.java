package com.buraktok.reportforge;

import com.buraktok.reportforge.model.ProjectWorkspace;
import com.buraktok.reportforge.persistence.ProjectSession;
import com.buraktok.reportforge.ui.WorkspaceNode;
import com.buraktok.reportforge.ui.WorkspaceNodeType;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Manages the high-level lifecycle of a project including opening, closing, and saving.
 */
final class ProjectLifecycleCoordinator {
    /**
     * Provides access to project persistence operations.
     */
    interface ProjectAccess {
        /**
         * Creates a new project at the specified path.
         *
         * @param projectPath          the path to create the project at
         * @param projectName          the name of the project
         * @param initialEnvironmentName the initial environment name
         * @param applicationNames     the list of applications 
         * @throws IOException  if an IO error occurs
         * @throws SQLException if a database error occurs
         */
        void createProject(Path projectPath, String projectName, String initialEnvironmentName, List<String> applicationNames)
                throws IOException, SQLException;

        /**
         * Opens an existing project from the specified path.
         *
         * @param projectPath the path to open the project from
         * @throws IOException  if an IO error occurs
         * @throws SQLException if a database error occurs
         */
        void openProject(Path projectPath) throws IOException, SQLException;

        /**
         * Loads the workspace data for the current project.
         *
         * @return the loaded project workspace
         * @throws SQLException if a database error occurs
         */
        ProjectWorkspace loadWorkspace() throws SQLException;

        /**
         * Saves the current project state to disk.
         *
         * @throws IOException  if an IO error occurs
         * @throws SQLException if a database error occurs
         */
        void saveProject() throws IOException, SQLException;

        /**
         * Gets the current active project session.
         *
         * @return the current project session
         */
        ProjectSession getCurrentSession();

        /**
         * Closes the current active project session.
         */
        void closeCurrentSession();
    }

    /**
     * Provides access to recent projects history.
     */
    interface RecentProjectsAccess {
        /**
         * Marks a project as recently opened.
         *
         * @param projectPath the path of the project
         * @param name        the name of the project
         */
        void touchProject(Path projectPath, String name);

        /**
         * Removes a project from the recent projects history.
         *
         * @param projectPath the path of the project to remove
         */
        void removeProject(Path projectPath);
    }

    /**
     * Schedules periodic autosave operations.
     */
    interface AutosaveScheduler {
        /**
         * Schedules an autosave operation.
         */
        void schedule();

        /**
         * Cancels any pending autosave operations.
         */
        void cancel();
    }

    /**
     * Provides UI support operations for the lifecycle coordinator.
     */
    interface Support {
        /**
         * Gets the currently selected node in the workspace.
         *
         * @return the selected workspace node
         */
        WorkspaceNode getCurrentSelection();

        /**
         * Clears the current selection in the workspace.
         */
        void clearCurrentSelection();

        /**
         * Captures the vertical scroll position in the center view.
         *
         * @return the vertical scroll value
         */
        double captureCenterScrollVvalue();

        /**
         * Rebuilds the workspace tree view.
         */
        void rebuildWorkspaceTree();

        /**
         * Selects a specific node in the workspace tree.
         *
         * @param nodeType the type of the node
         * @param nodeId   the ID of the node
         */
        void selectNode(WorkspaceNodeType nodeType, String nodeId);

        /**
         * Restores the vertical scroll position in the center view.
         *
         * @param scrollVvalue the scroll value to restore
         */
        void restoreCenterScrollVvalue(double scrollVvalue);

        /**
         * Selects the root project node in the workspace tree.
         */
        void selectProjectNode();

        /**
         * Shows the main workspace UI layout.
         */
        void showWorkspace();

        /**
         * Shows the initial start screen layout.
         */
        void reopenStartScreen();

        /**
         * Hides the project title menu.
         */
        void hideProjectTitleMenu();

        /**
         * Clears the current view in the workspace center.
         */
        void clearWorkspaceView();

        /**
         * Updates the UI chrome and layout structure.
         */
        void updateChrome();

        /**
         * Updates the displayed project title.
         */
        void updateProjectTitleDisplay();

        /**
         * Sets an informational status message.
         *
         * @param message the message to display
         */
        void setInfoStatus(String message);

        /**
         * Displays an error dialog to the user.
         *
         * @param title     the title of the dialog
         * @param message   the error message
         * @param exception the underlying exception
         */
        void showError(String title, String message, Throwable exception);
    }

    private final ProjectAccess projectAccess;
    private final RecentProjectsAccess recentProjectsAccess;
    private final AutosaveScheduler autosaveScheduler;
    private final Support support;

    private ProjectWorkspace currentWorkspace;
    private boolean dirty;
    private boolean projectSaveInProgress;

    /**
     * Constructs a new project lifecycle coordinator.
     *
     * @param projectAccess        the project persistence access provider
     * @param recentProjectsAccess the recent projects access provider
     * @param autosaveScheduler    the autosave scheduler provider
     * @param support              the UI support provider
     */
    ProjectLifecycleCoordinator(
            ProjectAccess projectAccess,
            RecentProjectsAccess recentProjectsAccess,
            AutosaveScheduler autosaveScheduler,
            Support support
    ) {
        this.projectAccess = projectAccess;
        this.recentProjectsAccess = recentProjectsAccess;
        this.autosaveScheduler = autosaveScheduler;
        this.support = support;
    }

    /**
     * Gets the current project workspace.
     *
     * @return the project workspace
     */
    ProjectWorkspace getCurrentWorkspace() {
        return currentWorkspace;
    }

    /**
     * Checks if the project has unsaved changes.
     *
     * @return true if there are unsaved changes
     */
    boolean isDirty() {
        return dirty;
    }

    /**
     * Checks if a save operation is currently in progress.
     *
     * @return true if a save is in progress
     */
    boolean isProjectSaveInProgress() {
        return projectSaveInProgress;
    }

    /**
     * Creates a new project and opens it in the workspace.
     *
     * @param projectPath          the path to create the project at
     * @param projectName          the name of the project
     * @param initialEnvironmentName the initial environment name
     * @param applicationNames     the list of applications
     * @throws IOException  if an IO error occurs
     * @throws SQLException if a database error occurs
     */
    void createProject(Path projectPath, String projectName, String initialEnvironmentName, List<String> applicationNames)
            throws IOException, SQLException {
        projectAccess.createProject(projectPath, projectName, initialEnvironmentName, applicationNames);
        currentWorkspace = projectAccess.loadWorkspace();
        support.clearCurrentSelection();
        dirty = false;
        projectSaveInProgress = false;
        touchCurrentProject();
        support.showWorkspace();
        support.selectProjectNode();
        support.setInfoStatus("Project created.");
    }

    /**
     * Opens an existing project and loads it into the workspace.
     *
     * @param projectPath       the path to open the project from
     * @param removeWhenMissing whether to remove from recent projects if missing
     */
    void openProject(Path projectPath, boolean removeWhenMissing) {
        try {
            projectAccess.openProject(projectPath);
            currentWorkspace = projectAccess.loadWorkspace();
            support.clearCurrentSelection();
            dirty = false;
            projectSaveInProgress = false;
            touchCurrentProject();
            support.showWorkspace();
            support.selectProjectNode();
            support.setInfoStatus("Project opened.");
        } catch (IOException exception) {
            if (removeWhenMissing && "File not found.".equals(exception.getMessage())) {
                recentProjectsAccess.removeProject(projectPath);
                resetCurrentProjectState();
                support.reopenStartScreen();
            }
            support.showError("Unable to open project", normalizeOpenProjectMessage(exception.getMessage()), exception);
        } catch (SQLException | IllegalStateException exception) {
            support.showError("Unable to open project", normalizeOpenProjectMessage(exception.getMessage()), exception);
        }
    }

    /**
     * Reloads the workspace from disk and reselects the specified node.
     *
     * @param nodeType the type of the node to select
     * @param nodeId   the ID of the node to select
     */
    void reloadWorkspaceAndReselect(WorkspaceNodeType nodeType, String nodeId) {
        reloadWorkspaceAndReselect(nodeType, nodeId, false);
    }

    /**
     * Reloads the workspace from disk, reselects the specified node, and preserves scroll state.
     *
     * @param nodeType the type of the node to select
     * @param nodeId   the ID of the node to select
     */
    void reloadWorkspaceAndReselectPreservingScroll(WorkspaceNodeType nodeType, String nodeId) {
        reloadWorkspaceAndReselect(nodeType, nodeId, true);
    }

    /**
     * Internal method to reload the workspace and optionally preserve the scroll position.
     *
     * @param nodeType              the type of the node to select
     * @param nodeId                the ID of the node to select
     * @param preserveContentScroll whether to preserve the scroll position
     */
    private void reloadWorkspaceAndReselect(WorkspaceNodeType nodeType, String nodeId, boolean preserveContentScroll) {
        double preservedScrollVvalue = Double.NaN;
        WorkspaceNode selection = support.getCurrentSelection();
        if (preserveContentScroll
                && selection != null
                && selection.type() == nodeType
                && Objects.equals(selection.id(), nodeId)) {
            preservedScrollVvalue = support.captureCenterScrollVvalue();
        }
        try {
            support.clearCurrentSelection();
            currentWorkspace = projectAccess.loadWorkspace();
            support.rebuildWorkspaceTree();
            support.selectNode(nodeType, nodeId);
            support.updateChrome();
            support.restoreCenterScrollVvalue(preservedScrollVvalue);
        } catch (SQLException | IllegalStateException exception) {
            showOperationError("Unable to refresh workspace", exception, "Unable to refresh workspace.");
        }
    }

    /**
     * Flushes any pending autosaved changes to disk immediately.
     */
    void flushAutosave() {
        if (currentWorkspace == null || projectAccess.getCurrentSession() == null || !dirty || projectSaveInProgress) {
            return;
        }
        try {
            projectSaveInProgress = true;
            support.updateProjectTitleDisplay();
            projectAccess.saveProject();
            dirty = false;
            projectSaveInProgress = false;
            touchCurrentProject();
            support.updateProjectTitleDisplay();
        } catch (IOException | SQLException | IllegalStateException exception) {
            projectSaveInProgress = false;
            support.setInfoStatus("Autosave failed.");
            support.updateProjectTitleDisplay();
            showOperationError("Unable to save project", exception, "Unable to save project.");
        }
    }

    /**
     * Closes the current project, saves changes if necessary, and returns to the start screen.
     */
    void closeCurrentProject() {
        if (currentWorkspace == null || projectAccess.getCurrentSession() == null || projectSaveInProgress) {
            return;
        }

        autosaveScheduler.cancel();
        try {
            projectSaveInProgress = true;
            support.updateProjectTitleDisplay();
            projectAccess.saveProject();
            projectSaveInProgress = false;
            touchCurrentProject();
            resetCurrentProjectState();
            support.reopenStartScreen();
        } catch (IOException | SQLException | IllegalStateException exception) {
            projectSaveInProgress = false;
            support.setInfoStatus("Unable to close project.");
            support.updateProjectTitleDisplay();
            showOperationError("Unable to close project", exception, "Unable to close project.");
        }
    }

    /**
     * Marks the current workspace as dirty and schedules an autosave.
     *
     * @param message the informational message to display
     */
    void markDirty(String message) {
        dirty = true;
        support.setInfoStatus(message);
        support.updateProjectTitleDisplay();
        autosaveScheduler.schedule();
    }

    /**
     * shuts down the coordinator, flushing any pending saves before closing the session.
     */
    void shutdown() {
        try {
            if (dirty) {
                flushAutosave();
            }
        } finally {
            projectAccess.closeCurrentSession();
        }
    }

    /**
     * Updates the recently opened list for the currently loaded project.
     */
    private void touchCurrentProject() {
        ProjectSession session = projectAccess.getCurrentSession();
        if (currentWorkspace != null && session != null) {
            recentProjectsAccess.touchProject(session.projectFile(), currentWorkspace.getProject().getName());
        }
    }

    /**
     * Resets the UI and state variables back to an empty workspace.
     */
    private void resetCurrentProjectState() {
        support.hideProjectTitleMenu();
        projectAccess.closeCurrentSession();
        currentWorkspace = null;
        support.clearCurrentSelection();
        dirty = false;
        projectSaveInProgress = false;
        support.clearWorkspaceView();
        support.updateChrome();
    }

    /**
     * Displays a consistent error message dialog for project operations.
     *
     * @param title           the title of the error dialog
     * @param exception       the underlying exception
     * @param fallbackMessage the fallback message if the exception message is empty
     */
    private void showOperationError(String title, Exception exception, String fallbackMessage) {
        support.showError(title, normalizeOperationMessage(exception.getMessage(), fallbackMessage), exception);
    }

    /**
     * Normalizes an exception message specific to opening projects.
     *
     * @param message the raw exception message
     * @return the normalized message
     */
    private String normalizeOpenProjectMessage(String message) {
        return normalizeOperationMessage(message, "Failed to read project data.");
    }

    /**
     * Normalizes a general exception message with a fallback value.
     *
     * @param message         the primary error message
     * @param fallbackMessage the default string to use if message is blank
     * @return the normalized error message to display
     */
    private String normalizeOperationMessage(String message, String fallbackMessage) {
        if (message == null || message.isBlank()) {
            return fallbackMessage;
        }
        return message;
    }
}
