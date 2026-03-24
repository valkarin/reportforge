package com.buraktok.reportforge.persistence;

import com.buraktok.reportforge.model.ExecutionMetrics;
import com.buraktok.reportforge.model.ExecutionReportSnapshot;
import com.buraktok.reportforge.model.ExecutionRunEvidenceRecord;
import com.buraktok.reportforge.model.ExecutionRunRecord;
import com.buraktok.reportforge.model.ExecutionRunSnapshot;
import com.buraktok.reportforge.model.TestCaseResultRecord;
import com.buraktok.reportforge.model.TestCaseStepRecord;
import com.buraktok.reportforge.model.TestExecutionRecord;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.buraktok.reportforge.persistence.PersistenceSupport.aggregateOutcome;
import static com.buraktok.reportforge.persistence.PersistenceSupport.countRows;
import static com.buraktok.reportforge.persistence.PersistenceSupport.firstNonBlank;
import static com.buraktok.reportforge.persistence.PersistenceSupport.nextScopedSortOrder;
import static com.buraktok.reportforge.persistence.PersistenceSupport.nextSortOrder;
import static com.buraktok.reportforge.persistence.PersistenceSupport.normalizeOutcome;
import static com.buraktok.reportforge.persistence.PersistenceSupport.normalizeResultStatus;
import static com.buraktok.reportforge.persistence.PersistenceSupport.nullableText;
import static com.buraktok.reportforge.persistence.PersistenceSupport.openConnection;
import static com.buraktok.reportforge.persistence.PersistenceSupport.placeholders;
import static com.buraktok.reportforge.persistence.PersistenceSupport.readNullableInteger;
import static com.buraktok.reportforge.persistence.PersistenceSupport.selectBoundaryDate;
import static com.buraktok.reportforge.persistence.PersistenceSupport.setNullableInteger;
import static com.buraktok.reportforge.persistence.PersistenceSupport.toNullableInteger;
import static com.buraktok.reportforge.persistence.PersistenceSupport.valueOrZero;

final class ProjectExecutionStore {
    private final ProjectContainerFiles containerFiles;
    private final ProjectWorkspaceStore workspaceStore;

    ProjectExecutionStore(ProjectContainerFiles containerFiles, ProjectWorkspaceStore workspaceStore) {
        this.containerFiles = containerFiles;
        this.workspaceStore = workspaceStore;
    }

    List<TestExecutionRecord> loadReportExecutions(ProjectSession session, String reportId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
            migrateLegacyExecutionSummary(connection, reportId);
            return loadReportExecutions(connection, reportId);
        }
    }

    ExecutionReportSnapshot loadExecutionReportSnapshot(ProjectSession session, String reportId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
            migrateLegacyExecutionHierarchy(connection, reportId);
            migrateExecutionRunDetails(connection, reportId);
            return loadExecutionReportSnapshot(connection, reportId);
        }
    }

    ExecutionRunRecord createExecutionRun(ProjectSession session, String reportId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
            ExecutionRunRecord run = newExecutionRunRecord(
                    reportId,
                    nextScopedSortOrder(connection, "report_execution_runs", "report_id", reportId)
            );
            insertExecutionRun(connection, run);
            resequenceExecutionRuns(connection, reportId);
            workspaceStore.touchReport(connection, reportId);
            return run;
        }
    }

    void createInitialExecutionRun(Connection connection, String reportId) throws SQLException {
        insertExecutionRun(connection, newExecutionRunRecord(reportId, 0));
    }

    void updateExecutionRun(ProjectSession session, ExecutionRunRecord run) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
            updateExecutionRun(connection, run);
            workspaceStore.touchReport(connection, run.getReportId());
        }
    }

    void deleteExecutionRun(ProjectSession session, String reportId, String executionRunId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath());
             PreparedStatement statement = connection.prepareStatement("DELETE FROM report_execution_runs WHERE id = ?")) {
            statement.setString(1, executionRunId);
            statement.executeUpdate();
            resequenceExecutionRuns(connection, reportId);
            workspaceStore.touchReport(connection, reportId);
        }
    }

    ExecutionRunEvidenceRecord addExecutionRunEvidenceFromFile(ProjectSession session, String reportId, String executionRunId, Path sourcePath)
            throws IOException, SQLException {
        if (sourcePath == null || !Files.exists(sourcePath)) {
            throw new IOException("Evidence file not found.");
        }
        byte[] content = Files.readAllBytes(sourcePath);
        String fileName = sourcePath.getFileName() == null ? "evidence" : sourcePath.getFileName().toString();
        String mediaType = probeEvidenceMediaType(sourcePath, fileName);
        return addExecutionRunEvidence(session, reportId, executionRunId, fileName, mediaType, content);
    }

    ExecutionRunEvidenceRecord addExecutionRunEvidence(
            ProjectSession session,
            String reportId,
            String executionRunId,
            String originalFileName,
            String mediaType,
            byte[] content
    ) throws IOException, SQLException {
        if (content == null || content.length == 0) {
            throw new IOException("Evidence content is empty.");
        }
        String normalizedMediaType = normalizeEvidenceMediaType(mediaType, originalFileName);
        if (!normalizedMediaType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image evidence is currently supported.");
        }

        String timestamp = Instant.now().toString();
        String evidenceId = UUID.randomUUID().toString();
        String storedPath = storeEvidenceContent(session, executionRunId, originalFileName, content);

        try (Connection connection = openConnection(session.databasePath())) {
            ExecutionRunEvidenceRecord evidence = new ExecutionRunEvidenceRecord(
                    evidenceId,
                    executionRunId,
                    storedPath,
                    nullableText(originalFileName),
                    normalizedMediaType,
                    nextScopedSortOrder(connection, "report_execution_run_evidence", "execution_run_id", executionRunId),
                    timestamp,
                    timestamp
            );
            try {
                insertExecutionRunEvidence(connection, evidence);
            } catch (SQLException exception) {
                Files.deleteIfExists(containerFiles.resolveWorkspacePath(session, storedPath));
                throw exception;
            }
            workspaceStore.touchReport(connection, reportId);
            return evidence;
        }
    }

    void deleteExecutionRunEvidence(ProjectSession session, String reportId, String evidenceId) throws IOException, SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
            String storedPath = null;
            try (PreparedStatement readStatement = connection.prepareStatement(
                    "SELECT stored_path FROM report_execution_run_evidence WHERE id = ?")) {
                readStatement.setString(1, evidenceId);
                try (ResultSet resultSet = readStatement.executeQuery()) {
                    if (resultSet.next()) {
                        storedPath = resultSet.getString("stored_path");
                    }
                }
            }

            try (PreparedStatement deleteStatement = connection.prepareStatement(
                    "DELETE FROM report_execution_run_evidence WHERE id = ?")) {
                deleteStatement.setString(1, evidenceId);
                deleteStatement.executeUpdate();
            }
            workspaceStore.touchReport(connection, reportId);

            if (storedPath != null && !storedPath.isBlank()) {
                Files.deleteIfExists(containerFiles.resolveWorkspacePath(session, storedPath));
            }
        }
    }

    TestExecutionRecord createReportExecution(ProjectSession session, String reportId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
            TestExecutionRecord execution = newExecutionRecord(
                    reportId,
                    nextSortOrder(connection, "report_executions", reportId)
            );
            insertReportExecution(connection, execution);
            workspaceStore.touchReport(connection, reportId);
            return execution;
        }
    }

    void updateReportExecution(ProjectSession session, TestExecutionRecord execution) throws SQLException {
        try (Connection connection = openConnection(session.databasePath());
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE report_executions
                     SET start_date = ?, end_date = ?, total_executed = ?, passed_count = ?, failed_count = ?, blocked_count = ?,
                         not_run_count = ?, deferred_count = ?, skip_count = ?, linked_defect_count = ?, cycle_name = ?,
                         executed_by = ?, overall_outcome = ?, execution_window = ?, data_source_reference = ?,
                         blocked_execution_flag = ?, updated_at = ?
                     WHERE id = ?
                     """)) {
            statement.setString(1, nullableText(execution.getStartDate()));
            statement.setString(2, nullableText(execution.getEndDate()));
            setNullableInteger(statement, 3, execution.getTotalExecuted());
            setNullableInteger(statement, 4, execution.getPassedCount());
            setNullableInteger(statement, 5, execution.getFailedCount());
            setNullableInteger(statement, 6, execution.getBlockedCount());
            setNullableInteger(statement, 7, execution.getNotRunCount());
            setNullableInteger(statement, 8, execution.getDeferredCount());
            setNullableInteger(statement, 9, execution.getSkipCount());
            setNullableInteger(statement, 10, execution.getLinkedDefectCount());
            statement.setString(11, nullableText(execution.getCycleName()));
            statement.setString(12, nullableText(execution.getExecutedBy()));
            statement.setString(13, normalizeOutcome(execution.getOverallOutcome()));
            statement.setString(14, nullableText(execution.getExecutionWindow()));
            statement.setString(15, nullableText(execution.getDataSourceReference()));
            statement.setInt(16, execution.isBlockedExecutionFlag() ? 1 : 0);
            statement.setString(17, Instant.now().toString());
            statement.setString(18, execution.getId());
            statement.executeUpdate();
            workspaceStore.touchReport(connection, execution.getReportId());
        }
    }

    void deleteReportExecution(ProjectSession session, String executionId, String reportId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath());
             PreparedStatement statement = connection.prepareStatement("DELETE FROM report_executions WHERE id = ?")) {
            statement.setString(1, executionId);
            statement.executeUpdate();
            workspaceStore.touchReport(connection, reportId);
        }
    }

    void copyExecutionHierarchy(Connection connection, ProjectSession session, String sourceReportId, String targetReportId) throws SQLException {
        migrateLegacyExecutionHierarchy(connection, sourceReportId);
        migrateExecutionRunDetails(connection, sourceReportId);
        ExecutionReportSnapshot snapshot = loadExecutionReportSnapshot(connection, sourceReportId);
        for (ExecutionRunSnapshot runSnapshot : snapshot.getRuns()) {
            ExecutionRunRecord run = runSnapshot.getRun();
            String timestamp = Instant.now().toString();
            String copiedRunId = UUID.randomUUID().toString();
            insertExecutionRun(connection, new ExecutionRunRecord(
                    copiedRunId,
                    targetReportId,
                    run.getExecutionKey(),
                    run.getSuiteName(),
                    run.getExecutedBy(),
                    run.getExecutionDate(),
                    run.getStartDate(),
                    run.getEndDate(),
                    run.getDurationText(),
                    run.getDataSourceReference(),
                    run.getNotes(),
                    run.getComments(),
                    run.getTestSteps(),
                    run.getTestCaseKey(),
                    run.getSectionName(),
                    run.getSubsectionName(),
                    run.getTestCaseName(),
                    run.getPriority(),
                    run.getModuleName(),
                    run.getStatus(),
                    run.getExecutionTime(),
                    run.getExpectedResultSummary(),
                    run.getActualResult(),
                    run.getRelatedIssue(),
                    run.getRemarks(),
                    run.getBlockedReason(),
                    run.getDefectSummary(),
                    run.getLegacyTotalExecuted(),
                    run.getLegacyPassedCount(),
                    run.getLegacyFailedCount(),
                    run.getLegacyBlockedCount(),
                    run.getLegacyNotRunCount(),
                    run.getLegacyDeferredCount(),
                    run.getLegacySkippedCount(),
                    run.getLegacyLinkedDefectCount(),
                    run.getLegacyOverallOutcome(),
                    run.getSortOrder(),
                    timestamp,
                    timestamp
            ));

            for (ExecutionRunEvidenceRecord evidence : runSnapshot.getEvidences()) {
                copyExecutionRunEvidence(connection, session, evidence, copiedRunId);
            }
        }
    }

    private ExecutionReportSnapshot loadExecutionReportSnapshot(Connection connection, String reportId) throws SQLException {
        List<ExecutionRunRecord> runRecords = loadExecutionRuns(connection, reportId);
        Map<String, List<ExecutionRunEvidenceRecord>> evidenceByRun = loadExecutionRunEvidenceByRun(connection, runRecords);
        List<ExecutionRunSnapshot> runs = new ArrayList<>();
        for (ExecutionRunRecord run : runRecords) {
            List<ExecutionRunEvidenceRecord> evidences = evidenceByRun.getOrDefault(run.getId(), List.of());
            runs.add(new ExecutionRunSnapshot(run, evidences, buildRunMetrics(run, evidences)));
        }
        return new ExecutionReportSnapshot(reportId, runs, buildReportMetrics(runs));
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
                ORDER BY sort_order, created_at
                """)) {
            statement.setString(1, reportId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ExecutionRunRecord> runs = new ArrayList<>();
                while (resultSet.next()) {
                    runs.add(new ExecutionRunRecord(
                            resultSet.getString("id"),
                            resultSet.getString("report_id"),
                            resultSet.getString("execution_key"),
                            resultSet.getString("suite_name"),
                            resultSet.getString("executed_by"),
                            resultSet.getString("execution_date"),
                            resultSet.getString("start_date"),
                            resultSet.getString("end_date"),
                            resultSet.getString("duration_text"),
                            resultSet.getString("data_source_reference"),
                            resultSet.getString("notes"),
                            resultSet.getString("comments_text"),
                            resultSet.getString("test_steps_text"),
                            resultSet.getString("test_case_key"),
                            resultSet.getString("section_name"),
                            resultSet.getString("subsection_name"),
                            resultSet.getString("test_case_name"),
                            resultSet.getString("priority"),
                            resultSet.getString("module_name"),
                            resultSet.getString("status"),
                            resultSet.getString("execution_time"),
                            resultSet.getString("expected_result_summary"),
                            resultSet.getString("actual_result"),
                            resultSet.getString("related_issue"),
                            resultSet.getString("remarks"),
                            resultSet.getString("blocked_reason"),
                            resultSet.getString("defect_summary"),
                            readNullableInteger(resultSet, "legacy_total_executed"),
                            readNullableInteger(resultSet, "legacy_passed_count"),
                            readNullableInteger(resultSet, "legacy_failed_count"),
                            readNullableInteger(resultSet, "legacy_blocked_count"),
                            readNullableInteger(resultSet, "legacy_not_run_count"),
                            readNullableInteger(resultSet, "legacy_deferred_count"),
                            readNullableInteger(resultSet, "legacy_skipped_count"),
                            readNullableInteger(resultSet, "legacy_linked_defect_count"),
                            resultSet.getString("legacy_overall_outcome"),
                            resultSet.getInt("sort_order"),
                            resultSet.getString("created_at"),
                            resultSet.getString("updated_at")
                    ));
                }
                return runs;
            }
        }
    }

    private Map<String, List<ExecutionRunEvidenceRecord>> loadExecutionRunEvidenceByRun(
            Connection connection,
            List<ExecutionRunRecord> runs
    ) throws SQLException {
        Map<String, List<ExecutionRunEvidenceRecord>> evidenceByRun = new LinkedHashMap<>();
        if (runs.isEmpty()) {
            return evidenceByRun;
        }

        String sql = """
                SELECT id, execution_run_id, stored_path, original_file_name, media_type, sort_order, created_at, updated_at
                FROM report_execution_run_evidence
                WHERE execution_run_id IN (%s)
                ORDER BY execution_run_id, sort_order, created_at
                """.formatted(placeholders(runs.size()));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < runs.size(); index++) {
                statement.setString(index + 1, runs.get(index).getId());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ExecutionRunEvidenceRecord evidence = new ExecutionRunEvidenceRecord(
                            resultSet.getString("id"),
                            resultSet.getString("execution_run_id"),
                            resultSet.getString("stored_path"),
                            resultSet.getString("original_file_name"),
                            resultSet.getString("media_type"),
                            resultSet.getInt("sort_order"),
                            resultSet.getString("created_at"),
                            resultSet.getString("updated_at")
                    );
                    evidenceByRun.computeIfAbsent(evidence.getExecutionRunId(), ignored -> new ArrayList<>()).add(evidence);
                }
            }
        }
        return evidenceByRun;
    }

    private List<TestCaseResultRecord> loadTestCaseResults(Connection connection, String executionRunId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, execution_run_id, test_case_key, section_name, subsection_name, test_case_name, priority,
                       module_name, status, execution_time, expected_result_summary, actual_result, related_issue,
                       attachment_reference, remarks, blocked_reason, sort_order, created_at, updated_at
                FROM report_test_case_results
                WHERE execution_run_id = ?
                ORDER BY sort_order, created_at
                """)) {
            statement.setString(1, executionRunId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<TestCaseResultRecord> results = new ArrayList<>();
                while (resultSet.next()) {
                    results.add(new TestCaseResultRecord(
                            resultSet.getString("id"),
                            resultSet.getString("execution_run_id"),
                            resultSet.getString("test_case_key"),
                            resultSet.getString("section_name"),
                            resultSet.getString("subsection_name"),
                            resultSet.getString("test_case_name"),
                            resultSet.getString("priority"),
                            resultSet.getString("module_name"),
                            resultSet.getString("status"),
                            resultSet.getString("execution_time"),
                            resultSet.getString("expected_result_summary"),
                            resultSet.getString("actual_result"),
                            resultSet.getString("related_issue"),
                            resultSet.getString("attachment_reference"),
                            resultSet.getString("remarks"),
                            resultSet.getString("blocked_reason"),
                            resultSet.getInt("sort_order"),
                            resultSet.getString("created_at"),
                            resultSet.getString("updated_at")
                    ));
                }
                return results;
            }
        }
    }

    private List<TestCaseStepRecord> loadTestCaseSteps(Connection connection, String testCaseResultId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, test_case_result_id, step_number, step_action, expected_result, actual_result, status,
                       sort_order, created_at, updated_at
                FROM report_test_case_steps
                WHERE test_case_result_id = ?
                ORDER BY sort_order, created_at
                """)) {
            statement.setString(1, testCaseResultId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<TestCaseStepRecord> steps = new ArrayList<>();
                while (resultSet.next()) {
                    steps.add(new TestCaseStepRecord(
                            resultSet.getString("id"),
                            resultSet.getString("test_case_result_id"),
                            readNullableInteger(resultSet, "step_number"),
                            resultSet.getString("step_action"),
                            resultSet.getString("expected_result"),
                            resultSet.getString("actual_result"),
                            resultSet.getString("status"),
                            resultSet.getInt("sort_order"),
                            resultSet.getString("created_at"),
                            resultSet.getString("updated_at")
                    ));
                }
                return steps;
            }
        }
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
            statement.setString(36, normalizeOutcome(run.getLegacyOverallOutcome()));
            statement.setInt(37, run.getSortOrder());
            statement.setString(38, run.getCreatedAt());
            statement.setString(39, run.getUpdatedAt());
            statement.executeUpdate();
        }
    }

    private void insertExecutionRunEvidence(Connection connection, ExecutionRunEvidenceRecord evidence) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO report_execution_run_evidence (
                    id, execution_run_id, stored_path, original_file_name, media_type, sort_order, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, evidence.getId());
            statement.setString(2, evidence.getExecutionRunId());
            statement.setString(3, evidence.getStoredPath());
            statement.setString(4, nullableText(evidence.getOriginalFileName()));
            statement.setString(5, nullableText(evidence.getMediaType()));
            statement.setInt(6, evidence.getSortOrder());
            statement.setString(7, evidence.getCreatedAt());
            statement.setString(8, evidence.getUpdatedAt());
            statement.executeUpdate();
        }
    }

    private void updateExecutionRun(Connection connection, ExecutionRunRecord run) throws SQLException {
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

    private void copyExecutionRunEvidence(
            Connection connection,
            ProjectSession session,
            ExecutionRunEvidenceRecord evidence,
            String targetRunId
    ) throws SQLException {
        String copiedStoredPath = evidence.getStoredPath();
        Path sourcePath = containerFiles.resolveWorkspacePath(session, evidence.getStoredPath());
        if (Files.exists(sourcePath)) {
            try {
                copiedStoredPath = storeEvidenceContent(session, targetRunId, evidence.getDisplayName(), Files.readAllBytes(sourcePath));
            } catch (IOException exception) {
                throw new SQLException("Unable to copy execution evidence.", exception);
            }
        }

        String timestamp = Instant.now().toString();
        insertExecutionRunEvidence(connection, new ExecutionRunEvidenceRecord(
                UUID.randomUUID().toString(),
                targetRunId,
                copiedStoredPath,
                evidence.getOriginalFileName(),
                evidence.getMediaType(),
                evidence.getSortOrder(),
                timestamp,
                timestamp
        ));
    }

    private String storeEvidenceContent(ProjectSession session, String executionRunId, String originalFileName, byte[] content) throws IOException {
        String extension = evidenceFileExtension(originalFileName);
        String relativePath = "evidence/execution-runs/" + executionRunId + "/" + UUID.randomUUID() + extension;
        Path targetPath = containerFiles.resolveWorkspacePath(session, relativePath);
        Files.createDirectories(targetPath.getParent());
        try (InputStream inputStream = new ByteArrayInputStream(content)) {
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
        return relativePath;
    }

    private String probeEvidenceMediaType(Path sourcePath, String fileName) throws IOException {
        return normalizeEvidenceMediaType(Files.probeContentType(sourcePath), fileName);
    }

    private String normalizeEvidenceMediaType(String mediaType, String fileName) {
        if (mediaType != null && !mediaType.isBlank()) {
            return mediaType.trim().toLowerCase();
        }
        return switch (evidenceFileExtension(fileName)) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".gif" -> "image/gif";
            case ".bmp" -> "image/bmp";
            case ".webp" -> "image/webp";
            default -> "image/png";
        };
    }

    private String evidenceFileExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return ".png";
        }
        int extensionIndex = fileName.lastIndexOf('.');
        if (extensionIndex < 0 || extensionIndex == fileName.length() - 1) {
            return ".png";
        }
        String extension = fileName.substring(extensionIndex).toLowerCase();
        return switch (extension) {
            case ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp" -> extension;
            default -> ".png";
        };
    }

    private void migrateLegacyExecutionHierarchy(Connection connection, String reportId) throws SQLException {
        if (countRows(connection, "SELECT COUNT(*) FROM report_execution_runs WHERE report_id = ?", reportId) > 0) {
            return;
        }

        migrateLegacyExecutionSummary(connection, reportId);
        for (TestExecutionRecord legacyExecution : loadReportExecutions(connection, reportId)) {
            String timestamp = Instant.now().toString();
            insertExecutionRun(connection, new ExecutionRunRecord(
                    UUID.randomUUID().toString(),
                    reportId,
                    "",
                    legacyExecution.getCycleName(),
                    legacyExecution.getExecutedBy(),
                    firstNonBlank(legacyExecution.getStartDate(), legacyExecution.getEndDate()),
                    legacyExecution.getStartDate(),
                    legacyExecution.getEndDate(),
                    legacyExecution.getExecutionWindow(),
                    legacyExecution.getDataSourceReference(),
                    buildLegacyRunNotes(legacyExecution),
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    legacyExecution.getTotalExecuted(),
                    legacyExecution.getPassedCount(),
                    legacyExecution.getFailedCount(),
                    legacyExecution.getBlockedCount(),
                    legacyExecution.getNotRunCount(),
                    legacyExecution.getDeferredCount(),
                    legacyExecution.getSkipCount(),
                    legacyExecution.getLinkedDefectCount(),
                    normalizeOutcome(legacyExecution.getOverallOutcome()),
                    legacyExecution.getSortOrder(),
                    timestamp,
                    timestamp
            ));
        }
    }

    private void migrateExecutionRunDetails(Connection connection, String reportId) throws SQLException {
        for (ExecutionRunRecord run : loadExecutionRuns(connection, reportId)) {
            if (!nullableText(run.getStatus()).isBlank()) {
                continue;
            }

            List<TestCaseResultRecord> results = loadTestCaseResults(connection, run.getId());
            ExecutionRunRecord migratedRun;
            if (!results.isEmpty()) {
                Map<String, List<TestCaseStepRecord>> stepsByResult = new LinkedHashMap<>();
                for (TestCaseResultRecord result : results) {
                    stepsByResult.put(result.getId(), loadTestCaseSteps(connection, result.getId()));
                }
                migratedRun = collapseLegacyResultRows(run, results, stepsByResult);
            } else if (hasLegacyRunMetrics(run)) {
                migratedRun = copyRunWithDetails(
                        run,
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        deriveLegacyRunStatus(run),
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        buildLegacyRunDefectSummary(run),
                        appendTextBlock(run.getNotes(), "Migrated from the earlier execution summary model.")
                );
            } else {
                migratedRun = copyRunWithDetails(
                        run,
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "NOT_RUN",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        run.getNotes()
                );
            }
            updateExecutionRun(connection, migratedRun);
        }
    }

    private ExecutionRunRecord collapseLegacyResultRows(
            ExecutionRunRecord run,
            List<TestCaseResultRecord> results,
            Map<String, List<TestCaseStepRecord>> stepsByResult
    ) {
        TestCaseResultRecord primaryResult = results.getFirst();
        boolean singleResult = results.size() == 1;
        return copyRunWithDetails(
                run,
                singleResult ? primaryResult.getTestCaseKey() : "",
                singleResult ? primaryResult.getSectionName() : "",
                singleResult ? primaryResult.getSubsectionName() : "",
                singleResult ? primaryResult.getTestCaseName() : "Migrated from " + results.size() + " detailed result rows",
                singleResult ? primaryResult.getPriority() : "",
                singleResult ? primaryResult.getModuleName() : "",
                collapseLegacyResultStatus(results),
                singleResult ? primaryResult.getExecutionTime() : "",
                singleResult ? primaryResult.getExpectedResultSummary() : buildCollapsedExpectedSummary(results),
                buildCollapsedActualResult(results, stepsByResult),
                joinDistinctValues(results.stream().map(TestCaseResultRecord::getRelatedIssue).toList(), ", "),
                joinDistinctValues(results.stream().map(TestCaseResultRecord::getRemarks).toList(), System.lineSeparator()),
                joinDistinctValues(results.stream().map(TestCaseResultRecord::getBlockedReason).toList(), System.lineSeparator()),
                buildCollapsedDefectSummary(results),
                buildMigratedRunNotes(run, results, stepsByResult)
        );
    }

    private ExecutionRunRecord copyRunWithDetails(
            ExecutionRunRecord run,
            String testCaseKey,
            String sectionName,
            String subsectionName,
            String testCaseName,
            String priority,
            String moduleName,
            String status,
            String executionTime,
            String expectedResultSummary,
            String actualResult,
            String relatedIssue,
            String remarks,
            String blockedReason,
            String defectSummary,
            String notes
    ) {
        return new ExecutionRunRecord(
                run.getId(),
                run.getReportId(),
                run.getExecutionKey(),
                run.getSuiteName(),
                run.getExecutedBy(),
                run.getExecutionDate(),
                run.getStartDate(),
                run.getEndDate(),
                run.getDurationText(),
                run.getDataSourceReference(),
                notes,
                run.getComments(),
                run.getTestSteps(),
                testCaseKey,
                sectionName,
                subsectionName,
                testCaseName,
                priority,
                moduleName,
                status,
                executionTime,
                expectedResultSummary,
                actualResult,
                relatedIssue,
                remarks,
                blockedReason,
                defectSummary,
                run.getLegacyTotalExecuted(),
                run.getLegacyPassedCount(),
                run.getLegacyFailedCount(),
                run.getLegacyBlockedCount(),
                run.getLegacyNotRunCount(),
                run.getLegacyDeferredCount(),
                run.getLegacySkippedCount(),
                run.getLegacyLinkedDefectCount(),
                run.getLegacyOverallOutcome(),
                run.getSortOrder(),
                run.getCreatedAt(),
                run.getUpdatedAt()
        );
    }

    private String buildCollapsedExpectedSummary(List<TestCaseResultRecord> results) {
        return joinDistinctValues(
                results.stream()
                        .map(result -> {
                            String expected = nullableText(result.getExpectedResultSummary());
                            if (expected.isBlank()) {
                                return "";
                            }
                            return result.getDisplayLabel() + ": " + expected;
                        })
                        .toList(),
                System.lineSeparator()
        );
    }

    private String buildCollapsedActualResult(
            List<TestCaseResultRecord> results,
            Map<String, List<TestCaseStepRecord>> stepsByResult
    ) {
        if (results.size() == 1) {
            TestCaseResultRecord result = results.getFirst();
            StringBuilder builder = new StringBuilder(nullableText(result.getActualResult()));
            appendStepTrace(builder, stepsByResult.getOrDefault(result.getId(), List.of()));
            return builder.toString().trim();
        }

        List<String> blocks = new ArrayList<>();
        for (TestCaseResultRecord result : results) {
            StringBuilder block = new StringBuilder();
            block.append(result.getDisplayLabel())
                    .append(" [")
                    .append(normalizeResultStatus(result.getStatus()))
                    .append("]");
            appendDetailLine(block, "Actual Result", result.getActualResult());
            appendDetailLine(block, "Remarks", result.getRemarks());
            appendDetailLine(block, "Blocked Reason", result.getBlockedReason());
            appendStepTrace(block, stepsByResult.getOrDefault(result.getId(), List.of()));
            blocks.add(block.toString());
        }
        return String.join(System.lineSeparator() + System.lineSeparator(), blocks);
    }

    private String buildCollapsedDefectSummary(List<TestCaseResultRecord> results) {
        List<String> lines = new ArrayList<>();
        for (TestCaseResultRecord result : results) {
            String status = normalizeResultStatus(result.getStatus());
            if ("PASS".equals(status)
                    && nullableText(result.getRelatedIssue()).isBlank()
                    && nullableText(result.getBlockedReason()).isBlank()) {
                continue;
            }

            StringBuilder line = new StringBuilder(result.getDisplayLabel())
                    .append(" [")
                    .append(status)
                    .append("]");
            if (!nullableText(result.getRelatedIssue()).isBlank()) {
                line.append(" Issue: ").append(nullableText(result.getRelatedIssue()));
            }
            if (!nullableText(result.getBlockedReason()).isBlank()) {
                line.append(" | Blocked: ").append(nullableText(result.getBlockedReason()));
            }
            if (!nullableText(result.getActualResult()).isBlank() && ("FAIL".equals(status) || "BLOCKED".equals(status))) {
                line.append(" | Actual: ").append(nullableText(result.getActualResult()));
            }
            lines.add(line.toString());
        }
        return String.join(System.lineSeparator(), lines);
    }

    private String buildMigratedRunNotes(
            ExecutionRunRecord run,
            List<TestCaseResultRecord> results,
            Map<String, List<TestCaseStepRecord>> stepsByResult
    ) {
        List<String> notes = new ArrayList<>();
        notes.add("Collapsed " + results.size() + " legacy detailed result row(s) into this execution run.");

        String attachmentReferences = joinDistinctValues(
                results.stream().map(TestCaseResultRecord::getAttachmentReference).toList(),
                ", "
        );
        if (!attachmentReferences.isBlank()) {
            notes.add("Legacy attachment references: " + attachmentReferences);
        }

        boolean hasSteps = stepsByResult.values().stream().anyMatch(stepList -> !stepList.isEmpty());
        if (hasSteps) {
            notes.add("Legacy step trace was preserved in the Actual Result field.");
        }
        return appendTextBlock(run.getNotes(), String.join(System.lineSeparator(), notes));
    }

    private void appendDetailLine(StringBuilder builder, String label, String value) {
        String normalizedValue = nullableText(value);
        if (normalizedValue.isBlank()) {
            return;
        }
        builder.append(System.lineSeparator())
                .append(label)
                .append(": ")
                .append(normalizedValue);
    }

    private void appendStepTrace(StringBuilder builder, List<TestCaseStepRecord> steps) {
        if (steps.isEmpty()) {
            return;
        }
        builder.append(System.lineSeparator()).append(System.lineSeparator()).append("Step Trace:");
        for (TestCaseStepRecord step : steps) {
            builder.append(System.lineSeparator())
                    .append(step.getStepNumber() == null ? step.getSortOrder() + 1 : step.getStepNumber())
                    .append(". ")
                    .append(nullableText(step.getStepAction()))
                    .append(" -> ")
                    .append(normalizeResultStatus(step.getStatus()));
            if (!nullableText(step.getExpectedResult()).isBlank()) {
                builder.append(" | Expected: ").append(nullableText(step.getExpectedResult()));
            }
            if (!nullableText(step.getActualResult()).isBlank()) {
                builder.append(" | Actual: ").append(nullableText(step.getActualResult()));
            }
        }
    }

    private String appendTextBlock(String existingValue, String addition) {
        String normalizedExisting = nullableText(existingValue);
        String normalizedAddition = nullableText(addition);
        if (normalizedExisting.isBlank()) {
            return normalizedAddition;
        }
        if (normalizedAddition.isBlank()) {
            return normalizedExisting;
        }
        return normalizedExisting + System.lineSeparator() + System.lineSeparator() + normalizedAddition;
    }

    private String joinDistinctValues(List<String> values, String separator) {
        LinkedHashSet<String> distinctValues = new LinkedHashSet<>();
        for (String value : values) {
            String normalizedValue = nullableText(value);
            if (!normalizedValue.isBlank()) {
                distinctValues.add(normalizedValue);
            }
        }
        return String.join(separator, distinctValues);
    }

    private String collapseLegacyResultStatus(List<TestCaseResultRecord> results) {
        String collapsedStatus = "NOT_RUN";
        int collapsedPriority = Integer.MIN_VALUE;
        for (TestCaseResultRecord result : results) {
            String status = normalizeRunStatus(result.getStatus());
            int priority = runStatusPriority(status);
            if (priority > collapsedPriority) {
                collapsedPriority = priority;
                collapsedStatus = status;
            }
        }
        return collapsedStatus;
    }

    private int runStatusPriority(String status) {
        return switch (normalizeRunStatus(status)) {
            case "FAIL" -> 6;
            case "BLOCKED" -> 5;
            case "DEFERRED" -> 4;
            case "NOT_RUN" -> 3;
            case "SKIPPED" -> 2;
            case "PASS" -> 1;
            default -> 0;
        };
    }

    private String deriveLegacyRunStatus(ExecutionRunRecord run) {
        String normalizedOutcome = normalizeOutcome(run.getLegacyOverallOutcome()).trim().toUpperCase();
        return switch (normalizedOutcome) {
            case "PASS", "PASSED" -> "PASS";
            case "FAIL", "FAILED" -> "FAIL";
            case "BLOCKED" -> "BLOCKED";
            case "DEFERRED" -> "DEFERRED";
            case "SKIPPED", "SKIP" -> "SKIPPED";
            case "NOT_RUN", "NOT_EXECUTED" -> "NOT_RUN";
            default -> {
                if (valueOrZero(run.getLegacyFailedCount()) > 0) {
                    yield "FAIL";
                }
                if (valueOrZero(run.getLegacyBlockedCount()) > 0) {
                    yield "BLOCKED";
                }
                if (valueOrZero(run.getLegacyDeferredCount()) > 0) {
                    yield "DEFERRED";
                }
                if (valueOrZero(run.getLegacySkippedCount()) > 0) {
                    yield "SKIPPED";
                }
                if (valueOrZero(run.getLegacyPassedCount()) > 0
                        && valueOrZero(run.getLegacyFailedCount()) == 0
                        && valueOrZero(run.getLegacyBlockedCount()) == 0) {
                    yield "PASS";
                }
                yield "NOT_RUN";
            }
        };
    }

    private String buildLegacyRunDefectSummary(ExecutionRunRecord run) {
        List<String> parts = new ArrayList<>();
        if (valueOrZero(run.getLegacyFailedCount()) > 0) {
            parts.add("Failed items: " + valueOrZero(run.getLegacyFailedCount()));
        }
        if (valueOrZero(run.getLegacyBlockedCount()) > 0) {
            parts.add("Blocked items: " + valueOrZero(run.getLegacyBlockedCount()));
        }
        if (valueOrZero(run.getLegacyLinkedDefectCount()) > 0) {
            parts.add("Linked issues: " + valueOrZero(run.getLegacyLinkedDefectCount()));
        }
        return String.join(" | ", parts);
    }

    private int countRelatedIssues(ExecutionRunRecord run) {
        if (!nullableText(run.getRelatedIssue()).isBlank()) {
            return countDelimitedValues(run.getRelatedIssue());
        }
        return valueOrZero(run.getLegacyLinkedDefectCount());
    }

    private int countDelimitedValues(String value) {
        return (int) java.util.Arrays.stream(value.split("[,;\\n|]+"))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .count();
    }

    private String normalizeRunStatus(String status) {
        String normalizedStatus = normalizeResultStatus(status);
        return "NOT_EXECUTED".equals(normalizedStatus) ? "NOT_RUN" : normalizedStatus;
    }

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

    private ExecutionMetrics buildRunMetrics(ExecutionRunRecord run, List<ExecutionRunEvidenceRecord> evidences) {
        if (!nullableText(run.getStatus()).isBlank()) {
            return buildSingleRunMetrics(
                    normalizeRunStatus(run.getStatus()),
                    countRelatedIssues(run),
                    evidences.size(),
                    firstNonBlank(run.getStartDate(), run.getExecutionDate(), run.getEndDate()),
                    firstNonBlank(run.getEndDate(), run.getExecutionDate(), run.getStartDate())
            );
        }

        if (hasLegacyRunMetrics(run)) {
            return new ExecutionMetrics(
                    1,
                    valueOrZero(run.getLegacyTotalExecuted()),
                    valueOrZero(run.getLegacyPassedCount()),
                    valueOrZero(run.getLegacyFailedCount()),
                    valueOrZero(run.getLegacyBlockedCount()),
                    valueOrZero(run.getLegacyNotRunCount()),
                    valueOrZero(run.getLegacyDeferredCount()),
                    valueOrZero(run.getLegacySkippedCount()),
                    valueOrZero(run.getLegacyLinkedDefectCount()),
                    0,
                    normalizeOutcome(run.getLegacyOverallOutcome()),
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

    private ExecutionMetrics buildReportMetrics(List<ExecutionRunSnapshot> runs) {
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

    private boolean hasLegacyRunMetrics(ExecutionRunRecord run) {
        return run.getLegacyTotalExecuted() != null
                || run.getLegacyPassedCount() != null
                || run.getLegacyFailedCount() != null
                || run.getLegacyBlockedCount() != null
                || run.getLegacyNotRunCount() != null
                || run.getLegacyDeferredCount() != null
                || run.getLegacySkippedCount() != null
                || run.getLegacyLinkedDefectCount() != null
                || !nullableText(run.getLegacyOverallOutcome()).isBlank();
    }

    private String buildLegacyRunNotes(TestExecutionRecord execution) {
        List<String> noteParts = new ArrayList<>();
        noteParts.add("Migrated from the earlier execution summary model.");
        if (execution.getTotalExecuted() != null) {
            noteParts.add("Total: " + execution.getTotalExecuted());
        }
        if (execution.getPassedCount() != null) {
            noteParts.add("Passed: " + execution.getPassedCount());
        }
        if (execution.getFailedCount() != null) {
            noteParts.add("Failed: " + execution.getFailedCount());
        }
        if (execution.getBlockedCount() != null) {
            noteParts.add("Blocked: " + execution.getBlockedCount());
        }
        if (execution.getLinkedDefectCount() != null) {
            noteParts.add("Linked defects: " + execution.getLinkedDefectCount());
        }
        if (!nullableText(execution.getOverallOutcome()).isBlank()) {
            noteParts.add("Outcome: " + normalizeOutcome(execution.getOverallOutcome()));
        }
        return String.join(" | ", noteParts);
    }

    private ExecutionRunRecord newExecutionRunRecord(String reportId, int sortOrder) {
        String timestamp = Instant.now().toString();
        String defaultExecutionKey = Integer.toString(sortOrder + 1);
        String defaultStartDate = LocalDate.now().toString();
        return new ExecutionRunRecord(
                UUID.randomUUID().toString(),
                reportId,
                defaultExecutionKey,
                "",
                "",
                "",
                defaultStartDate,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "NOT_RUN",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "NOT_EXECUTED",
                sortOrder,
                timestamp,
                timestamp
        );
    }

    private void insertReportExecution(Connection connection, TestExecutionRecord execution) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO report_executions (
                    id, report_id, start_date, end_date, total_executed, passed_count, failed_count, blocked_count,
                    not_run_count, deferred_count, skip_count, linked_defect_count, cycle_name, executed_by,
                    overall_outcome, execution_window, data_source_reference, blocked_execution_flag, sort_order, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, execution.getId());
            statement.setString(2, execution.getReportId());
            statement.setString(3, nullableText(execution.getStartDate()));
            statement.setString(4, nullableText(execution.getEndDate()));
            setNullableInteger(statement, 5, execution.getTotalExecuted());
            setNullableInteger(statement, 6, execution.getPassedCount());
            setNullableInteger(statement, 7, execution.getFailedCount());
            setNullableInteger(statement, 8, execution.getBlockedCount());
            setNullableInteger(statement, 9, execution.getNotRunCount());
            setNullableInteger(statement, 10, execution.getDeferredCount());
            setNullableInteger(statement, 11, execution.getSkipCount());
            setNullableInteger(statement, 12, execution.getLinkedDefectCount());
            statement.setString(13, nullableText(execution.getCycleName()));
            statement.setString(14, nullableText(execution.getExecutedBy()));
            statement.setString(15, normalizeOutcome(execution.getOverallOutcome()));
            statement.setString(16, nullableText(execution.getExecutionWindow()));
            statement.setString(17, nullableText(execution.getDataSourceReference()));
            statement.setInt(18, execution.isBlockedExecutionFlag() ? 1 : 0);
            statement.setInt(19, execution.getSortOrder());
            statement.setString(20, execution.getCreatedAt());
            statement.setString(21, execution.getUpdatedAt());
            statement.executeUpdate();
        }
    }

    private List<TestExecutionRecord> loadReportExecutions(Connection connection, String reportId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, report_id, start_date, end_date, total_executed, passed_count, failed_count, blocked_count,
                       not_run_count, deferred_count, skip_count, linked_defect_count, cycle_name, executed_by,
                       overall_outcome, execution_window, data_source_reference, blocked_execution_flag, sort_order,
                       created_at, updated_at
                FROM report_executions
                WHERE report_id = ?
                ORDER BY sort_order, created_at
                """)) {
            statement.setString(1, reportId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<TestExecutionRecord> executions = new ArrayList<>();
                while (resultSet.next()) {
                    executions.add(new TestExecutionRecord(
                            resultSet.getString("id"),
                            resultSet.getString("report_id"),
                            resultSet.getString("start_date"),
                            resultSet.getString("end_date"),
                            readNullableInteger(resultSet, "total_executed"),
                            readNullableInteger(resultSet, "passed_count"),
                            readNullableInteger(resultSet, "failed_count"),
                            readNullableInteger(resultSet, "blocked_count"),
                            readNullableInteger(resultSet, "not_run_count"),
                            readNullableInteger(resultSet, "deferred_count"),
                            readNullableInteger(resultSet, "skip_count"),
                            readNullableInteger(resultSet, "linked_defect_count"),
                            resultSet.getString("cycle_name"),
                            resultSet.getString("executed_by"),
                            resultSet.getString("overall_outcome"),
                            resultSet.getString("execution_window"),
                            resultSet.getString("data_source_reference"),
                            resultSet.getInt("blocked_execution_flag") == 1,
                            resultSet.getInt("sort_order"),
                            resultSet.getString("created_at"),
                            resultSet.getString("updated_at")
                    ));
                }
                return executions;
            }
        }
    }

    private void migrateLegacyExecutionSummary(Connection connection, String reportId) throws SQLException {
        if (countRows(connection, "SELECT COUNT(*) FROM report_executions WHERE report_id = ?", reportId) > 0) {
            return;
        }

        Map<String, String> legacyFields = loadLegacyExecutionFields(connection, reportId);
        if (legacyFields.isEmpty()) {
            return;
        }

        String timestamp = Instant.now().toString();
        insertReportExecution(connection, new TestExecutionRecord(
                UUID.randomUUID().toString(),
                reportId,
                legacyFields.get("executionSummary.startDate"),
                legacyFields.get("executionSummary.endDate"),
                toNullableInteger(legacyFields.get("executionSummary.totalExecuted")),
                toNullableInteger(legacyFields.get("executionSummary.passedCount")),
                toNullableInteger(legacyFields.get("executionSummary.failedCount")),
                toNullableInteger(legacyFields.get("executionSummary.blockedCount")),
                toNullableInteger(legacyFields.get("executionSummary.notRunCount")),
                toNullableInteger(legacyFields.get("executionSummary.deferredCount")),
                toNullableInteger(legacyFields.get("executionSummary.skipCount")),
                toNullableInteger(legacyFields.get("executionSummary.linkedDefectCount")),
                legacyFields.get("executionSummary.cycleName"),
                legacyFields.get("executionSummary.executedBy"),
                normalizeOutcome(legacyFields.get("executionSummary.overallOutcome")),
                legacyFields.get("executionSummary.executionWindow"),
                legacyFields.get("executionSummary.dataSourceReference"),
                Boolean.parseBoolean(legacyFields.getOrDefault("executionSummary.blockedExecutionFlag", "false")),
                0,
                timestamp,
                timestamp
        ));
    }

    private Map<String, String> loadLegacyExecutionFields(Connection connection, String reportId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT field_key, field_value FROM report_fields WHERE report_id = ? AND field_key LIKE 'executionSummary.%'")) {
            statement.setString(1, reportId);
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, String> fields = new HashMap<>();
                while (resultSet.next()) {
                    fields.put(resultSet.getString("field_key"), resultSet.getString("field_value"));
                }
                return fields;
            }
        }
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

    private TestExecutionRecord newExecutionRecord(String reportId, int sortOrder) {
        String timestamp = Instant.now().toString();
        return new TestExecutionRecord(
                UUID.randomUUID().toString(),
                reportId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "",
                "",
                "NOT_EXECUTED",
                "",
                "",
                false,
                sortOrder,
                timestamp,
                timestamp
        );
    }
}
