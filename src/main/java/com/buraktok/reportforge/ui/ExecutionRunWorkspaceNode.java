package com.buraktok.reportforge.ui;

import com.buraktok.reportforge.model.ExecutionRunSnapshot;
import com.buraktok.reportforge.model.ReportRecord;

public record ExecutionRunWorkspaceNode(ReportRecord report, ExecutionRunSnapshot runSnapshot) {
}
