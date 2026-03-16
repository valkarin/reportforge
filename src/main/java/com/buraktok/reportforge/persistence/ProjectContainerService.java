package com.buraktok.reportforge.persistence;

import com.buraktok.reportforge.model.ApplicationEntry;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ProjectSummary;
import com.buraktok.reportforge.model.ProjectWorkspace;
import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.ReportStatus;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

    private int nextSortOrder(Connection connection, String tableName, String reportId) throws SQLException {
        String sql = "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM " + tableName +
                ("report_applications".equals(tableName) ? " WHERE report_id = ? " : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if ("report_applications".equals(tableName)) {
                statement.setString(1, reportId);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
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
