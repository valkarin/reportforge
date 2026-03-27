package com.buraktok.reportforge.ui;

import com.buraktok.reportforge.model.ExecutionRunRecord;
import com.buraktok.reportforge.model.ReportRecord;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeCell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

public final class WorkspaceTreeCell extends TreeCell<WorkspaceNode> {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceTreeCell.class);
    private final WorkspaceHost host;

    public WorkspaceTreeCell(WorkspaceHost host) {
        this.host = host;
        setOnContextMenuRequested(event -> {
            if (getTreeItem() != null && getTreeItem().getValue() != null && getTreeView() != null) {
                getTreeView().getSelectionModel().select(getTreeItem());
            }
        });
    }

    @Override
    protected void updateItem(WorkspaceNode item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setText(null);
            setContextMenu(null);
            return;
        }

        setText(item.label());
        setContextMenu(createContextMenu(item));
    }

    private ContextMenu createContextMenu(WorkspaceNode node) {
        return switch (node.type()) {
            case REPORT -> createReportContextMenu((ReportRecord) node.payload());
            case EXECUTION_RUN -> createExecutionRunContextMenu((ExecutionRunWorkspaceNode) node.payload());
            default -> null;
        };
    }

    private ContextMenu createReportContextMenu(ReportRecord report) {
        MenuItem addRunItem = UiSupport.createMenuItem("Add Run", "fas-plus", () -> addRun(report));
        MenuItem duplicateReportItem = UiSupport.createMenuItem("Duplicate", "far-copy", () -> host.duplicateReport(report));
        MenuItem moveReportItem = UiSupport.createMenuItem("Move", "fas-exchange-alt", () -> host.moveReport(report));
        MenuItem copyReportItem = UiSupport.createMenuItem("Copy To", "fas-copy", () -> host.copyReport(report));
        MenuItem deleteReportItem = UiSupport.createMenuItem("Delete Report", "fas-trash", () -> host.deleteReport(report));

        return UiSupport.themedContextMenu(
                host,
                addRunItem,
                new SeparatorMenuItem(),
                duplicateReportItem,
                moveReportItem,
                copyReportItem,
                new SeparatorMenuItem(),
                deleteReportItem
        );
    }

    private ContextMenu createExecutionRunContextMenu(ExecutionRunWorkspaceNode runNode) {
        MenuItem addRunItem = UiSupport.createMenuItem("Add Run", "fas-plus", () -> addRun(runNode.report()));
        MenuItem removeRunItem = UiSupport.createMenuItem("Remove Run", "fas-trash", () -> removeRun(runNode));

        return UiSupport.themedContextMenu(host, addRunItem, new SeparatorMenuItem(), removeRunItem);
    }

    private void addRun(ReportRecord report) {
        try {
            ExecutionRunRecord createdRun = host.getProjectService().createExecutionRun(report.getId());
            host.reloadWorkspaceAndReselect(WorkspaceNodeType.EXECUTION_RUN, createdRun.getId());
            host.markDirty("Execution run added.");
        } catch (SQLException | IllegalStateException exception) {
            showOperationError("Unable to add execution run", exception);
        }
    }

    private void removeRun(ExecutionRunWorkspaceNode runNode) {
        try {
            host.getProjectService().deleteExecutionRun(
                    runNode.report().getId(),
                    runNode.runSnapshot().getRun().getId()
            );
            host.reloadWorkspaceAndReselect(WorkspaceNodeType.REPORT, runNode.report().getId());
            host.markDirty("Execution run removed.");
        } catch (SQLException | IllegalStateException exception) {
            showOperationError("Unable to remove execution run", exception);
        }
    }

    private void showOperationError(String title, Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = "Operation failed.";
        }
        LOGGER.error("{}: {}", title, message, exception);
        host.showError(title, message);
    }
}
