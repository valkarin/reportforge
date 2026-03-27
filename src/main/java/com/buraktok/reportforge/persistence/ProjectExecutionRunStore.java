package com.buraktok.reportforge.persistence;

import com.buraktok.reportforge.model.ExecutionReportSnapshot;
import com.buraktok.reportforge.model.ExecutionRunEvidenceRecord;
import com.buraktok.reportforge.model.ExecutionRunRecord;
import com.buraktok.reportforge.model.ExecutionRunSnapshot;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.buraktok.reportforge.persistence.PersistenceSupport.nextScopedSortOrder;
import static com.buraktok.reportforge.persistence.PersistenceSupport.nullableText;
import static com.buraktok.reportforge.persistence.PersistenceSupport.readNullableInteger;
import static com.buraktok.reportforge.persistence.PersistenceSupport.setNullableInteger;

final class ProjectExecutionRunStore {
    private final ProjectExecutionEvidenceStore evidenceStore;
    private final ExecutionMetricsAssembler metricsAssembler;

    ProjectExecutionRunStore(ProjectExecutionEvidenceStore evidenceStore, ExecutionMetricsAssembler metricsAssembler) {
        this.evidenceStore = evidenceStore;
        this.metricsAssembler = metricsAssembler;
    }

    ExecutionReportSnapshot loadExecutionReportSnapshot(Connection connection, String reportId) throws SQLException {
        List<ExecutionRunRecord> runRecords = loadExecutionRuns(connection, reportId);
        Map<String, List<ExecutionRunEvidenceRecord>> evidenceByRun = evidenceStore.loadExecutionRunEvidenceByRun(connection, runRecords);
        List<ExecutionRunSnapshot> runs = new ArrayList<>();
        for (ExecutionRunRecord run : runRecords) {
            List<ExecutionRunEvidenceRecord> evidences = evidenceByRun.getOrDefault(run.getId(), List.of());
            runs.add(new ExecutionRunSnapshot(run, evidences, metricsAssembler.buildRunMetrics(run, evidences)));
        }
        return new ExecutionReportSnapshot(reportId, runs, metricsAssembler.buildReportMetrics(runs));
    }

    ExecutionRunRecord createExecutionRun(Connection connection, String reportId) throws SQLException {
        ExecutionRunRecord run = newExecutionRunRecord(
                reportId,
                nextScopedSortOrder(connection, PersistenceSupport.ScopedSortTarget.REPORT_EXECUTION_RUN, reportId)
        );
        insertExecutionRun(connection, run);
        resequenceExecutionRuns(connection, reportId);
        return run;
    }

    void createInitialExecutionRun(Connection connection, String reportId) throws SQLException {
        insertExecutionRun(connection, newExecutionRunRecord(reportId, 0));
    }

    void updateExecutionRun(Connection connection, ExecutionRunRecord run) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE report_execution_runs
                SET execution_key = ?, suite_name = ?, executed_by = ?, execution_date = ?, start_date = ?, end_date = ?,
                    duration_text = ?, data_source_reference = ?, notes = ?, comments_text = ?, test_steps_text = ?,
                    test_case_key = ?, section_name = ?, subsection_name = ?, test_case_name = ?, priority = ?,
                    module_name = ?, status = ?, execution_time = ?, expected_result_summary = ?, actual_result = ?,
                    related_issue = ?, remarks = ?, blocked_reason = ?, defect_summary = ?, updated_at = ?
                WHERE id = ?
                """)) {
            statement.setString(1, nullableText(run.getExecutionKey()));
            statement.setString(2, nullableText(run.getSuiteName()));
            statement.setString(3, nullableText(run.getExecutedBy()));
            statement.setString(4, nullableText(run.getExecutionDate()));
            statement.setString(5, nullableText(run.getStartDate()));
            statement.setString(6, nullableText(run.getEndDate()));
            statement.setString(7, nullableText(run.getDurationText()));
            statement.setString(8, nullableText(run.getDataSourceReference()));
            statement.setString(9, nullableText(run.getNotes()));
            statement.setString(10, nullableText(run.getComments()));
            statement.setString(11, nullableText(run.getTestSteps()));
            statement.setString(12, nullableText(run.getTestCaseKey()));
            statement.setString(13, nullableText(run.getSectionName()));
            statement.setString(14, nullableText(run.getSubsectionName()));
            statement.setString(15, nullableText(run.getTestCaseName()));
            statement.setString(16, nullableText(run.getPriority()));
            statement.setString(17, nullableText(run.getModuleName()));
            statement.setString(18, nullableText(run.getStatus()));
            statement.setString(19, nullableText(run.getExecutionTime()));
            statement.setString(20, nullableText(run.getExpectedResultSummary()));
            statement.setString(21, nullableText(run.getActualResult()));
            statement.setString(22, nullableText(run.getRelatedIssue()));
            statement.setString(23, nullableText(run.getRemarks()));
            statement.setString(24, nullableText(run.getBlockedReason()));
            statement.setString(25, nullableText(run.getDefectSummary()));
            statement.setString(26, Instant.now().toString());
            statement.setString(27, run.getId());
            statement.executeUpdate();
        }
    }

    void deleteExecutionRun(Connection connection, String reportId, String executionRunId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM report_execution_runs WHERE id = ?")) {
            statement.setString(1, executionRunId);
            statement.executeUpdate();
        }
        resequenceExecutionRuns(connection, reportId);
    }

    void copyExecutionHierarchy(
            Connection connection,
            ProjectSession session,
            String sourceReportId,
            String targetReportId,
            List<Path> copiedEvidencePaths
    ) throws SQLException {
        ExecutionReportSnapshot snapshot = loadExecutionReportSnapshot(connection, sourceReportId);
        for (ExecutionRunSnapshot runSnapshot : snapshot.getRuns()) {
            ExecutionRunRecord copiedRun = copyExecutionRunRecord(runSnapshot.getRun(), targetReportId);
            insertExecutionRun(connection, copiedRun);
            for (ExecutionRunEvidenceRecord evidence : runSnapshot.getEvidences()) {
                evidenceStore.copyExecutionRunEvidence(connection, session, evidence, copiedRun.getId(), copiedEvidencePaths);
            }
        }
    }

    private List<ExecutionRunRecord> loadExecutionRuns(Connection connection, String reportId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, report_id, execution_key, suite_name, executed_by, execution_date, start_date, end_date,
                       duration_text, data_source_reference, notes, comments_text, test_steps_text, test_case_key,
                       section_name, subsection_name, test_case_name, priority, module_name, status, execution_time,
                       expected_result_summary, actual_result, related_issue, remarks, blocked_reason, defect_summary, legacy_total_executed,
                       legacy_passed_count, legacy_failed_count, legacy_blocked_count, legacy_not_run_count,
                       legacy_deferred_count, legacy_skipped_count, legacy_linked_defect_count, legacy_overall_outcome,
                       sort_order, created_at, updated_at
                FROM report_execution_runs
                WHERE report_id = ?
                ORDER BY sort_order, created_at, id
                """)) {
            statement.setString(1, reportId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ExecutionRunRecord> runs = new ArrayList<>();
                while (resultSet.next()) {
                    runs.add(mapExecutionRun(resultSet));
                }
                return runs;
            }
        }
    }

    private ExecutionRunRecord mapExecutionRun(ResultSet resultSet) throws SQLException {
        return ExecutionRunRecord.builder()
                .id(resultSet.getString("id"))
                .reportId(resultSet.getString("report_id"))
                .executionKey(resultSet.getString("execution_key"))
                .suiteName(resultSet.getString("suite_name"))
                .executedBy(resultSet.getString("executed_by"))
                .executionDate(resultSet.getString("execution_date"))
                .startDate(resultSet.getString("start_date"))
                .endDate(resultSet.getString("end_date"))
                .durationText(resultSet.getString("duration_text"))
                .dataSourceReference(resultSet.getString("data_source_reference"))
                .notes(resultSet.getString("notes"))
                .comments(resultSet.getString("comments_text"))
                .testSteps(resultSet.getString("test_steps_text"))
                .testCaseKey(resultSet.getString("test_case_key"))
                .sectionName(resultSet.getString("section_name"))
                .subsectionName(resultSet.getString("subsection_name"))
                .testCaseName(resultSet.getString("test_case_name"))
                .priority(resultSet.getString("priority"))
                .moduleName(resultSet.getString("module_name"))
                .status(resultSet.getString("status"))
                .executionTime(resultSet.getString("execution_time"))
                .expectedResultSummary(resultSet.getString("expected_result_summary"))
                .actualResult(resultSet.getString("actual_result"))
                .relatedIssue(resultSet.getString("related_issue"))
                .remarks(resultSet.getString("remarks"))
                .blockedReason(resultSet.getString("blocked_reason"))
                .defectSummary(resultSet.getString("defect_summary"))
                .legacyTotalExecuted(readNullableInteger(resultSet, "legacy_total_executed"))
                .legacyPassedCount(readNullableInteger(resultSet, "legacy_passed_count"))
                .legacyFailedCount(readNullableInteger(resultSet, "legacy_failed_count"))
                .legacyBlockedCount(readNullableInteger(resultSet, "legacy_blocked_count"))
                .legacyNotRunCount(readNullableInteger(resultSet, "legacy_not_run_count"))
                .legacyDeferredCount(readNullableInteger(resultSet, "legacy_deferred_count"))
                .legacySkippedCount(readNullableInteger(resultSet, "legacy_skipped_count"))
                .legacyLinkedDefectCount(readNullableInteger(resultSet, "legacy_linked_defect_count"))
                .legacyOverallOutcome(resultSet.getString("legacy_overall_outcome"))
                .sortOrder(resultSet.getInt("sort_order"))
                .createdAt(resultSet.getString("created_at"))
                .updatedAt(resultSet.getString("updated_at"))
                .build();
    }

    private void insertExecutionRun(Connection connection, ExecutionRunRecord run) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO report_execution_runs (
                    id, report_id, execution_key, suite_name, executed_by, execution_date, start_date, end_date,
                    duration_text, data_source_reference, notes, comments_text, test_steps_text, test_case_key,
                    section_name, subsection_name, test_case_name, priority, module_name, status, execution_time,
                    expected_result_summary, actual_result, related_issue, remarks, blocked_reason, defect_summary, legacy_total_executed,
                    legacy_passed_count, legacy_failed_count, legacy_blocked_count, legacy_not_run_count,
                    legacy_deferred_count, legacy_skipped_count, legacy_linked_defect_count, legacy_overall_outcome,
                    sort_order, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, run.getId());
            statement.setString(2, run.getReportId());
            statement.setString(3, nullableText(run.getExecutionKey()));
            statement.setString(4, nullableText(run.getSuiteName()));
            statement.setString(5, nullableText(run.getExecutedBy()));
            statement.setString(6, nullableText(run.getExecutionDate()));
            statement.setString(7, nullableText(run.getStartDate()));
            statement.setString(8, nullableText(run.getEndDate()));
            statement.setString(9, nullableText(run.getDurationText()));
            statement.setString(10, nullableText(run.getDataSourceReference()));
            statement.setString(11, nullableText(run.getNotes()));
            statement.setString(12, nullableText(run.getComments()));
            statement.setString(13, nullableText(run.getTestSteps()));
            statement.setString(14, nullableText(run.getTestCaseKey()));
            statement.setString(15, nullableText(run.getSectionName()));
            statement.setString(16, nullableText(run.getSubsectionName()));
            statement.setString(17, nullableText(run.getTestCaseName()));
            statement.setString(18, nullableText(run.getPriority()));
            statement.setString(19, nullableText(run.getModuleName()));
            statement.setString(20, nullableText(run.getStatus()));
            statement.setString(21, nullableText(run.getExecutionTime()));
            statement.setString(22, nullableText(run.getExpectedResultSummary()));
            statement.setString(23, nullableText(run.getActualResult()));
            statement.setString(24, nullableText(run.getRelatedIssue()));
            statement.setString(25, nullableText(run.getRemarks()));
            statement.setString(26, nullableText(run.getBlockedReason()));
            statement.setString(27, nullableText(run.getDefectSummary()));
            setNullableInteger(statement, 28, run.getLegacyTotalExecuted());
            setNullableInteger(statement, 29, run.getLegacyPassedCount());
            setNullableInteger(statement, 30, run.getLegacyFailedCount());
            setNullableInteger(statement, 31, run.getLegacyBlockedCount());
            setNullableInteger(statement, 32, run.getLegacyNotRunCount());
            setNullableInteger(statement, 33, run.getLegacyDeferredCount());
            setNullableInteger(statement, 34, run.getLegacySkippedCount());
            setNullableInteger(statement, 35, run.getLegacyLinkedDefectCount());
            statement.setString(36, PersistenceSupport.normalizeOutcome(run.getLegacyOverallOutcome()));
            statement.setInt(37, run.getSortOrder());
            statement.setString(38, run.getCreatedAt());
            statement.setString(39, run.getUpdatedAt());
            statement.executeUpdate();
        }
    }

    private ExecutionRunRecord copyExecutionRunRecord(ExecutionRunRecord run, String targetReportId) {
        String timestamp = Instant.now().toString();
        return run.toBuilder()
                .id(UUID.randomUUID().toString())
                .reportId(targetReportId)
                .createdAt(timestamp)
                .updatedAt(timestamp)
                .build();
    }

    private ExecutionRunRecord newExecutionRunRecord(String reportId, int sortOrder) {
        String timestamp = Instant.now().toString();
        String defaultExecutionKey = Integer.toString(sortOrder + 1);
        String defaultStartDate = LocalDate.now().toString();
        return ExecutionRunRecord.builder()
                .id(UUID.randomUUID().toString())
                .reportId(reportId)
                .executionKey(defaultExecutionKey)
                .startDate(defaultStartDate)
                .status("NOT_RUN")
                .sortOrder(sortOrder)
                .createdAt(timestamp)
                .updatedAt(timestamp)
                .build();
    }

    private void resequenceExecutionRuns(Connection connection, String reportId) throws SQLException {
        List<String> runIds = new ArrayList<>();
        List<String> executionKeys = new ArrayList<>();
        try (PreparedStatement readStatement = connection.prepareStatement("""
                SELECT id, execution_key
                FROM report_execution_runs
                WHERE report_id = ?
                ORDER BY sort_order, created_at, id
                """)) {
            readStatement.setString(1, reportId);
            try (ResultSet resultSet = readStatement.executeQuery()) {
                while (resultSet.next()) {
                    runIds.add(resultSet.getString("id"));
                    executionKeys.add(resultSet.getString("execution_key"));
                }
            }
        }

        if (runIds.isEmpty()) {
            return;
        }

        try (PreparedStatement updateStatement = connection.prepareStatement(
                "UPDATE report_execution_runs SET sort_order = ?, execution_key = ? WHERE id = ?")) {
            for (int index = 0; index < runIds.size(); index++) {
                String executionKey = executionKeys.get(index);
                String updatedExecutionKey = shouldAutoNumberExecutionKey(executionKey)
                        ? Integer.toString(index + 1)
                        : executionKey;
                updateStatement.setInt(1, index);
                updateStatement.setString(2, nullableText(updatedExecutionKey));
                updateStatement.setString(3, runIds.get(index));
                updateStatement.addBatch();
            }
            updateStatement.executeBatch();
        }
    }

    private boolean shouldAutoNumberExecutionKey(String executionKey) {
        return executionKey == null || executionKey.isBlank() || executionKey.chars().allMatch(Character::isDigit);
    }
}
