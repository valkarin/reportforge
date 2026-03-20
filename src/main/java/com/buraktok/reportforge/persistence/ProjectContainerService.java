package com.buraktok.reportforge.persistence;

import com.buraktok.reportforge.model.ApplicationEntry;
import com.buraktok.reportforge.model.ExecutionReportSnapshot;
import com.buraktok.reportforge.model.ExecutionRunEvidenceRecord;
import com.buraktok.reportforge.model.ExecutionRunRecord;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ProjectSummary;
import com.buraktok.reportforge.model.ProjectWorkspace;
import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.ReportStatus;
import com.buraktok.reportforge.model.TestExecutionRecord;
import com.buraktok.reportforge.model.TestExecutionSection;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.buraktok.reportforge.persistence.PersistenceSupport.openConnection;
import static com.buraktok.reportforge.persistence.PersistenceSupport.requireText;

public final class ProjectContainerService {
    public static final String PROJECT_EXTENSION = ".rforge";

    static final String APP_VERSION = "0.1.0";
    static final String FORMAT_VERSION = "1";
    static final int MAX_FILE_COUNT = 512;
    static final long MAX_TOTAL_UNCOMPRESSED_SIZE = 256L * 1024L * 1024L;
    static final long MAX_ENTRY_SIZE = 64L * 1024L * 1024L;
    static final String DEFAULT_DATABASE_PATH = "data/project.db";
    static final String DEFAULT_BRANDING_JSON = "{\n  \"companyName\": \"\",\n  \"logoPath\": \"\",\n  \"colorTheme\": \"\",\n  \"footerText\": \"\"\n}\n";
    static final String DEFAULT_EXPORT_PRESETS_JSON = "{\n  \"presets\": []\n}\n";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProjectContainerFiles containerFiles = new ProjectContainerFiles(objectMapper);
    private final ProjectSchemaManager schemaManager = new ProjectSchemaManager();
    private final ProjectWorkspaceStore workspaceStore = new ProjectWorkspaceStore();
    private final ProjectExecutionStore executionStore = new ProjectExecutionStore(containerFiles, workspaceStore);
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
            containerFiles.initializeWorkspaceDirectories(workspaceRoot);
            Path databasePath = workspaceRoot.resolve(DEFAULT_DATABASE_PATH.replace("/", "\\"));
            schemaManager.initializeSchema(databasePath);
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

                workspaceStore.insertInitialApplications(connection, applicationNames);
                if (initialEnvironmentName != null && !initialEnvironmentName.isBlank()) {
                    workspaceStore.insertEnvironment(connection, new EnvironmentRecord(
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
            containerFiles.writeAuxiliaryFiles(session);
            saveProject();
            return session;
        } catch (IOException | SQLException exception) {
            if (session == null) {
                containerFiles.deleteDirectorySilently(workspaceRoot);
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
            containerFiles.initializeWorkspaceDirectories(workspaceRoot);
            containerFiles.extractProjectContainer(absoluteProjectPath, workspaceRoot);

            Path manifestPath = workspaceRoot.resolve("manifest.json");
            Path databasePath = workspaceRoot.resolve(DEFAULT_DATABASE_PATH.replace("/", "\\"));
            if (!Files.exists(manifestPath) || !Files.exists(databasePath)) {
                throw new IOException("Failed to read project data.");
            }

            ManifestData manifestData = containerFiles.readManifest(manifestPath);
            if (manifestData == null || !Objects.equals(manifestData.databasePath(), DEFAULT_DATABASE_PATH)) {
                throw new IOException("Failed to read project data.");
            }

            containerFiles.validateChecksums(workspaceRoot);
            schemaManager.initializeSchema(databasePath);
            try (Connection ignored = openConnection(databasePath)) {
                // Verifies that SQLite can read the project database.
            }

            currentSession = new ProjectSession(absoluteProjectPath, workspaceRoot, manifestData);
            return currentSession;
        } catch (IOException | SQLException exception) {
            containerFiles.deleteDirectorySilently(workspaceRoot);
            currentSession = null;
            throw exception;
        }
    }

    public void closeCurrentSession() {
        if (currentSession != null) {
            containerFiles.deleteDirectorySilently(currentSession.workspaceRoot());
            currentSession = null;
        }
    }

    public ProjectSession getCurrentSession() {
        return currentSession;
    }

    public ProjectWorkspace loadWorkspace() throws SQLException {
        return workspaceStore.loadWorkspace(requireCurrentSession());
    }

    public Map<String, String> loadReportFields(String reportId) throws SQLException {
        return workspaceStore.loadReportFields(requireCurrentSession(), reportId);
    }

    public List<ApplicationEntry> loadReportApplications(String reportId) throws SQLException {
        return workspaceStore.loadReportApplications(requireCurrentSession(), reportId);
    }

    public List<TestExecutionRecord> loadReportExecutions(String reportId) throws SQLException {
        return executionStore.loadReportExecutions(requireCurrentSession(), reportId);
    }

    public ExecutionReportSnapshot loadExecutionReportSnapshot(String reportId) throws SQLException {
        return executionStore.loadExecutionReportSnapshot(requireCurrentSession(), reportId);
    }

    public Path resolveProjectPath(String relativePath) {
        return containerFiles.resolveWorkspacePath(requireCurrentSession(), relativePath);
    }

    public EnvironmentRecord loadReportEnvironmentSnapshot(String reportId) throws SQLException {
        return workspaceStore.loadReportEnvironmentSnapshot(requireCurrentSession(), reportId);
    }

    public void updateProject(String name, String description) throws SQLException {
        workspaceStore.updateProject(requireCurrentSession(), name, description);
    }

    public EnvironmentRecord createEnvironment(String name) throws SQLException {
        return workspaceStore.createEnvironment(requireCurrentSession(), name);
    }

    public void updateEnvironment(EnvironmentRecord environment) throws SQLException {
        workspaceStore.updateEnvironment(requireCurrentSession(), environment);
    }

    public void deleteEnvironment(String environmentId) throws SQLException {
        workspaceStore.deleteEnvironment(requireCurrentSession(), environmentId);
    }

    public void upsertProjectApplication(ApplicationEntry application) throws SQLException {
        workspaceStore.upsertProjectApplication(requireCurrentSession(), application);
    }

    public void deleteProjectApplication(String applicationId) throws SQLException {
        workspaceStore.deleteProjectApplication(requireCurrentSession(), applicationId);
    }

    public void setPrimaryProjectApplication(String applicationId) throws SQLException {
        workspaceStore.setPrimaryProjectApplication(requireCurrentSession(), applicationId);
    }

    public ReportRecord createTestExecutionReport(String environmentId, String requestedTitle) throws SQLException {
        ProjectSession session = requireCurrentSession();
        String reportId = UUID.randomUUID().toString();
        String timestamp = Instant.now().toString();
        String reportTitle = requestedTitle == null || requestedTitle.isBlank()
                ? "Test Execution Report"
                : requestedTitle.trim();

        try (Connection connection = openConnection(session.databasePath())) {
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

            workspaceStore.snapshotProjectOverview(connection, reportId);
            workspaceStore.snapshotApplications(connection, reportId);
            workspaceStore.snapshotEnvironment(connection, reportId, environmentId);
            executionStore.createInitialExecutionRun(connection, reportId);
        }

        return workspaceStore.loadReport(session, reportId);
    }

    public ReportRecord copyReportToEnvironment(String reportId, String targetEnvironmentId) throws SQLException {
        ProjectSession session = requireCurrentSession();
        ReportRecord originalReport = workspaceStore.loadReport(session, reportId);
        String newReportId = UUID.randomUUID().toString();
        String timestamp = Instant.now().toString();
        String copiedTitle = originalReport.getTitle() + " (Copy)";

        try (Connection connection = openConnection(session.databasePath())) {
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

            workspaceStore.copyReportFields(connection, reportId, newReportId);
            workspaceStore.copyReportApplications(connection, reportId, newReportId);
            workspaceStore.snapshotEnvironment(connection, newReportId, targetEnvironmentId);
            executionStore.copyExecutionHierarchy(connection, session, reportId, newReportId);
        }

        return workspaceStore.loadReport(session, newReportId);
    }

    public void moveReportToEnvironment(String reportId, String targetEnvironmentId) throws SQLException {
        workspaceStore.moveReportToEnvironment(requireCurrentSession(), reportId, targetEnvironmentId);
    }

    public void deleteReport(String reportId) throws SQLException {
        workspaceStore.deleteReport(requireCurrentSession(), reportId);
    }

    public void updateReportStatus(String reportId, ReportStatus status) throws SQLException {
        workspaceStore.updateReportStatus(requireCurrentSession(), reportId, status);
    }

    public void updateReportTitle(String reportId, String title) throws SQLException {
        workspaceStore.updateReportTitle(requireCurrentSession(), reportId, title);
    }

    public void updateLastSelectedSection(String reportId, TestExecutionSection section) throws SQLException {
        workspaceStore.updateLastSelectedSection(requireCurrentSession(), reportId, section);
    }

    public void updateReportField(String reportId, String fieldKey, String fieldValue) throws SQLException {
        workspaceStore.updateReportField(requireCurrentSession(), reportId, fieldKey, fieldValue);
    }

    public void upsertReportApplication(String reportId, ApplicationEntry application) throws SQLException {
        workspaceStore.upsertReportApplication(requireCurrentSession(), reportId, application);
    }

    public void deleteReportApplication(String applicationId, String reportId) throws SQLException {
        workspaceStore.deleteReportApplication(requireCurrentSession(), applicationId, reportId);
    }

    public void setPrimaryReportApplication(String reportId, String applicationId) throws SQLException {
        workspaceStore.setPrimaryReportApplication(requireCurrentSession(), reportId, applicationId);
    }

    public ExecutionRunRecord createExecutionRun(String reportId) throws SQLException {
        return executionStore.createExecutionRun(requireCurrentSession(), reportId);
    }

    public void updateExecutionRun(ExecutionRunRecord run) throws SQLException {
        executionStore.updateExecutionRun(requireCurrentSession(), run);
    }

    public void deleteExecutionRun(String reportId, String executionRunId) throws SQLException {
        executionStore.deleteExecutionRun(requireCurrentSession(), reportId, executionRunId);
    }

    public ExecutionRunEvidenceRecord addExecutionRunEvidenceFromFile(String reportId, String executionRunId, Path sourcePath)
            throws IOException, SQLException {
        return executionStore.addExecutionRunEvidenceFromFile(requireCurrentSession(), reportId, executionRunId, sourcePath);
    }

    public ExecutionRunEvidenceRecord addExecutionRunEvidence(
            String reportId,
            String executionRunId,
            String originalFileName,
            String mediaType,
            byte[] content
    ) throws IOException, SQLException {
        return executionStore.addExecutionRunEvidence(
                requireCurrentSession(),
                reportId,
                executionRunId,
                originalFileName,
                mediaType,
                content
        );
    }

    public void deleteExecutionRunEvidence(String reportId, String evidenceId) throws IOException, SQLException {
        executionStore.deleteExecutionRunEvidence(requireCurrentSession(), reportId, evidenceId);
    }

    public TestExecutionRecord createReportExecution(String reportId) throws SQLException {
        return executionStore.createReportExecution(requireCurrentSession(), reportId);
    }

    public void updateReportExecution(TestExecutionRecord execution) throws SQLException {
        executionStore.updateReportExecution(requireCurrentSession(), execution);
    }

    public void deleteReportExecution(String executionId, String reportId) throws SQLException {
        executionStore.deleteReportExecution(requireCurrentSession(), executionId, reportId);
    }

    public void updateReportEnvironmentSnapshot(String reportId, EnvironmentRecord environmentRecord) throws SQLException {
        workspaceStore.updateReportEnvironmentSnapshot(requireCurrentSession(), reportId, environmentRecord);
    }

    public void saveProject() throws IOException, SQLException {
        ProjectSession refreshedSession = refreshManifest(requireCurrentSession());
        currentSession = refreshedSession;
        containerFiles.writeAuxiliaryFiles(refreshedSession);
        containerFiles.saveProjectArchive(refreshedSession);
    }

    private ProjectSession refreshManifest(ProjectSession session) throws SQLException {
        ProjectSummary projectSummary = workspaceStore.loadWorkspace(session).getProject();
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
        String normalizedFileName = fileName.toLowerCase();
        if (normalizedFileName.endsWith(PROJECT_EXTENSION)) {
            return requestedTargetFile;
        }
        return requestedTargetFile.resolveSibling(fileName + PROJECT_EXTENSION);
    }

}
