package com.buraktok.reportforge.ui;

import javafx.scene.control.TreeCell;

public final class WorkspaceTreeCell extends TreeCell<WorkspaceNode> {
    @Override
    protected void updateItem(WorkspaceNode item, boolean empty) {
        super.updateItem(item, empty);
        setText(empty || item == null ? null : item.label());
    }
}
