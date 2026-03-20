package com.buraktok.reportforge.ui;

import com.buraktok.reportforge.model.ExecutionReportSnapshot;
import com.buraktok.reportforge.model.ExecutionRunSnapshot;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ProjectWorkspace;
import com.buraktok.reportforge.model.ReportRecord;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;

public final class WorkspaceNavigator {
    private final WorkspaceHost host;
    private final WorkspaceContentFactory contentFactory;

    private TreeView<WorkspaceNode> workspaceTree;
    private BorderPane workspaceCenterPane;
    private SplitPane workspaceSplitPane;

    public WorkspaceNavigator(WorkspaceHost host, WorkspaceContentFactory contentFactory) {
        this.host = host;
        this.contentFactory = contentFactory;
    }

    public Node build() {
        if (workspaceSplitPane == null) {
            initialize();
        }
        rebuildTree();
        return workspaceSplitPane;
    }

    public void rebuildTree() {
        ProjectWorkspace currentWorkspace = host.getCurrentWorkspace();
        if (workspaceTree == null || currentWorkspace == null) {
            return;
        }

        try {
            WorkspaceNode projectNode = new WorkspaceNode(
                    WorkspaceNodeType.PROJECT,
                    currentWorkspace.getProject().getId(),
                    currentWorkspace.getProject().getName(),
                    currentWorkspace.getProject()
            );
            TreeItem<WorkspaceNode> rootItem = new TreeItem<>(projectNode);
            rootItem.setExpanded(true);

            WorkspaceNode applicationsNode = new WorkspaceNode(
                    WorkspaceNodeType.APPLICATIONS,
                    "project-applications",
                    "Applications under Test",
                    currentWorkspace.getProjectApplications()
            );
            rootItem.getChildren().add(new TreeItem<>(applicationsNode));

            for (EnvironmentRecord environment : currentWorkspace.getEnvironments()) {
                WorkspaceNode environmentNode = new WorkspaceNode(
                        WorkspaceNodeType.ENVIRONMENT,
                        environment.getId(),
                        environment.getName(),
                        environment
                );
                TreeItem<WorkspaceNode> environmentItem = new TreeItem<>(environmentNode);
                environmentItem.setExpanded(true);

                for (ReportRecord report : currentWorkspace.getReportsForEnvironment(environment.getId())) {
                    TreeItem<WorkspaceNode> reportItem = new TreeItem<>(
                            new WorkspaceNode(WorkspaceNodeType.REPORT, report.getId(), report.getTitle(), report)
                    );
                    reportItem.setExpanded(true);

                    ExecutionReportSnapshot executionSnapshot = host.getProjectService().loadExecutionReportSnapshot(report.getId());
                    for (ExecutionRunSnapshot runSnapshot : executionSnapshot.getRuns()) {
                        reportItem.getChildren().add(new TreeItem<>(
                                new WorkspaceNode(
                                        WorkspaceNodeType.EXECUTION_RUN,
                                        runSnapshot.getRun().getId(),
                                        runSnapshot.getRun().getDisplayLabel(),
                                        new ExecutionRunWorkspaceNode(report, runSnapshot)
                                )
                        ));
                    }

                    environmentItem.getChildren().add(reportItem);
                }
                rootItem.getChildren().add(environmentItem);
            }

            workspaceTree.setRoot(rootItem);
        } catch (Exception exception) {
            host.showError("Unable to refresh workspace tree", exception.getMessage());
        }
    }

    public void selectProjectNode() {
        if (workspaceTree != null && workspaceTree.getRoot() != null) {
            workspaceTree.getSelectionModel().select(workspaceTree.getRoot());
        }
    }

    public void selectApplicationsNode() {
        selectNode(WorkspaceNodeType.APPLICATIONS, "project-applications");
    }

    public void selectNode(WorkspaceNodeType nodeType, String nodeId) {
        if (workspaceTree == null) {
            return;
        }
        TreeItem<WorkspaceNode> treeItem = findTreeItem(workspaceTree.getRoot(), nodeType, nodeId);
        if (treeItem != null) {
            workspaceTree.getSelectionModel().select(treeItem);
        }
    }

    public void updateNode(WorkspaceNode updatedNode) {
        if (workspaceTree == null || updatedNode == null) {
            return;
        }
        TreeItem<WorkspaceNode> treeItem = findTreeItem(workspaceTree.getRoot(), updatedNode.type(), updatedNode.id());
        if (treeItem == null) {
            return;
        }
        treeItem.setValue(updatedNode);
        workspaceTree.refresh();
        if (workspaceTree.getSelectionModel().getSelectedItem() == treeItem) {
            host.onWorkspaceSelectionChanged(updatedNode);
        }
    }

    private void initialize() {
        workspaceTree = new TreeView<>();
        workspaceTree.setShowRoot(true);
        workspaceTree.getStyleClass().add("workspace-tree");
        workspaceTree.setCellFactory(treeView -> new WorkspaceTreeCell(host));
        workspaceTree.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (selected == null) {
                return;
            }
            host.onWorkspaceSelectionChanged(selected.getValue());
            showSelectionDetails(selected.getValue());
        });

        workspaceCenterPane = new BorderPane();
        workspaceCenterPane.setPadding(Insets.EMPTY);
        workspaceCenterPane.getStyleClass().add("workspace-center-pane");

        workspaceSplitPane = new SplitPane();
        workspaceSplitPane.setOrientation(Orientation.HORIZONTAL);
        workspaceSplitPane.getStyleClass().add("workspace-split-pane");
        workspaceSplitPane.getItems().setAll(workspaceTree, workspaceCenterPane);
        workspaceSplitPane.setDividerPositions(0.24);
    }

    private void showSelectionDetails(WorkspaceNode selection) {
        if (selection == null) {
            workspaceCenterPane.setCenter(new Label("Select a node from the workspace tree."));
            return;
        }

        try {
            workspaceCenterPane.setCenter(contentFactory.buildContent(selection));
        } catch (Exception exception) {
            host.showError("Unable to load selection", exception.getMessage());
        }
    }

    private TreeItem<WorkspaceNode> findTreeItem(TreeItem<WorkspaceNode> rootItem, WorkspaceNodeType nodeType, String nodeId) {
        if (rootItem == null) {
            return null;
        }
        WorkspaceNode node = rootItem.getValue();
        if (node != null && node.type() == nodeType && java.util.Objects.equals(node.id(), nodeId)) {
            return rootItem;
        }
        for (TreeItem<WorkspaceNode> child : rootItem.getChildren()) {
            TreeItem<WorkspaceNode> match = findTreeItem(child, nodeType, nodeId);
            if (match != null) {
                return match;
            }
        }
        return null;
    }
}
