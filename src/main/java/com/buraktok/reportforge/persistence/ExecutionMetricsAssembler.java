package com.buraktok.reportforge.persistence;

import com.buraktok.reportforge.model.ExecutionMetrics;
import com.buraktok.reportforge.model.ExecutionRunEvidenceRecord;
import com.buraktok.reportforge.model.ExecutionRunRecord;
import com.buraktok.reportforge.model.ExecutionRunSnapshot;

import java.util.LinkedHashSet;
import java.util.List;

import static com.buraktok.reportforge.persistence.PersistenceSupport.aggregateOutcome;
import static com.buraktok.reportforge.persistence.PersistenceSupport.firstNonBlank;
import static com.buraktok.reportforge.persistence.PersistenceSupport.normalizeOutcome;
import static com.buraktok.reportforge.persistence.PersistenceSupport.normalizeResultStatus;
import static com.buraktok.reportforge.persistence.PersistenceSupport.nullableText;
import static com.buraktok.reportforge.persistence.PersistenceSupport.selectBoundaryDate;

/**
 * Utility responsible for evaluating individual execution runs to compile testing metrics.
 */
final class ExecutionMetricsAssembler {

    /**
     * Translates a singular execution run into standardized metrics based on its status.
     *
     * @param run       the execution run model
     * @param evidences the list of attached evidence files
     * @return the calculated metrics reflecting the run's outcome
     */
    ExecutionMetrics buildRunMetrics(ExecutionRunRecord run, List<ExecutionRunEvidenceRecord> evidences) {
        if (!nullableText(run.getStatus()).isBlank()) {
            return buildSingleRunMetrics(
                    normalizeRunStatus(run.getStatus()),
                    countRelatedIssues(run),
                    evidences.size(),
                    firstNonBlank(run.getStartDate(), run.getExecutionDate(), run.getEndDate()),
                    firstNonBlank(run.getEndDate(), run.getExecutionDate(), run.getStartDate())
            );
        }

        return buildSingleRunMetrics(
                "NOT_RUN",
                countRelatedIssues(run),
                evidences.size(),
                firstNonBlank(run.getStartDate(), run.getExecutionDate(), run.getEndDate()),
                firstNonBlank(run.getEndDate(), run.getExecutionDate(), run.getStartDate())
        );
    }

    /**
     * Aggregates metrics from multiple execution runs into a single consolidated report metric.
     *
     * @param runs the list of execution run snapshots
     * @return the aggregated execution metrics
     */
    ExecutionMetrics buildReportMetrics(List<ExecutionRunSnapshot> runs) {
        int executionRunCount = runs.size();
        int testCaseCount = 0;
        int passed = 0;
        int failed = 0;
        int blocked = 0;
        int notRun = 0;
        int deferred = 0;
        int skipped = 0;
        int issues = 0;
        int evidence = 0;
        LinkedHashSet<String> outcomes = new LinkedHashSet<>();
        String earliestDate = "";
        String latestDate = "";

        for (ExecutionRunSnapshot run : runs) {
            ExecutionMetrics metrics = run.getMetrics();
            testCaseCount += metrics.getTestCaseCount();
            passed += metrics.getPassedCount();
            failed += metrics.getFailedCount();
            blocked += metrics.getBlockedCount();
            notRun += metrics.getNotRunCount();
            deferred += metrics.getDeferredCount();
            skipped += metrics.getSkippedCount();
            issues += metrics.getIssueCount();
            evidence += metrics.getEvidenceCount();
            outcomes.add(normalizeOutcome(metrics.getOverallOutcome()));
            earliestDate = selectBoundaryDate(earliestDate, metrics.getEarliestDate(), true);
            latestDate = selectBoundaryDate(latestDate, metrics.getLatestDate(), false);
        }

        return new ExecutionMetrics(
                executionRunCount,
                testCaseCount,
                passed,
                failed,
                blocked,
                notRun,
                deferred,
                skipped,
                issues,
                evidence,
                aggregateOutcome(outcomes),
                earliestDate,
                latestDate
        );
    }

    /**
     * Normalizes a raw execution status string into a standardized category.
     *
     * @param status the raw status string
     * @return the normalized status string
     */
    static String normalizeRunStatus(String status) {
        String normalizedStatus = normalizeResultStatus(status);
        return "NOT_EXECUTED".equals(normalizedStatus) ? "NOT_RUN" : normalizedStatus;
    }

    /**
     * Constructs metrics for a single test execution run based on its current status string.
     *
     * @param status        the normalized status
     * @param issueCount    the number of linked issues
     * @param evidenceCount the number of attached evidence files
     * @param earliestDate  the earliest date bound for the run
     * @param latestDate    the latest date bound for the run
     * @return the execution metrics object
     */
    private ExecutionMetrics buildSingleRunMetrics(
            String status,
            int issueCount,
            int evidenceCount,
            String earliestDate,
            String latestDate
    ) {
        int passed = 0;
        int failed = 0;
        int blocked = 0;
        int notRun = 0;
        int deferred = 0;
        int skipped = 0;

        switch (normalizeRunStatus(status)) {
            case "PASS" -> passed = 1;
            case "FAIL" -> failed = 1;
            case "BLOCKED" -> blocked = 1;
            case "DEFERRED" -> deferred = 1;
            case "SKIPPED" -> skipped = 1;
            default -> notRun = 1;
        }

        return new ExecutionMetrics(
                1,
                1,
                passed,
                failed,
                blocked,
                notRun,
                deferred,
                skipped,
                issueCount,
                evidenceCount,
                normalizeRunStatus(status),
                earliestDate,
                latestDate
        );
    }

    /**
     * Counts the total number of individual related issues linked.
     *
     * @param run the execution run to examine
     * @return the count of related issues
     */
    private int countRelatedIssues(ExecutionRunRecord run) {
        if (!nullableText(run.getRelatedIssue()).isBlank()) {
            return countDelimitedValues(run.getRelatedIssue());
        }
        return 0;
    }

    /**
     * Counts delimited items in a raw string field.
     *
     * @param value the raw text list
     * @return the number of delimited elements
     */
    private int countDelimitedValues(String value) {
        return (int) java.util.Arrays.stream(value.split("[,;\\n|]+"))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .count();
    }
}
