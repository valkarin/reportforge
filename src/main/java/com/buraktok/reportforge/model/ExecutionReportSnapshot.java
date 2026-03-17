package com.buraktok.reportforge.model;

import java.util.List;

public class ExecutionReportSnapshot {
    private final String reportId;
    private final List<ExecutionRunSnapshot> runs;
    private final ExecutionMetrics metrics;

    public ExecutionReportSnapshot(String reportId, List<ExecutionRunSnapshot> runs, ExecutionMetrics metrics) {
        this.reportId = reportId;
        this.runs = List.copyOf(runs);
        this.metrics = metrics;
    }

    public String getReportId() {
        return reportId;
    }

    public List<ExecutionRunSnapshot> getRuns() {
        return runs;
    }

    public ExecutionMetrics getMetrics() {
        return metrics;
    }
}
