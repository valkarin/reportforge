package com.buraktok.reportforge.persistence;

import com.buraktok.reportforge.model.ApplicationEntry;
import com.buraktok.reportforge.model.ExecutionMetrics;
import com.buraktok.reportforge.model.ExecutionReportSnapshot;
import com.buraktok.reportforge.model.ExecutionRunRecord;
import com.buraktok.reportforge.model.ExecutionRunSnapshot;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ProjectSummary;
import com.buraktok.reportforge.model.ProjectWorkspace;
import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.ReportStatus;
import com.buraktok.reportforge.model.TestCaseResultRecord;
import com.buraktok.reportforge.model.TestCaseResultSnapshot;
import com.buraktok.reportforge.model.TestCaseStepRecord;
import com.buraktok.reportforge.model.TestExecutionRecord;
import com.buraktok.reportforge.model.TestExecutionSection;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ProjectContainerService {
    public static final String PROJECT_EXTENSION = ".rfproj";

    private static final String APP_VERSION = "0.1.0";
    private static final String FORMAT_VERSION = "1";
    private static final int MAX_FILE_COUNT = 512;
    private static final long MAX_TOTAL_UNCOMPRESSED_SIZE = 256L * 1024L * 1024L;
    private static final long MAX_ENTRY_SIZE = 64L * 1024L * 1024L;
    private static final String DEFAULT_DATABASE_PATH = "data/project.db";
    private static final String DEFAULT_BRANDING_JSON = "{\n  \"companyName\": \"\",\n  \"logoPath\": \"\",\n  \"colorTheme\": \"\",\n  \"footerText\": \"\"\n}\n";
    private static final String DEFAULT_EXPORT_PRESETS_JSON = "{\n  \"presets\": []\n}\n";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProjectSession currentSession;

    public ProjectSession createProject(Path requestedTargetFile, String projectName, String initialEnvironmentName, List<String> applicationNames)
            throws IOException, SQLException {
        String normalizedName = requireText(projectName, "Project Name");
        Path targetFile = ensureProjectExtension(requestedTargetFile);
        Path parentDirectory = targetFile.toAbsolutePath().getParent();
        if (parentDirectory == null) {
            throw new IOException("Invalid project location.");
        }
        Files.createDirectories(parentDirectory);

        closeCurrentSession();

        Path workspaceRoot = Files.createTempDirectory("reportforge-");
        ProjectSession session = null;
        try {
            initializeWorkspaceDirectories(workspaceRoot);
            Path databasePath = workspaceRoot.resolve(DEFAULT_DATABASE_PATH.replace("/", "\\"));
            initializeSchema(databasePath);
            String now = Instant.now().toString();
            String projectId = UUID.randomUUID().toString();

            try (Connection connection = openConnection(databasePath)) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO project_info (id, name, description, created_at, updated_at) VALUES (?, ?, ?, ?, ?)")) {
                    statement.setString(1, projectId);
                    statement.setString(2, normalizedName);
                    statement.setString(3, "");
                    statement.setString(4, now);
                    statement.setString(5, now);
                    statement.executeUpdate();
                }

                insertInitialApplications(connection, applicationNames);
                if (initialEnvironmentName != null && !initialEnvironmentName.isBlank()) {
                    insertEnvironment(connection, new EnvironmentRecord(
                            UUID.randomUUID().toString(),
                            initialEnvironmentName.trim(),
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            0
                    ));
                }
            }

            session = new ProjectSession(
                    targetFile.toAbsolutePath(),
                    workspaceRoot,
                    new ManifestData(FORMAT_VERSION, APP_VERSION, projectId, normalizedName, DEFAULT_DATABASE_PATH, now, now)
            );
            currentSession = session;
            writeAuxiliaryFiles();
            saveProject();
            return session;
        } catch (IOException | SQLException exception) {
            if (session == null) {
                deleteDirectorySilently(workspaceRoot);
            }
            currentSession = null;
            throw exception;
        }
    }

    public ProjectSession openProject(Path projectFile) throws IOException, SQLException {
        Path absoluteProjectPath = projectFile.toAbsolutePath();
        if (!Files.exists(absoluteProjectPath)) {
            throw new IOException("File not found.");
        }

        closeCurrentSession();

        Path workspaceRoot = Files.createTempDirectory("reportforge-");
        try {
            initializeWorkspaceDirectories(workspaceRoot);
            extractProjectContainer(absoluteProjectPath, workspaceRoot);

            Path manifestPath = workspaceRoot.resolve("manifest.json");
            Path databasePath = workspaceRoot.resolve(DEFAULT_DATABASE_PATH.replace("/", "\\"));
            if (!Files.exists(manifestPath) || !Files.exists(databasePath)) {
                throw new IOException("Failed to read project data.");
            }

            ManifestData manifestData = readManifest(manifestPath);
            if (manifestData == null || !Objects.equals(manifestData.databasePath(), DEFAULT_DATABASE_PATH)) {
                throw new IOException("Failed to read project data.");
            }

            validateChecksums(workspaceRoot);
            initializeSchema(databasePath);
            try (Connection ignored = openConnection(databasePath)) {
                // Verifies that SQLite can read the project database.
            }

            currentSession = new ProjectSession(absoluteProjectPath, workspaceRoot, manifestData);
            return currentSession;
        } catch (IOException | SQLException exception) {
            deleteDirectorySilently(workspaceRoot);
            currentSession = null;
            throw exception;
        }
    }

    public void closeCurrentSession() {
        if (currentSession != null) {
            deleteDirectorySilently(currentSession.workspaceRoot());
            currentSession = null;
        }
    }

    public ProjectSession getCurrentSession() {
        return currentSession;
    }

    public ProjectWorkspace loadWorkspace() throws SQLException {
        Path databasePath = requireCurrentSession().databasePath();
        try (Connection connection = openConnection(databasePath)) {
            ProjectSummary summary = loadProjectSummary(connection);
            List<ApplicationEntry> projectApplications = loadApplications(connection, "project_applications", null);
            List<EnvironmentRecord> environments = loadEnvironments(connection);
            Map<String, List<ReportRecord>> reportsByEnvironment = loadReportsByEnvironment(connection);
            return new ProjectWorkspace(summary, projectApplications, environments, reportsByEnvironment);
        }
    }

    public Map<String, String> loadReportFields(String reportId) throws SQLException {
        Map<String, String> fieldValues = new HashMap<>();
        try (Connection connection = openConnection(requireCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT field_key, field_value FROM report_fields WHERE report_id = ?")) {
            statement.setString(1, reportId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    fieldValues.put(resultSet.getString("field_key"), resultSet.getString("field_value"));
                }
            }
        }
        return fieldValues;
    }

    public List<ApplicationEntry> loadReportApplications(String reportId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath())) {
            return loadApplications(connection, "report_applications", reportId);
        }
    }

    public List<TestExecutionRecord> loadReportExecutions(String reportId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath())) {
            migrateLegacyExecutionSummary(connection, reportId);
            return loadReportExecutions(connection, reportId);
        }
    }

    public ExecutionReportSnapshot loadExecutionReportSnapshot(String reportId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath())) {
            migrateLegacyExecutionHierarchy(connection, reportId);
            return loadExecutionReportSnapshot(connection, reportId);
        }
    }

    public EnvironmentRecord loadReportEnvironmentSnapshot(String reportId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT report_id, name, type, base_url, os_platform, browser_client, backend_version, notes " +
                             "FROM report_environment_snapshots WHERE report_id = ?")) {
            statement.setString(1, reportId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new EnvironmentRecord(
                            resultSet.getString("report_id"),
                            resultSet.getString("name"),
                            resultSet.getString("type"),
                            resultSet.getString("base_url"),
                            resultSet.getString("os_platform"),
                            resultSet.getString("browser_client"),
                            resultSet.getString("backend_version"),
                            resultSet.getString("notes"),
                            0
                    );
                }
            }
        }
        return new EnvironmentRecord(reportId, "", "", "", "", "", "", "", 0);
    }

    public void updateProject(String name, String description) throws SQLException {
        String now = Instant.now().toString();
        try (Connection connection = openConnection(requireCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE project_info SET name = ?, description = ?, updated_at = ? WHERE rowid = 1")) {
            statement.setString(1, requireText(name, "Project Name"));
            statement.setString(2, nullableText(description));
            statement.setString(3, now);
            statement.executeUpdate();
        }
    }

    public EnvironmentRecord createEnvironment(String name) throws SQLException {
        Path databasePath = requireCurrentSession().databasePath();
        try (Connection connection = openConnection(databasePath)) {
            int sortOrder = nextSortOrder(connection, "environments", null);
            EnvironmentRecord environment = new EnvironmentRecord(
                    UUID.randomUUID().toString(),
                    requireText(name, "Environment Name"),
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    sortOrder
            );
            insertEnvironment(connection, environment);
            return environment;
        }
    }

    public void updateEnvironment(EnvironmentRecord environment) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE environments SET name = ?, type = ?, base_url = ?, os_platform = ?, browser_client = ?, " +
                             "backend_version = ?, notes = ? WHERE id = ?")) {
            statement.setString(1, requireText(environment.getName(), "Environment Name"));
            statement.setString(2, nullableText(environment.getType()));
            statement.setString(3, nullableText(environment.getBaseUrl()));
            statement.setString(4, nullableText(environment.getOsPlatform()));
            statement.setString(5, nullableText(environment.getBrowserClient()));
            statement.setString(6, nullableText(environment.getBackendVersion()));
            statement.setString(7, nullableText(environment.getNotes()));
            statement.setString(8, environment.getId());
            statement.executeUpdate();
        }
    }

    public void deleteEnvironment(String environmentId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath())) {
            int reportCount = countRows(connection, "SELECT COUNT(*) FROM reports WHERE environment_id = ?", environmentId);
            if (reportCount > 0) {
                throw new IllegalStateException("Move or delete reports before removing the environment.");
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM environments WHERE id = ?")) {
                statement.setString(1, environmentId);
                statement.executeUpdate();
            }
        }
    }

    public void upsertProjectApplication(ApplicationEntry application) throws SQLException {
        upsertApplication("project_applications", null, application);
    }

    public void deleteProjectApplication(String applicationId) throws SQLException {
        deleteApplication("project_applications", applicationId, null);
    }

    public void setPrimaryProjectApplication(String applicationId) throws SQLException {
        setPrimaryApplication("project_applications", applicationId, null);
    }

    public ReportRecord createTestExecutionReport(String environmentId, String requestedTitle) throws SQLException {
        String reportId = UUID.randomUUID().toString();
        String timestamp = Instant.now().toString();
        String reportTitle = requestedTitle == null || requestedTitle.isBlank()
                ? "Test Execution Report"
                : requestedTitle.trim();

        try (Connection connection = openConnection(requireCurrentSession().databasePath())) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO reports (id, environment_id, title, report_type, status, created_at, updated_at, last_selected_section) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, reportId);
                statement.setString(2, environmentId);
                statement.setString(3, reportTitle);
                statement.setString(4, "TEST_EXECUTION_REPORT");
                statement.setString(5, ReportStatus.DRAFT.name());
                statement.setString(6, timestamp);
                statement.setString(7, timestamp);
                statement.setString(8, TestExecutionSection.PROJECT_OVERVIEW.name());
                statement.executeUpdate();
            }

            snapshotProjectOverview(connection, reportId);
            snapshotApplications(connection, reportId);
            snapshotEnvironment(connection, reportId, environmentId);
            insertExecutionRun(connection, newExecutionRunRecord(reportId, 0));
        }

        return loadReport(reportId);
    }

    public ReportRecord copyReportToEnvironment(String reportId, String targetEnvironmentId) throws SQLException {
        ReportRecord originalReport = loadReport(reportId);
        String newReportId = UUID.randomUUID().toString();
        String timestamp = Instant.now().toString();
        String copiedTitle = originalReport.getTitle() + " (Copy)";

        try (Connection connection = openConnection(requireCurrentSession().databasePath())) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO reports (id, environment_id, title, report_type, status, created_at, updated_at, last_selected_section) " +
                            "SELECT ?, ?, ?, report_type, status, ?, ?, last_selected_section FROM reports WHERE id = ?")) {
                statement.setString(1, newReportId);
                statement.setString(2, targetEnvironmentId);
                statement.setString(3, copiedTitle);
                statement.setString(4, timestamp);
                statement.setString(5, timestamp);
                statement.setString(6, reportId);
                statement.executeUpdate();
            }

            copyReportFields(connection, reportId, newReportId);
            copyReportApplications(connection, reportId, newReportId);
            snapshotEnvironment(connection, newReportId, targetEnvironmentId);
            copyExecutionHierarchy(connection, reportId, newReportId);
        }

        return loadReport(newReportId);
    }

    public void moveReportToEnvironment(String reportId, String targetEnvironmentId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE reports SET environment_id = ?, updated_at = ? WHERE id = ?")) {
            statement.setString(1, targetEnvironmentId);
            statement.setString(2, Instant.now().toString());
            statement.setString(3, reportId);
            statement.executeUpdate();
            snapshotEnvironment(connection, reportId, targetEnvironmentId);
        }
    }

    public void deleteReport(String reportId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement("DELETE FROM reports WHERE id = ?")) {
            statement.setString(1, reportId);
            statement.executeUpdate();
        }
    }

    public void updateReportStatus(String reportId, ReportStatus status) throws SQLException {
        updateReportColumn(reportId, "status", status.name());
    }

    public void updateReportTitle(String reportId, String title) throws SQLException {
        updateReportColumn(reportId, "title", requireText(title, "Report Title"));
    }

    public void updateLastSelectedSection(String reportId, TestExecutionSection section) throws SQLException {
        updateReportColumn(reportId, "last_selected_section", section.name());
    }

    public void updateReportField(String reportId, String fieldKey, String fieldValue) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath())) {
            if (fieldValue == null || fieldValue.isBlank()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM report_fields WHERE report_id = ? AND field_key = ?")) {
                    statement.setString(1, reportId);
                    statement.setString(2, fieldKey);
                    statement.executeUpdate();
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO report_fields (report_id, field_key, field_value) VALUES (?, ?, ?) " +
                                "ON CONFLICT(report_id, field_key) DO UPDATE SET field_value = excluded.field_value")) {
                    statement.setString(1, reportId);
                    statement.setString(2, fieldKey);
                    statement.setString(3, fieldValue);
                    statement.executeUpdate();
                }
            }
            touchReport(connection, reportId);
        }
    }

    public void upsertReportApplication(String reportId, ApplicationEntry application) throws SQLException {
        upsertApplication("report_applications", reportId, application);
        touchReport(reportId);
    }

    public void deleteReportApplication(String applicationId, String reportId) throws SQLException {
        deleteApplication("report_applications", applicationId, reportId);
        touchReport(reportId);
    }

    public void setPrimaryReportApplication(String reportId, String applicationId) throws SQLException {
        setPrimaryApplication("report_applications", applicationId, reportId);
        touchReport(reportId);
    }

    public ExecutionRunRecord createExecutionRun(String reportId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath())) {
            ExecutionRunRecord run = newExecutionRunRecord(
                    reportId,
                    nextScopedSortOrder(connection, "report_execution_runs", "report_id", reportId)
            );
            insertExecutionRun(connection, run);
            resequenceExecutionRuns(connection, reportId);
            touchReport(connection, reportId);
            return run;
        }
    }

    public void updateExecutionRun(ExecutionRunRecord run) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE report_execution_runs
                     SET execution_key = ?, suite_name = ?, executed_by = ?, execution_date = ?, start_date = ?, end_date = ?,
                         duration_text = ?, data_source_reference = ?, notes = ?, updated_at = ?
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
            statement.setString(10, Instant.now().toString());
            statement.setString(11, run.getId());
            statement.executeUpdate();
            touchReport(connection, run.getReportId());
        }
    }

    public void deleteExecutionRun(String reportId, String executionRunId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement("DELETE FROM report_execution_runs WHERE id = ?")) {
            statement.setString(1, executionRunId);
            statement.executeUpdate();
            resequenceExecutionRuns(connection, reportId);
            touchReport(connection, reportId);
        }
    }

    public TestCaseResultRecord createTestCaseResult(String reportId, String executionRunId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath())) {
            TestCaseResultRecord result = newTestCaseResultRecord(
                    executionRunId,
                    nextScopedSortOrder(connection, "report_test_case_results", "execution_run_id", executionRunId)
            );
            insertTestCaseResult(connection, result);
            touchReport(connection, reportId);
            return result;
        }
    }

    public void updateTestCaseResult(String reportId, TestCaseResultRecord result) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE report_test_case_results
                     SET test_case_key = ?, section_name = ?, subsection_name = ?, test_case_name = ?, priority = ?,
                         module_name = ?, status = ?, execution_time = ?, expected_result_summary = ?, actual_result = ?,
                         related_issue = ?, attachment_reference = ?, remarks = ?, blocked_reason = ?, updated_at = ?
                     WHERE id = ?
                     """)) {
            statement.setString(1, nullableText(result.getTestCaseKey()));
            statement.setString(2, nullableText(result.getSectionName()));
            statement.setString(3, nullableText(result.getSubsectionName()));
            statement.setString(4, nullableText(result.getTestCaseName()));
            statement.setString(5, nullableText(result.getPriority()));
            statement.setString(6, nullableText(result.getModuleName()));
            statement.setString(7, normalizeResultStatus(result.getStatus()));
            statement.setString(8, nullableText(result.getExecutionTime()));
            statement.setString(9, nullableText(result.getExpectedResultSummary()));
            statement.setString(10, nullableText(result.getActualResult()));
            statement.setString(11, nullableText(result.getRelatedIssue()));
            statement.setString(12, nullableText(result.getAttachmentReference()));
            statement.setString(13, nullableText(result.getRemarks()));
            statement.setString(14, nullableText(result.getBlockedReason()));
            statement.setString(15, Instant.now().toString());
            statement.setString(16, result.getId());
            statement.executeUpdate();
            touchReport(connection, reportId);
        }
    }

    public void deleteTestCaseResult(String reportId, String testCaseResultId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement("DELETE FROM report_test_case_results WHERE id = ?")) {
            statement.setString(1, testCaseResultId);
            statement.executeUpdate();
            touchReport(connection, reportId);
        }
    }

    public TestCaseStepRecord createTestCaseStep(String reportId, String testCaseResultId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath())) {
            TestCaseStepRecord step = newTestCaseStepRecord(
                    testCaseResultId,
                    nextScopedSortOrder(connection, "report_test_case_steps", "test_case_result_id", testCaseResultId)
            );
            insertTestCaseStep(connection, step);
            touchReport(connection, reportId);
            return step;
        }
    }

    public void updateTestCaseStep(String reportId, TestCaseStepRecord step) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE report_test_case_steps
                     SET step_number = ?, step_action = ?, expected_result = ?, actual_result = ?, status = ?, updated_at = ?
                     WHERE id = ?
                     """)) {
            setNullableInteger(statement, 1, step.getStepNumber());
            statement.setString(2, nullableText(step.getStepAction()));
            statement.setString(3, nullableText(step.getExpectedResult()));
            statement.setString(4, nullableText(step.getActualResult()));
            statement.setString(5, normalizeResultStatus(step.getStatus()));
            statement.setString(6, Instant.now().toString());
            statement.setString(7, step.getId());
            statement.executeUpdate();
            touchReport(connection, reportId);
        }
    }

    public void deleteTestCaseStep(String reportId, String stepId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement("DELETE FROM report_test_case_steps WHERE id = ?")) {
            statement.setString(1, stepId);
            statement.executeUpdate();
            touchReport(connection, reportId);
        }
    }

    public TestExecutionRecord createReportExecution(String reportId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath())) {
            TestExecutionRecord execution = newExecutionRecord(
                    reportId,
                    nextSortOrder(connection, "report_executions", reportId)
            );
            insertReportExecution(connection, execution);
            touchReport(connection, reportId);
            return execution;
        }
    }

    public void updateReportExecution(TestExecutionRecord execution) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath());
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
            touchReport(connection, execution.getReportId());
        }
    }

    public void deleteReportExecution(String executionId, String reportId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement("DELETE FROM report_executions WHERE id = ?")) {
            statement.setString(1, executionId);
            statement.executeUpdate();
            touchReport(connection, reportId);
        }
    }

    public void updateReportEnvironmentSnapshot(String reportId, EnvironmentRecord environmentRecord) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE report_environment_snapshots SET name = ?, type = ?, base_url = ?, os_platform = ?, " +
                             "browser_client = ?, backend_version = ?, notes = ? WHERE report_id = ?")) {
            statement.setString(1, requireText(environmentRecord.getName(), "Environment Name"));
            statement.setString(2, nullableText(environmentRecord.getType()));
            statement.setString(3, nullableText(environmentRecord.getBaseUrl()));
            statement.setString(4, nullableText(environmentRecord.getOsPlatform()));
            statement.setString(5, nullableText(environmentRecord.getBrowserClient()));
            statement.setString(6, nullableText(environmentRecord.getBackendVersion()));
            statement.setString(7, nullableText(environmentRecord.getNotes()));
            statement.setString(8, reportId);
            statement.executeUpdate();
            touchReport(connection, reportId);
        }
    }

    public void saveProject() throws IOException, SQLException {
        ProjectSession refreshedSession = refreshManifest(requireCurrentSession());
        currentSession = refreshedSession;
        writeAuxiliaryFiles();

        Path targetFile = refreshedSession.projectFile();
        Path parentDirectory = targetFile.getParent();
        if (parentDirectory != null) {
            Files.createDirectories(parentDirectory);
        }

        Path temporaryTarget = Files.createTempFile(parentDirectory, "reportforge-", ".tmp");
        try (OutputStream outputStream = Files.newOutputStream(temporaryTarget);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            List<Path> paths;
            try (var pathStream = Files.walk(refreshedSession.workspaceRoot())) {
                paths = pathStream
                        .sorted(Comparator.comparing(path -> refreshedSession.workspaceRoot().relativize(path).toString()))
                        .toList();
            }
            for (Path path : paths) {
                if (path.equals(refreshedSession.workspaceRoot())) {
                    continue;
                }
                String relativePath = refreshedSession.workspaceRoot().relativize(path).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(Files.isDirectory(path) ? relativePath + "/" : relativePath);
                zipOutputStream.putNextEntry(entry);
                if (Files.isRegularFile(path)) {
                    Files.copy(path, zipOutputStream);
                }
                zipOutputStream.closeEntry();
            }
        }

        Files.move(temporaryTarget, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private ReportRecord loadReport(String reportId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, environment_id, title, status, created_at, updated_at, last_selected_section FROM reports WHERE id = ?")) {
            statement.setString(1, reportId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("Report not found.");
                }
                return new ReportRecord(
                        resultSet.getString("id"),
                        resultSet.getString("environment_id"),
                        resultSet.getString("title"),
                        ReportStatus.fromDatabaseValue(resultSet.getString("status")),
                        resultSet.getString("created_at"),
                        resultSet.getString("updated_at"),
                        resultSet.getString("last_selected_section")
                );
            }
        }
    }

    private void updateReportColumn(String reportId, String columnName, String value) throws SQLException {
        String sql = "UPDATE reports SET " + columnName + " = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = openConnection(requireCurrentSession().databasePath());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.setString(2, Instant.now().toString());
            statement.setString(3, reportId);
            statement.executeUpdate();
        }
    }

    private void touchReport(String reportId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath())) {
            touchReport(connection, reportId);
        }
    }

    private void touchReport(Connection connection, String reportId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE reports SET updated_at = ? WHERE id = ?")) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, reportId);
            statement.executeUpdate();
        }
    }

    private void snapshotProjectOverview(Connection connection, String reportId) throws SQLException {
        try (Statement projectStatement = connection.createStatement();
             ResultSet projectResult = projectStatement.executeQuery("SELECT name, description FROM project_info LIMIT 1")) {
            if (projectResult.next()) {
                upsertReportField(connection, reportId, "projectOverview.projectName", projectResult.getString("name"));
                upsertReportField(connection, reportId, "projectOverview.projectDescription", projectResult.getString("description"));
                upsertReportField(connection, reportId, "projectOverview.reportType", "Test Execution Report");
                upsertReportField(connection, reportId, "projectOverview.preparedDate", LocalDate.now().toString());
                upsertReportField(connection, reportId, "executionSummary.overallOutcome", "NOT_EXECUTED");
            }
        }
    }

    private void snapshotApplications(Connection connection, String reportId) throws SQLException {
        try (PreparedStatement readStatement = connection.prepareStatement(
                "SELECT name, version_or_build, module_list, platform, description, related_services, is_primary, sort_order " +
                        "FROM project_applications ORDER BY sort_order, name");
             ResultSet resultSet = readStatement.executeQuery()) {
            while (resultSet.next()) {
                try (PreparedStatement insertStatement = connection.prepareStatement(
                        "INSERT INTO report_applications (id, report_id, name, version_or_build, module_list, platform, description, related_services, is_primary, sort_order) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    insertStatement.setString(1, UUID.randomUUID().toString());
                    insertStatement.setString(2, reportId);
                    insertStatement.setString(3, resultSet.getString("name"));
                    insertStatement.setString(4, resultSet.getString("version_or_build"));
                    insertStatement.setString(5, resultSet.getString("module_list"));
                    insertStatement.setString(6, resultSet.getString("platform"));
                    insertStatement.setString(7, resultSet.getString("description"));
                    insertStatement.setString(8, resultSet.getString("related_services"));
                    insertStatement.setInt(9, resultSet.getInt("is_primary"));
                    insertStatement.setInt(10, resultSet.getInt("sort_order"));
                    insertStatement.executeUpdate();
                }
            }
        }
    }

    private void snapshotEnvironment(Connection connection, String reportId, String environmentId) throws SQLException {
        try (PreparedStatement deleteStatement = connection.prepareStatement(
                "DELETE FROM report_environment_snapshots WHERE report_id = ?")) {
            deleteStatement.setString(1, reportId);
            deleteStatement.executeUpdate();
        }

        try (PreparedStatement readStatement = connection.prepareStatement(
                "SELECT name, type, base_url, os_platform, browser_client, backend_version, notes FROM environments WHERE id = ?")) {
            readStatement.setString(1, environmentId);
            try (ResultSet resultSet = readStatement.executeQuery()) {
                if (!resultSet.next()) {
                    return;
                }
                try (PreparedStatement insertStatement = connection.prepareStatement(
                        "INSERT INTO report_environment_snapshots (report_id, name, type, base_url, os_platform, browser_client, backend_version, notes) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    insertStatement.setString(1, reportId);
                    insertStatement.setString(2, resultSet.getString("name"));
                    insertStatement.setString(3, resultSet.getString("type"));
                    insertStatement.setString(4, resultSet.getString("base_url"));
                    insertStatement.setString(5, resultSet.getString("os_platform"));
                    insertStatement.setString(6, resultSet.getString("browser_client"));
                    insertStatement.setString(7, resultSet.getString("backend_version"));
                    insertStatement.setString(8, resultSet.getString("notes"));
                    insertStatement.executeUpdate();
                }
            }
        }
    }

    private void copyReportFields(Connection connection, String sourceReportId, String targetReportId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO report_fields (report_id, field_key, field_value) " +
                        "SELECT ?, field_key, field_value FROM report_fields WHERE report_id = ?")) {
            statement.setString(1, targetReportId);
            statement.setString(2, sourceReportId);
            statement.executeUpdate();
        }
    }

    private void copyReportApplications(Connection connection, String sourceReportId, String targetReportId) throws SQLException {
        try (PreparedStatement readStatement = connection.prepareStatement(
                "SELECT name, version_or_build, module_list, platform, description, related_services, is_primary, sort_order " +
                        "FROM report_applications WHERE report_id = ? ORDER BY sort_order, name")) {
            readStatement.setString(1, sourceReportId);
            try (ResultSet resultSet = readStatement.executeQuery()) {
                while (resultSet.next()) {
                    try (PreparedStatement insertStatement = connection.prepareStatement(
                            "INSERT INTO report_applications (id, report_id, name, version_or_build, module_list, platform, description, related_services, is_primary, sort_order) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                        insertStatement.setString(1, UUID.randomUUID().toString());
                        insertStatement.setString(2, targetReportId);
                        insertStatement.setString(3, resultSet.getString("name"));
                        insertStatement.setString(4, resultSet.getString("version_or_build"));
                        insertStatement.setString(5, resultSet.getString("module_list"));
                        insertStatement.setString(6, resultSet.getString("platform"));
                        insertStatement.setString(7, resultSet.getString("description"));
                        insertStatement.setString(8, resultSet.getString("related_services"));
                        insertStatement.setInt(9, resultSet.getInt("is_primary"));
                        insertStatement.setInt(10, resultSet.getInt("sort_order"));
                        insertStatement.executeUpdate();
                    }
                }
            }
        }
    }

    private void copyReportExecutions(Connection connection, String sourceReportId, String targetReportId) throws SQLException {
        migrateLegacyExecutionSummary(connection, sourceReportId);
        for (TestExecutionRecord execution : loadReportExecutions(connection, sourceReportId)) {
            String timestamp = Instant.now().toString();
            insertReportExecution(connection, new TestExecutionRecord(
                    UUID.randomUUID().toString(),
                    targetReportId,
                    execution.getStartDate(),
                    execution.getEndDate(),
                    execution.getTotalExecuted(),
                    execution.getPassedCount(),
                    execution.getFailedCount(),
                    execution.getBlockedCount(),
                    execution.getNotRunCount(),
                    execution.getDeferredCount(),
                    execution.getSkipCount(),
                    execution.getLinkedDefectCount(),
                    execution.getCycleName(),
                    execution.getExecutedBy(),
                    normalizeOutcome(execution.getOverallOutcome()),
                    execution.getExecutionWindow(),
                    execution.getDataSourceReference(),
                    execution.isBlockedExecutionFlag(),
                    execution.getSortOrder(),
                    timestamp,
                    timestamp
                ));
        }
    }

    private void copyExecutionHierarchy(Connection connection, String sourceReportId, String targetReportId) throws SQLException {
        migrateLegacyExecutionHierarchy(connection, sourceReportId);
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

            for (TestCaseResultSnapshot resultSnapshot : runSnapshot.getTestCaseResults()) {
                TestCaseResultRecord result = resultSnapshot.getResult();
                String copiedResultId = UUID.randomUUID().toString();
                insertTestCaseResult(connection, new TestCaseResultRecord(
                        copiedResultId,
                        copiedRunId,
                        result.getTestCaseKey(),
                        result.getSectionName(),
                        result.getSubsectionName(),
                        result.getTestCaseName(),
                        result.getPriority(),
                        result.getModuleName(),
                        normalizeResultStatus(result.getStatus()),
                        result.getExecutionTime(),
                        result.getExpectedResultSummary(),
                        result.getActualResult(),
                        result.getRelatedIssue(),
                        result.getAttachmentReference(),
                        result.getRemarks(),
                        result.getBlockedReason(),
                        result.getSortOrder(),
                        timestamp,
                        timestamp
                ));

                for (TestCaseStepRecord step : resultSnapshot.getSteps()) {
                    insertTestCaseStep(connection, new TestCaseStepRecord(
                            UUID.randomUUID().toString(),
                            copiedResultId,
                            step.getStepNumber(),
                            step.getStepAction(),
                            step.getExpectedResult(),
                            step.getActualResult(),
                            normalizeResultStatus(step.getStatus()),
                            step.getSortOrder(),
                            timestamp,
                            timestamp
                    ));
                }
            }
        }
    }

    private ExecutionReportSnapshot loadExecutionReportSnapshot(Connection connection, String reportId) throws SQLException {
        List<ExecutionRunRecord> runRecords = loadExecutionRuns(connection, reportId);
        Map<String, List<TestCaseResultRecord>> resultsByRun = loadTestCaseResultsByRun(connection, runRecords);
        Map<String, List<TestCaseStepRecord>> stepsByResult = loadTestCaseStepsByResult(connection, resultsByRun);
        List<ExecutionRunSnapshot> runs = new ArrayList<>();
        for (ExecutionRunRecord run : runRecords) {
            List<TestCaseResultSnapshot> results = new ArrayList<>();
            for (TestCaseResultRecord result : resultsByRun.getOrDefault(run.getId(), List.of())) {
                results.add(new TestCaseResultSnapshot(result, stepsByResult.getOrDefault(result.getId(), List.of())));
            }
            runs.add(new ExecutionRunSnapshot(run, results, buildRunMetrics(run, results)));
        }
        return new ExecutionReportSnapshot(reportId, runs, buildReportMetrics(runs));
    }

    private List<ExecutionRunRecord> loadExecutionRuns(Connection connection, String reportId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, report_id, execution_key, suite_name, executed_by, execution_date, start_date, end_date,
                       duration_text, data_source_reference, notes, legacy_total_executed, legacy_passed_count,
                       legacy_failed_count, legacy_blocked_count, legacy_not_run_count, legacy_deferred_count,
                       legacy_skipped_count, legacy_linked_defect_count, legacy_overall_outcome, sort_order,
                       created_at, updated_at
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

    private Map<String, List<TestCaseResultRecord>> loadTestCaseResultsByRun(
            Connection connection,
            List<ExecutionRunRecord> runs
    ) throws SQLException {
        Map<String, List<TestCaseResultRecord>> resultsByRun = new LinkedHashMap<>();
        if (runs.isEmpty()) {
            return resultsByRun;
        }

        String sql = """
                SELECT id, execution_run_id, test_case_key, section_name, subsection_name, test_case_name, priority,
                       module_name, status, execution_time, expected_result_summary, actual_result, related_issue,
                       attachment_reference, remarks, blocked_reason, sort_order, created_at, updated_at
                FROM report_test_case_results
                WHERE execution_run_id IN (%s)
                ORDER BY execution_run_id, sort_order, created_at
                """.formatted(placeholders(runs.size()));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < runs.size(); index++) {
                statement.setString(index + 1, runs.get(index).getId());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    TestCaseResultRecord result = new TestCaseResultRecord(
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
                    );
                    resultsByRun.computeIfAbsent(result.getExecutionRunId(), ignored -> new ArrayList<>()).add(result);
                }
            }
        }
        return resultsByRun;
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

    private Map<String, List<TestCaseStepRecord>> loadTestCaseStepsByResult(
            Connection connection,
            Map<String, List<TestCaseResultRecord>> resultsByRun
    ) throws SQLException {
        List<TestCaseResultRecord> allResults = new ArrayList<>();
        for (List<TestCaseResultRecord> results : resultsByRun.values()) {
            allResults.addAll(results);
        }

        Map<String, List<TestCaseStepRecord>> stepsByResult = new LinkedHashMap<>();
        if (allResults.isEmpty()) {
            return stepsByResult;
        }

        String sql = """
                SELECT id, test_case_result_id, step_number, step_action, expected_result, actual_result, status,
                       sort_order, created_at, updated_at
                FROM report_test_case_steps
                WHERE test_case_result_id IN (%s)
                ORDER BY test_case_result_id, sort_order, created_at
                """.formatted(placeholders(allResults.size()));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < allResults.size(); index++) {
                statement.setString(index + 1, allResults.get(index).getId());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    TestCaseStepRecord step = new TestCaseStepRecord(
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
                    );
                    stepsByResult.computeIfAbsent(step.getTestCaseResultId(), ignored -> new ArrayList<>()).add(step);
                }
            }
        }
        return stepsByResult;
    }

    private void insertExecutionRun(Connection connection, ExecutionRunRecord run) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO report_execution_runs (
                    id, report_id, execution_key, suite_name, executed_by, execution_date, start_date, end_date,
                    duration_text, data_source_reference, notes, legacy_total_executed, legacy_passed_count,
                    legacy_failed_count, legacy_blocked_count, legacy_not_run_count, legacy_deferred_count,
                    legacy_skipped_count, legacy_linked_defect_count, legacy_overall_outcome, sort_order, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            setNullableInteger(statement, 12, run.getLegacyTotalExecuted());
            setNullableInteger(statement, 13, run.getLegacyPassedCount());
            setNullableInteger(statement, 14, run.getLegacyFailedCount());
            setNullableInteger(statement, 15, run.getLegacyBlockedCount());
            setNullableInteger(statement, 16, run.getLegacyNotRunCount());
            setNullableInteger(statement, 17, run.getLegacyDeferredCount());
            setNullableInteger(statement, 18, run.getLegacySkippedCount());
            setNullableInteger(statement, 19, run.getLegacyLinkedDefectCount());
            statement.setString(20, normalizeOutcome(run.getLegacyOverallOutcome()));
            statement.setInt(21, run.getSortOrder());
            statement.setString(22, run.getCreatedAt());
            statement.setString(23, run.getUpdatedAt());
            statement.executeUpdate();
        }
    }

    private void insertTestCaseResult(Connection connection, TestCaseResultRecord result) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO report_test_case_results (
                    id, execution_run_id, test_case_key, section_name, subsection_name, test_case_name, priority,
                    module_name, status, execution_time, expected_result_summary, actual_result, related_issue,
                    attachment_reference, remarks, blocked_reason, sort_order, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, result.getId());
            statement.setString(2, result.getExecutionRunId());
            statement.setString(3, nullableText(result.getTestCaseKey()));
            statement.setString(4, nullableText(result.getSectionName()));
            statement.setString(5, nullableText(result.getSubsectionName()));
            statement.setString(6, nullableText(result.getTestCaseName()));
            statement.setString(7, nullableText(result.getPriority()));
            statement.setString(8, nullableText(result.getModuleName()));
            statement.setString(9, normalizeResultStatus(result.getStatus()));
            statement.setString(10, nullableText(result.getExecutionTime()));
            statement.setString(11, nullableText(result.getExpectedResultSummary()));
            statement.setString(12, nullableText(result.getActualResult()));
            statement.setString(13, nullableText(result.getRelatedIssue()));
            statement.setString(14, nullableText(result.getAttachmentReference()));
            statement.setString(15, nullableText(result.getRemarks()));
            statement.setString(16, nullableText(result.getBlockedReason()));
            statement.setInt(17, result.getSortOrder());
            statement.setString(18, result.getCreatedAt());
            statement.setString(19, result.getUpdatedAt());
            statement.executeUpdate();
        }
    }

    private void insertTestCaseStep(Connection connection, TestCaseStepRecord step) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO report_test_case_steps (
                    id, test_case_result_id, step_number, step_action, expected_result, actual_result, status,
                    sort_order, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, step.getId());
            statement.setString(2, step.getTestCaseResultId());
            setNullableInteger(statement, 3, step.getStepNumber());
            statement.setString(4, nullableText(step.getStepAction()));
            statement.setString(5, nullableText(step.getExpectedResult()));
            statement.setString(6, nullableText(step.getActualResult()));
            statement.setString(7, normalizeResultStatus(step.getStatus()));
            statement.setInt(8, step.getSortOrder());
            statement.setString(9, step.getCreatedAt());
            statement.setString(10, step.getUpdatedAt());
            statement.executeUpdate();
        }
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

    private ExecutionMetrics buildRunMetrics(ExecutionRunRecord run, List<TestCaseResultSnapshot> results) {
        if (results.isEmpty() && hasLegacyRunMetrics(run)) {
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

        int passed = 0;
        int failed = 0;
        int blocked = 0;
        int notRun = 0;
        int deferred = 0;
        int skipped = 0;
        int issues = 0;
        int evidence = 0;
        LinkedHashSet<String> statuses = new LinkedHashSet<>();

        for (TestCaseResultSnapshot resultSnapshot : results) {
            TestCaseResultRecord result = resultSnapshot.getResult();
            String normalizedStatus = normalizeResultStatus(result.getStatus());
            statuses.add(normalizedStatus);
            switch (normalizedStatus) {
                case "PASS" -> passed++;
                case "FAIL" -> failed++;
                case "BLOCKED" -> blocked++;
                case "DEFERRED" -> deferred++;
                case "SKIPPED" -> skipped++;
                default -> notRun++;
            }
            if (!nullableText(result.getRelatedIssue()).isBlank()) {
                issues++;
            }
            if (!nullableText(result.getAttachmentReference()).isBlank()) {
                evidence++;
            }
        }

        return new ExecutionMetrics(
                1,
                results.size(),
                passed,
                failed,
                blocked,
                notRun,
                deferred,
                skipped,
                issues,
                evidence,
                aggregateOutcome(statuses),
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
        return new ExecutionRunRecord(
                UUID.randomUUID().toString(),
                reportId,
                "",
                "",
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

    private TestCaseResultRecord newTestCaseResultRecord(String executionRunId, int sortOrder) {
        String timestamp = Instant.now().toString();
        return new TestCaseResultRecord(
                UUID.randomUUID().toString(),
                executionRunId,
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
                sortOrder,
                timestamp,
                timestamp
        );
    }

    private TestCaseStepRecord newTestCaseStepRecord(String testCaseResultId, int sortOrder) {
        String timestamp = Instant.now().toString();
        return new TestCaseStepRecord(
                UUID.randomUUID().toString(),
                testCaseResultId,
                sortOrder + 1,
                "",
                "",
                "",
                "NOT_RUN",
                sortOrder,
                timestamp,
                timestamp
        );
    }

    private void upsertReportField(Connection connection, String reportId, String fieldKey, String fieldValue) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO report_fields (report_id, field_key, field_value) VALUES (?, ?, ?) " +
                        "ON CONFLICT(report_id, field_key) DO UPDATE SET field_value = excluded.field_value")) {
            statement.setString(1, reportId);
            statement.setString(2, fieldKey);
            statement.setString(3, fieldValue);
            statement.executeUpdate();
        }
    }

    private void upsertApplication(String tableName, String reportId, ApplicationEntry application) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath())) {
            String applicationId = application.getId() == null || application.getId().isBlank()
                    ? UUID.randomUUID().toString()
                    : application.getId();
            int sortOrder = application.getSortOrder() >= 0
                    ? application.getSortOrder()
                    : nextSortOrder(connection, tableName, reportId);
            String reportColumnPrefix = "report_applications".equals(tableName) ? "report_id, " : "";
            String reportValuePrefix = "report_applications".equals(tableName) ? "?, " : "";
            String reportUpdateClause = "report_applications".equals(tableName) ? "report_id = excluded.report_id, " : "";

            String sql = "INSERT INTO " + tableName + " (id, " + reportColumnPrefix +
                    "name, version_or_build, module_list, platform, description, related_services, is_primary, sort_order) " +
                    "VALUES (?, " + reportValuePrefix + "?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(id) DO UPDATE SET " + reportUpdateClause +
                    "name = excluded.name, version_or_build = excluded.version_or_build, module_list = excluded.module_list, " +
                    "platform = excluded.platform, description = excluded.description, related_services = excluded.related_services, " +
                    "is_primary = excluded.is_primary, sort_order = excluded.sort_order";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                statement.setString(index++, applicationId);
                if ("report_applications".equals(tableName)) {
                    statement.setString(index++, reportId);
                }
                statement.setString(index++, requireText(application.getName(), "Application Name"));
                statement.setString(index++, nullableText(application.getVersionOrBuild()));
                statement.setString(index++, nullableText(application.getModuleList()));
                statement.setString(index++, nullableText(application.getPlatform()));
                statement.setString(index++, nullableText(application.getDescription()));
                statement.setString(index++, nullableText(application.getRelatedServices()));
                statement.setInt(index++, application.isPrimary() ? 1 : 0);
                statement.setInt(index, sortOrder);
                statement.executeUpdate();
            }

            if (application.isPrimary()) {
                setPrimaryApplication(connection, tableName, applicationId, reportId);
            }
        }
    }

    private void deleteApplication(String tableName, String applicationId, String reportId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath())) {
            boolean wasPrimary = false;
            String reportCondition = "report_applications".equals(tableName) ? " AND report_id = ?" : "";
            try (PreparedStatement readStatement = connection.prepareStatement(
                    "SELECT is_primary FROM " + tableName + " WHERE id = ?" + reportCondition)) {
                readStatement.setString(1, applicationId);
                if ("report_applications".equals(tableName)) {
                    readStatement.setString(2, reportId);
                }
                try (ResultSet resultSet = readStatement.executeQuery()) {
                    if (resultSet.next()) {
                        wasPrimary = resultSet.getInt("is_primary") == 1;
                    }
                }
            }

            try (PreparedStatement deleteStatement = connection.prepareStatement(
                    "DELETE FROM " + tableName + " WHERE id = ?" + reportCondition)) {
                deleteStatement.setString(1, applicationId);
                if ("report_applications".equals(tableName)) {
                    deleteStatement.setString(2, reportId);
                }
                deleteStatement.executeUpdate();
            }

            if (wasPrimary) {
                String selectSql = "SELECT id FROM " + tableName +
                        ("report_applications".equals(tableName) ? " WHERE report_id = ? " : " ") +
                        "ORDER BY sort_order, name LIMIT 1";
                try (PreparedStatement selectStatement = connection.prepareStatement(selectSql)) {
                    if ("report_applications".equals(tableName)) {
                        selectStatement.setString(1, reportId);
                    }
                    try (ResultSet resultSet = selectStatement.executeQuery()) {
                        if (resultSet.next()) {
                            setPrimaryApplication(connection, tableName, resultSet.getString("id"), reportId);
                        }
                    }
                }
            }
        }
    }

    private void setPrimaryApplication(String tableName, String applicationId, String reportId) throws SQLException {
        try (Connection connection = openConnection(requireCurrentSession().databasePath())) {
            setPrimaryApplication(connection, tableName, applicationId, reportId);
        }
    }

    private void setPrimaryApplication(Connection connection, String tableName, String applicationId, String reportId) throws SQLException {
        String reportCondition = "report_applications".equals(tableName) ? " WHERE report_id = ? " : "";
        try (PreparedStatement resetStatement = connection.prepareStatement(
                "UPDATE " + tableName + " SET is_primary = 0" + reportCondition)) {
            if ("report_applications".equals(tableName)) {
                resetStatement.setString(1, reportId);
            }
            resetStatement.executeUpdate();
        }

        String primaryCondition = "report_applications".equals(tableName) ? " AND report_id = ? " : "";
        try (PreparedStatement updateStatement = connection.prepareStatement(
                "UPDATE " + tableName + " SET is_primary = 1 WHERE id = ?" + primaryCondition)) {
            updateStatement.setString(1, applicationId);
            if ("report_applications".equals(tableName)) {
                updateStatement.setString(2, reportId);
            }
            updateStatement.executeUpdate();
        }
    }

    private ProjectSummary loadProjectSummary(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT id, name, description, created_at, updated_at FROM project_info LIMIT 1")) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Project data is missing.");
            }
            return new ProjectSummary(
                    resultSet.getString("id"),
                    resultSet.getString("name"),
                    resultSet.getString("description"),
                    resultSet.getString("created_at"),
                    resultSet.getString("updated_at")
            );
        }
    }

    private List<ApplicationEntry> loadApplications(Connection connection, String tableName, String reportId) throws SQLException {
        String sql = "SELECT id, name, version_or_build, module_list, platform, description, related_services, is_primary, sort_order " +
                "FROM " + tableName +
                ("report_applications".equals(tableName) ? " WHERE report_id = ? " : " ") +
                "ORDER BY sort_order, name";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if ("report_applications".equals(tableName)) {
                statement.setString(1, reportId);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ApplicationEntry> applications = new ArrayList<>();
                while (resultSet.next()) {
                    applications.add(new ApplicationEntry(
                            resultSet.getString("id"),
                            resultSet.getString("name"),
                            resultSet.getString("version_or_build"),
                            resultSet.getString("module_list"),
                            resultSet.getString("platform"),
                            resultSet.getString("description"),
                            resultSet.getString("related_services"),
                            resultSet.getInt("is_primary") == 1,
                            resultSet.getInt("sort_order")
                    ));
                }
                return applications;
            }
        }
    }

    private List<EnvironmentRecord> loadEnvironments(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT id, name, type, base_url, os_platform, browser_client, backend_version, notes, sort_order " +
                             "FROM environments ORDER BY sort_order, name")) {
            List<EnvironmentRecord> environments = new ArrayList<>();
            while (resultSet.next()) {
                environments.add(new EnvironmentRecord(
                        resultSet.getString("id"),
                        resultSet.getString("name"),
                        resultSet.getString("type"),
                        resultSet.getString("base_url"),
                        resultSet.getString("os_platform"),
                        resultSet.getString("browser_client"),
                        resultSet.getString("backend_version"),
                        resultSet.getString("notes"),
                        resultSet.getInt("sort_order")
                ));
            }
            return environments;
        }
    }

    private Map<String, List<ReportRecord>> loadReportsByEnvironment(Connection connection) throws SQLException {
        Map<String, List<ReportRecord>> reportsByEnvironment = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT id, environment_id, title, status, created_at, updated_at, last_selected_section " +
                             "FROM reports ORDER BY created_at, title")) {
            while (resultSet.next()) {
                ReportRecord reportRecord = new ReportRecord(
                        resultSet.getString("id"),
                        resultSet.getString("environment_id"),
                        resultSet.getString("title"),
                        ReportStatus.fromDatabaseValue(resultSet.getString("status")),
                        resultSet.getString("created_at"),
                        resultSet.getString("updated_at"),
                        resultSet.getString("last_selected_section")
                );
                reportsByEnvironment.computeIfAbsent(reportRecord.getEnvironmentId(), ignored -> new ArrayList<>())
                        .add(reportRecord);
            }
        }
        return reportsByEnvironment;
    }

    private void insertInitialApplications(Connection connection, List<String> applicationNames) throws SQLException {
        if (applicationNames == null || applicationNames.isEmpty()) {
            return;
        }

        int sortOrder = 0;
        boolean primaryAssigned = false;
        for (String applicationName : applicationNames) {
            if (applicationName == null || applicationName.isBlank()) {
                continue;
            }
            boolean primary = !primaryAssigned;
            primaryAssigned = true;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO project_applications (id, name, version_or_build, module_list, platform, description, related_services, is_primary, sort_order) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, applicationName.trim());
                statement.setString(3, "");
                statement.setString(4, "");
                statement.setString(5, "");
                statement.setString(6, "");
                statement.setString(7, "");
                statement.setInt(8, primary ? 1 : 0);
                statement.setInt(9, sortOrder++);
                statement.executeUpdate();
            }
        }
    }

    private void insertEnvironment(Connection connection, EnvironmentRecord environment) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO environments (id, name, type, base_url, os_platform, browser_client, backend_version, notes, sort_order) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, environment.getId());
            statement.setString(2, requireText(environment.getName(), "Environment Name"));
            statement.setString(3, nullableText(environment.getType()));
            statement.setString(4, nullableText(environment.getBaseUrl()));
            statement.setString(5, nullableText(environment.getOsPlatform()));
            statement.setString(6, nullableText(environment.getBrowserClient()));
            statement.setString(7, nullableText(environment.getBackendVersion()));
            statement.setString(8, nullableText(environment.getNotes()));
            statement.setInt(9, environment.getSortOrder());
            statement.executeUpdate();
        }
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

    private int nextSortOrder(Connection connection, String tableName, String reportId) throws SQLException {
        String sql = "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM " + tableName +
                (requiresReportScope(tableName) ? " WHERE report_id = ? " : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (requiresReportScope(tableName)) {
                statement.setString(1, reportId);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private int nextScopedSortOrder(Connection connection, String tableName, String scopeColumn, String scopeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM " + tableName + " WHERE " + scopeColumn + " = ?")) {
            statement.setString(1, scopeId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private void resequenceExecutionRuns(Connection connection, String reportId) throws SQLException {
        List<String> runIds = new ArrayList<>();
        try (PreparedStatement readStatement = connection.prepareStatement("""
                SELECT id
                FROM report_execution_runs
                WHERE report_id = ?
                ORDER BY sort_order, created_at, id
                """)) {
            readStatement.setString(1, reportId);
            try (ResultSet resultSet = readStatement.executeQuery()) {
                while (resultSet.next()) {
                    runIds.add(resultSet.getString("id"));
                }
            }
        }

        if (runIds.isEmpty()) {
            return;
        }

        try (PreparedStatement updateStatement = connection.prepareStatement(
                "UPDATE report_execution_runs SET sort_order = ? WHERE id = ?")) {
            for (int index = 0; index < runIds.size(); index++) {
                updateStatement.setInt(1, index);
                updateStatement.setString(2, runIds.get(index));
                updateStatement.addBatch();
            }
            updateStatement.executeBatch();
        }
    }

    private boolean requiresReportScope(String tableName) {
        return "report_applications".equals(tableName) || "report_executions".equals(tableName);
    }

    private int countRows(Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private ProjectSession refreshManifest(ProjectSession session) throws SQLException {
        ProjectSummary projectSummary = loadWorkspace().getProject();
        String createdAt = session.manifestData().createdAt();
        String updatedAt = Instant.now().toString();
        return new ProjectSession(
                session.projectFile(),
                session.workspaceRoot(),
                new ManifestData(
                        FORMAT_VERSION,
                        APP_VERSION,
                        projectSummary.getId(),
                        projectSummary.getName(),
                        DEFAULT_DATABASE_PATH,
                        createdAt,
                        updatedAt
                )
        );
    }

    private void writeAuxiliaryFiles() throws IOException {
        ProjectSession session = requireCurrentSession();
        writeManifest(session.workspaceRoot().resolve("manifest.json"), session.manifestData());
        writeTextFile(session.workspaceRoot().resolve("templates").resolve("branding.json"), DEFAULT_BRANDING_JSON);
        writeTextFile(session.workspaceRoot().resolve("settings").resolve("export-presets.json"), DEFAULT_EXPORT_PRESETS_JSON);
        writeChecksums(session.workspaceRoot().resolve("meta").resolve("checksums.json"), buildChecksums(session.workspaceRoot()));
    }

    private ChecksumsData buildChecksums(Path workspaceRoot) throws IOException {
        Path manifestPath = workspaceRoot.resolve("manifest.json");
        Path databasePath = workspaceRoot.resolve(DEFAULT_DATABASE_PATH.replace("/", "\\"));
        Map<String, String> checksums = new LinkedHashMap<>();
        checksums.put("manifest.json", sha256(manifestPath));
        checksums.put(DEFAULT_DATABASE_PATH, sha256(databasePath));
        return new ChecksumsData(checksums);
    }

    private void validateChecksums(Path workspaceRoot) throws IOException {
        Path checksumPath = workspaceRoot.resolve("meta").resolve("checksums.json");
        if (!Files.exists(checksumPath)) {
            return;
        }
        ChecksumsData checksumsData = readChecksums(checksumPath);
        if (checksumsData == null || checksumsData.files == null) {
            throw new IOException("Failed to read project data.");
        }
        for (Map.Entry<String, String> entry : checksumsData.files.entrySet()) {
            Path filePath = workspaceRoot.resolve(entry.getKey().replace("/", "\\"));
            if (!Files.exists(filePath) || !Objects.equals(entry.getValue(), sha256(filePath))) {
                throw new IOException("Failed to read project data.");
            }
        }
    }

    private void extractProjectContainer(Path projectFile, Path workspaceRoot) throws IOException {
        int fileCount = 0;
        long totalSize = 0L;

        try (InputStream inputStream = Files.newInputStream(projectFile);
             BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
             ZipInputStream zipInputStream = new ZipInputStream(bufferedInputStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String normalizedEntryName = normalizeZipEntryName(entry.getName());
                if (normalizedEntryName.isBlank()) {
                    zipInputStream.closeEntry();
                    continue;
                }
                if (!isAllowedEntry(normalizedEntryName)) {
                    throw new IOException("Failed to read project data.");
                }
                fileCount++;
                if (fileCount > MAX_FILE_COUNT) {
                    throw new IOException("Failed to read project data.");
                }

                Path outputPath = workspaceRoot.resolve(normalizedEntryName.replace("/", "\\")).normalize();
                if (!outputPath.startsWith(workspaceRoot)) {
                    throw new IOException("Failed to read project data.");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                    zipInputStream.closeEntry();
                    continue;
                }

                Files.createDirectories(outputPath.getParent());
                long entryBytes = copyWithLimit(zipInputStream, outputPath, MAX_ENTRY_SIZE);
                totalSize += entryBytes;
                if (totalSize > MAX_TOTAL_UNCOMPRESSED_SIZE) {
                    throw new IOException("Failed to read project data.");
                }
                zipInputStream.closeEntry();
            }
        }
    }

    private long copyWithLimit(InputStream inputStream, Path outputPath, long maxBytes) throws IOException {
        long totalCopied = 0L;
        byte[] buffer = new byte[8192];
        try (OutputStream outputStream = Files.newOutputStream(outputPath)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                totalCopied += read;
                if (totalCopied > maxBytes) {
                    throw new IOException("Failed to read project data.");
                }
                outputStream.write(buffer, 0, read);
            }
        }
        return totalCopied;
    }

    private boolean isAllowedEntry(String entryName) {
        return entryName.equals("manifest.json")
                || entryName.equals(DEFAULT_DATABASE_PATH)
                || entryName.equals("templates/branding.json")
                || entryName.equals("settings/export-presets.json")
                || entryName.equals("meta/checksums.json")
                || entryName.startsWith("evidence/")
                || entryName.startsWith("templates/")
                || entryName.startsWith("settings/")
                || entryName.startsWith("meta/")
                || entryName.startsWith("data/");
    }

    private String normalizeZipEntryName(String entryName) {
        String normalized = entryName.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.contains("..")) {
            return "";
        }
        return normalized;
    }

    private void initializeWorkspaceDirectories(Path workspaceRoot) throws IOException {
        Files.createDirectories(workspaceRoot.resolve("data"));
        Files.createDirectories(workspaceRoot.resolve("evidence"));
        Files.createDirectories(workspaceRoot.resolve("templates"));
        Files.createDirectories(workspaceRoot.resolve("settings"));
        Files.createDirectories(workspaceRoot.resolve("meta"));
    }

    private void initializeSchema(Path databasePath) throws SQLException {
        try (Connection connection = openConnection(databasePath);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS project_info (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS project_applications (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        version_or_build TEXT,
                        module_list TEXT,
                        platform TEXT,
                        description TEXT,
                        related_services TEXT,
                        is_primary INTEGER NOT NULL DEFAULT 0,
                        sort_order INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS environments (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        type TEXT,
                        base_url TEXT,
                        os_platform TEXT,
                        browser_client TEXT,
                        backend_version TEXT,
                        notes TEXT,
                        sort_order INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS reports (
                        id TEXT PRIMARY KEY,
                        environment_id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        report_type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        last_selected_section TEXT NOT NULL,
                        FOREIGN KEY(environment_id) REFERENCES environments(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS report_fields (
                        report_id TEXT NOT NULL,
                        field_key TEXT NOT NULL,
                        field_value TEXT NOT NULL,
                        PRIMARY KEY(report_id, field_key),
                        FOREIGN KEY(report_id) REFERENCES reports(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS report_applications (
                        id TEXT PRIMARY KEY,
                        report_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        version_or_build TEXT,
                        module_list TEXT,
                        platform TEXT,
                        description TEXT,
                        related_services TEXT,
                        is_primary INTEGER NOT NULL DEFAULT 0,
                        sort_order INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(report_id) REFERENCES reports(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS report_environment_snapshots (
                        report_id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        type TEXT,
                        base_url TEXT,
                        os_platform TEXT,
                        browser_client TEXT,
                        backend_version TEXT,
                        notes TEXT,
                        FOREIGN KEY(report_id) REFERENCES reports(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS report_executions (
                        id TEXT PRIMARY KEY,
                        report_id TEXT NOT NULL,
                        start_date TEXT,
                        end_date TEXT,
                        total_executed INTEGER,
                        passed_count INTEGER,
                        failed_count INTEGER,
                        blocked_count INTEGER,
                        not_run_count INTEGER,
                        deferred_count INTEGER,
                        skip_count INTEGER,
                        linked_defect_count INTEGER,
                        cycle_name TEXT,
                        executed_by TEXT,
                        overall_outcome TEXT NOT NULL DEFAULT 'NOT_EXECUTED',
                        execution_window TEXT,
                        data_source_reference TEXT,
                        blocked_execution_flag INTEGER NOT NULL DEFAULT 0,
                        sort_order INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY(report_id) REFERENCES reports(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS report_execution_runs (
                        id TEXT PRIMARY KEY,
                        report_id TEXT NOT NULL,
                        execution_key TEXT,
                        suite_name TEXT,
                        executed_by TEXT,
                        execution_date TEXT,
                        start_date TEXT,
                        end_date TEXT,
                        duration_text TEXT,
                        data_source_reference TEXT,
                        notes TEXT,
                        legacy_total_executed INTEGER,
                        legacy_passed_count INTEGER,
                        legacy_failed_count INTEGER,
                        legacy_blocked_count INTEGER,
                        legacy_not_run_count INTEGER,
                        legacy_deferred_count INTEGER,
                        legacy_skipped_count INTEGER,
                        legacy_linked_defect_count INTEGER,
                        legacy_overall_outcome TEXT,
                        sort_order INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY(report_id) REFERENCES reports(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_execution_runs_report_id ON report_execution_runs(report_id)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS report_test_case_results (
                        id TEXT PRIMARY KEY,
                        execution_run_id TEXT NOT NULL,
                        test_case_key TEXT,
                        section_name TEXT,
                        subsection_name TEXT,
                        test_case_name TEXT,
                        priority TEXT,
                        module_name TEXT,
                        status TEXT NOT NULL DEFAULT 'NOT_RUN',
                        execution_time TEXT,
                        expected_result_summary TEXT,
                        actual_result TEXT,
                        related_issue TEXT,
                        attachment_reference TEXT,
                        remarks TEXT,
                        blocked_reason TEXT,
                        sort_order INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY(execution_run_id) REFERENCES report_execution_runs(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_test_case_results_run_id ON report_test_case_results(execution_run_id)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS report_test_case_steps (
                        id TEXT PRIMARY KEY,
                        test_case_result_id TEXT NOT NULL,
                        step_number INTEGER,
                        step_action TEXT,
                        expected_result TEXT,
                        actual_result TEXT,
                        status TEXT NOT NULL DEFAULT 'NOT_RUN',
                        sort_order INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY(test_case_result_id) REFERENCES report_test_case_results(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_test_case_steps_result_id ON report_test_case_steps(test_case_result_id)");
        }
    }

    private Connection openConnection(Path databasePath) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    private String nullableText(String value) {
        return value == null ? "" : value.trim();
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be empty.");
        }
        return value.trim();
    }

    private ProjectSession requireCurrentSession() {
        if (currentSession == null) {
            throw new IllegalStateException("No project is currently open.");
        }
        return currentSession;
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

    private void setNullableInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
            return;
        }
        statement.setInt(index, value);
    }

    private Integer toNullableInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Integer readNullableInteger(ResultSet resultSet, String columnName) throws SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private String selectBoundaryDate(String currentValue, String candidateValue, boolean earliest) {
        if (candidateValue == null || candidateValue.isBlank()) {
            return currentValue == null ? "" : currentValue;
        }
        if (currentValue == null || currentValue.isBlank()) {
            return candidateValue;
        }
        LocalDate currentDate = parseDateOrNull(currentValue);
        LocalDate candidateDate = parseDateOrNull(candidateValue);
        if (currentDate == null) {
            return candidateValue;
        }
        if (candidateDate == null) {
            return currentValue;
        }
        return earliest
                ? (candidateDate.isBefore(currentDate) ? candidateValue : currentValue)
                : (candidateDate.isAfter(currentDate) ? candidateValue : currentValue);
    }

    private LocalDate parseDateOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String normalizeOutcome(String outcome) {
        return outcome == null || outcome.isBlank() ? "NOT_EXECUTED" : outcome;
    }

    private String normalizeResultStatus(String status) {
        if (status == null || status.isBlank()) {
            return "NOT_RUN";
        }
        return switch (status.trim().toUpperCase()) {
            case "PASSED" -> "PASS";
            case "FAILED" -> "FAIL";
            case "SKIPPED", "SKIP" -> "SKIPPED";
            default -> status.trim().toUpperCase();
        };
    }

    private String aggregateOutcome(LinkedHashSet<String> outcomes) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String outcome : outcomes) {
            normalized.add(normalizeOutcome(outcome));
        }
        if (normalized.isEmpty()) {
            return "NOT_EXECUTED";
        }
        return normalized.size() == 1 ? normalized.iterator().next() : "MIXED";
    }

    private Path ensureProjectExtension(Path requestedTargetFile) {
        if (requestedTargetFile == null) {
            throw new IllegalArgumentException("Project file path is required.");
        }
        String fileName = requestedTargetFile.getFileName().toString();
        if (fileName.toLowerCase().endsWith(PROJECT_EXTENSION)) {
            return requestedTargetFile;
        }
        return requestedTargetFile.resolveSibling(fileName + PROJECT_EXTENSION);
    }

    private void writeTextFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private void writeManifest(Path path, ManifestData manifestData) throws IOException {
        writeJsonFile(path, manifestData);
    }

    private ManifestData readManifest(Path path) throws IOException {
        return readJsonFile(path, ManifestData.class);
    }

    private void writeChecksums(Path path, ChecksumsData checksumsData) throws IOException {
        writeJsonFile(path, checksumsData);
    }

    private ChecksumsData readChecksums(Path path) throws IOException {
        return readJsonFile(path, ChecksumsData.class);
    }

    private void writeJsonFile(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private <T> T readJsonFile(Path path, Class<T> type) throws IOException {
        return objectMapper.readValue(path.toFile(), type);
    }

    private String sha256(Path path) throws IOException {
        try (InputStream inputStream = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder builder = new StringBuilder();
            for (byte value : digest.digest()) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("Unable to compute checksums.", exception);
        }
    }

    private void deleteDirectorySilently(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        FileVisitor<Path> visitor = new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        };
        try {
            Files.walkFileTree(root, visitor);
        } catch (IOException ignored) {
            // Best-effort cleanup for temporary working directories.
        }
    }

    public record ProjectSession(Path projectFile, Path workspaceRoot, ManifestData manifestData) {
        public Path databasePath() {
            return workspaceRoot.resolve(manifestData.databasePath().replace("/", "\\"));
        }
    }

    public record ManifestData(
            String formatVersion,
            String appVersion,
            String projectId,
            String projectName,
            String databasePath,
            String createdAt,
            String updatedAt
    ) { }

    public record ChecksumsData(Map<String, String> files) { }
}
