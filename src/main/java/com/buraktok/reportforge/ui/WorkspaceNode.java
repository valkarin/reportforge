package com.buraktok.reportforge.ui;

public record WorkspaceNode(WorkspaceNodeType type, String id, String label, Object payload) {
    public WorkspaceNode {
        label = label == null ? "" : label;
    }
}
