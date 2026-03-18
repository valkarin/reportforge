package com.buraktok.reportforge.model;

import java.util.List;

public class ExecutionRunSnapshot {
    private final ExecutionRunRecord run;
    private final List<ExecutionRunEvidenceRecord> evidences;
    private final ExecutionMetrics metrics;

    public ExecutionRunSnapshot(
            ExecutionRunRecord run,
            List<ExecutionRunEvidenceRecord> evidences,
            ExecutionMetrics metrics
    ) {
        this.run = run;
        this.evidences = List.copyOf(evidences);
        this.metrics = metrics;
    }

    public ExecutionRunRecord getRun() {
        return run;
    }

    public List<ExecutionRunEvidenceRecord> getEvidences() {
        return evidences;
    }

    public ExecutionMetrics getMetrics() {
        return metrics;
    }
}
