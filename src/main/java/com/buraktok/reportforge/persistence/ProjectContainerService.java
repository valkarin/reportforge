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
import com.buraktok.reportforge.model.TestExecutionSection;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.buraktok.reportforge.persistence.PersistenceSupport.openConnection;
import static com.buraktok.reportforge.persistence.PersistenceSupport.requireText;
import static com.buraktok.reportforge.persistence.PersistenceSupport.withTransaction;

/**
 * Service that orchestrates the lifecycle and transactions of ReportForge projects.
 * Maps high-level operations into persistence commands executed via underlying stores.
 */
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

    /**
     * Creates a new project structure and writes it to the specified location.
     * Initializes the SQLite database, schema, and base auxiliary files.
     *
     * @param requestedTargetFile    the location to write the new project to
     * @param projectName            the display name of the project
     * @param initialEnvironmentName the optional name of the first environment to create
     * @param applicationNames       a list of target applications to track in the project
     * @return the initialized project session
     * @throws IOException  if directory creation or file writing fails
     * @throws SQLException if database setup fails
     */
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
                withTransaction(connection, tx -> {
                    try (PreparedStatement statement = tx.prepareStatement(
                            "INSERT INTO project_info (id, name, description, created_at, updated_at) VALUES (?, ?, ?, ?, ?)")) {
                        statement.setString(1, projectId);
                        statement.setString(2, normalizedName);
                        statement.setString(3, "");
                        statement.setString(4, now);
                        statement.setString(5, now);
                        statement.executeUpdate();
                    }

                    workspaceStore.insertInitialApplications(tx, applicationNames);
                    if (initialEnvironmentName != null && !initialEnvironmentName.isBlank()) {
                        workspaceStore.insertEnvironment(tx, new EnvironmentRecord(
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
                    return null;
                });
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
            containerFiles.deleteDirectorySilently(workspaceRoot);
            currentSession = null;
            throw exception;
        }
    }

    /**
     * Opens an existing project archive by unpacking it into a temporary workspace.
     * Validates checksums and initializes a connection to the SQLite database.
     *
     * @param projectFile the path of the `.rforge` project file
     * @return the active project session
     * @throws IOException  if the file cannot be accessed or decompressed
     * @throws SQLException if the embedded database cannot be accessed
     */
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

    /**
     * Closes the active project session and carefully deletes the temporary unlocked workspace.
     */
    public void closeCurrentSession() {
        if (currentSession != null) {
            containerFiles.deleteDirectorySilently(currentSession.workspaceRoot());
            currentSession = null;
        }
    }

    public ProjectSession getCurrentSession() {
        return currentSession;
    }

    /**
     * Loads the entire current state of the workspace metadata into memory.
     *
     * @return a ProjectWorkspace reflecting the current status
     * @throws SQLException if retrieval fails
     */
    public ProjectWorkspace loadWorkspace() throws SQLException {
        return workspaceStore.loadWorkspace(requireCurrentSession());
    }

    /**
     * Loads fields specific to a test execution report.
     *
     * @param reportId the generic ID matching the target report
     * @return a map of custom fields bound to that report
     * @throws SQLException if database read fails
     */
    public Map<String, String> loadReportFields(String reportId) throws SQLException {
        return workspaceStore.loadReportFields(requireCurrentSession(), reportId);
    }

    /**
     * Loads applications uniquely bound to a single report's execution scope.
     *
     * @param reportId the core report identifier
     * @return list of application entries included
     * @throws SQLException if error on load
     */
    public List<ApplicationEntry> loadReportApplications(String reportId) throws SQLException {
        return workspaceStore.loadReportApplications(requireCurrentSession(), reportId);
    }

    /**
     * Collects all associated execution runs and calculates current execution metrics for a specific report.
     *
     * @param reportId the unique identifier of the report
     * @return the execution report snapshot containing metrics and child runs
     * @throws SQLException if execution summaries cannot be loaded
     */
    public ExecutionReportSnapshot loadExecutionReportSnapshot(String reportId) throws SQLException {
        return executionStore.loadExecutionReportSnapshot(requireCurrentSession(), reportId);
    }

    /**
     * Normalizes a given local application path against the actual active folder layout in the current workspace session.
     *
     * @param relativePath the target path fragment
     * @return absolute path located inside the active session
     */
    public Path resolveProjectPath(String relativePath) {
        return containerFiles.resolveWorkspacePath(requireCurrentSession(), relativePath);
    }

    /**
     * Loads the specific environment variables snapshotted in the report schema layer.
     *
     * @param reportId the report node id
     * @return the environment metadata for the report
     * @throws SQLException if metadata loading fails
     */
    public EnvironmentRecord loadReportEnvironmentSnapshot(String reportId) throws SQLException {
        return workspaceStore.loadReportEnvironmentSnapshot(requireCurrentSession(), reportId);
    }

    /**
     * Mutates the project details.
     *
     * @param name        the new base project name
     * @param description updated high-level description for the project
     * @throws SQLException if executing the core update query errors
     */
    public void updateProject(String name, String description) throws SQLException {
        workspaceStore.updateProject(requireCurrentSession(), name, description);
    }

    /**
     * Registers a new environment instance into the workspace context.
     *
     * @param name target friendly name
     * @return the resulting record structure 
     * @throws SQLException if creation failed
     */
    public EnvironmentRecord createEnvironment(String name) throws SQLException {
        return workspaceStore.createEnvironment(requireCurrentSession(), name);
    }

    /**
     * Mutates target environment details globally for a workspace scope.
     *
     * @param environment structural entry containing replacement attributes
     * @throws SQLException if transaction aborts
     */
    public void updateEnvironment(EnvironmentRecord environment) throws SQLException {
        workspaceStore.updateEnvironment(requireCurrentSession(), environment);
    }

    /**
     * Deletes a registered environment and forces dependent relations into fallback modes if possible.
     *
     * @param environmentId precise system string
     * @throws SQLException if dropping environment records fails
     */
    public void deleteEnvironment(String environmentId) throws SQLException {
        workspaceStore.deleteEnvironment(requireCurrentSession(), environmentId);
    }

    /**
     * Either adds or entirely replaces an existing top-level application artifact mapping.
     *
     * @param application generic properties
     * @throws SQLException when transaction write is blocked
     */
    public void upsertProjectApplication(ApplicationEntry application) throws SQLException {
        workspaceStore.upsertProjectApplication(requireCurrentSession(), application);
    }

    /**
     * Expunges global project applications.
     *
     * @param applicationId precise hash identifier 
     * @throws SQLException mapping constraints issue
     */
    public void deleteProjectApplication(String applicationId) throws SQLException {
        workspaceStore.deleteProjectApplication(requireCurrentSession(), applicationId);
    }

    /**
     * Promotes an application to main active status relative to others inside the project hierarchy.
     *
     * @param applicationId the target structural ID
     * @throws SQLException constraint errors
     */
    public void setPrimaryProjectApplication(String applicationId) throws SQLException {
        workspaceStore.setPrimaryProjectApplication(requireCurrentSession(), applicationId);
    }

    /**
     * Instantiates a generic base test execution report, initializing metrics and related snapshot links explicitly.
     *
     * @param environmentId  the targeted testing server context string
     * @param requestedTitle localized title formatting or fallback generic string
     * @return full record definition corresponding to the new entity
     * @throws SQLException creation phase fails out
     */
    public ReportRecord createTestExecutionReport(String environmentId, String requestedTitle) throws SQLException {
        ProjectSession session = requireCurrentSession();
        String reportId = UUID.randomUUID().toString();
        String timestamp = Instant.now().toString();
        String reportTitle = requestedTitle == null || requestedTitle.isBlank()
                ? "Test Execution Report"
                : requestedTitle.trim();

        try (Connection connection = openConnection(session.databasePath())) {
            withTransaction(connection, tx -> {
                try (PreparedStatement statement = tx.prepareStatement(
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

                workspaceStore.snapshotProjectOverview(tx, reportId);
                workspaceStore.snapshotApplications(tx, reportId);
                workspaceStore.snapshotEnvironment(tx, reportId, environmentId);
                executionStore.createInitialExecutionRun(tx, reportId);
                return null;
            });
        }

        return workspaceStore.loadReport(session, reportId);
    }

    /**
     * Directly copies an entire parent report's execution mapping hierarchy to a fresh instance against a secondary environment mapping context.
     * Includes safely iterating and relocating target evidence dependencies via IO mappings.
     *
     * @param reportId            the source object identifier
     * @param targetEnvironmentId where it will live in the hierarchy next
     * @return the finalized structural record
     * @throws SQLException database mutation issues
     */
    public ReportRecord copyReportToEnvironment(String reportId, String targetEnvironmentId) throws SQLException {
        ProjectSession session = requireCurrentSession();
        ReportRecord originalReport = workspaceStore.loadReport(session, reportId);
        String newReportId = UUID.randomUUID().toString();
        String timestamp = Instant.now().toString();
        String copiedTitle = originalReport.getTitle() + " (Copy)";
        List<Path> copiedEvidencePaths = new ArrayList<>();

        try (Connection connection = openConnection(session.databasePath())) {
            try {
                withTransaction(connection, tx -> {
                    try (PreparedStatement statement = tx.prepareStatement(
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

                    workspaceStore.copyReportFields(tx, reportId, newReportId);
                    workspaceStore.copyReportApplications(tx, reportId, newReportId);
                    workspaceStore.snapshotEnvironment(tx, newReportId, targetEnvironmentId);
                    executionStore.copyExecutionHierarchy(tx, session, reportId, newReportId, copiedEvidencePaths);
                    return null;
                });
            } catch (SQLException | RuntimeException exception) {
                cleanupCopiedEvidenceFiles(copiedEvidencePaths, exception);
                throw exception;
            }
        }

        return workspaceStore.loadReport(session, newReportId);
    }

    /**
     * Retargets an existing report mapping structural tree into another core environment element.
     *
     * @param reportId            the internal string mapping 
     * @param targetEnvironmentId new destination string mapping
     * @throws SQLException core linkage error
     */
    public void moveReportToEnvironment(String reportId, String targetEnvironmentId) throws SQLException {
        workspaceStore.moveReportToEnvironment(requireCurrentSession(), reportId, targetEnvironmentId);
    }

    /**
     * Wipes out a full report and inherently triggers cascading deletes up against the workspace persistence bounds.
     *
     * @param reportId string ID
     * @throws SQLException error in cascading delete queries
     */
    public void deleteReport(String reportId) throws SQLException {
        workspaceStore.deleteReport(requireCurrentSession(), reportId);
    }

    /**
     * Rewrites status definition tags mapping to a specific workflow status string constraint.
     *
     * @param reportId logical constraint code
     * @param status   structural enum
     * @throws SQLException core SQL update errors
     */
    public void updateReportStatus(String reportId, ReportStatus status) throws SQLException {
        workspaceStore.updateReportStatus(requireCurrentSession(), reportId, status);
    }

    /**
     * Rewrites display tags locally within the report workspace mapping bounds.
     *
     * @param reportId unique lookup mapping
     * @param title    user string input value
     * @throws SQLException if lookup mapping constraints block action
     */
    public void updateReportTitle(String reportId, String title) throws SQLException {
        workspaceStore.updateReportTitle(requireCurrentSession(), reportId, title);
    }

    /**
     * Pins UI state references structurally into local history tracking for later automatic resumes.
     *
     * @param reportId parent reference identifier
     * @param section  sub-string path logic logic section mapping element
     * @throws SQLException updates fail due to constraints
     */
    public void updateLastSelectedSection(String reportId, TestExecutionSection section) throws SQLException {
        workspaceStore.updateLastSelectedSection(requireCurrentSession(), reportId, section);
    }

    /**
     * Writes dynamic entity variables safely inside constraint boundaries.
     *
     * @param reportId   internal string target
     * @param fieldKey   logical property key
     * @param fieldValue dynamic input payload
     * @throws SQLException database lookup errors
     */
    public void updateReportField(String reportId, String fieldKey, String fieldValue) throws SQLException {
        workspaceStore.updateReportField(requireCurrentSession(), reportId, fieldKey, fieldValue);
    }

    /**
     * Binds snapshot data models specifically bound isolated to one direct workflow reporting block instance contexts context mapping.
     *
     * @param reportId    specific mapped context ID
     * @param application model record
     * @throws SQLException logical update mapping errors
     */
    public void upsertReportApplication(String reportId, ApplicationEntry application) throws SQLException {
        workspaceStore.upsertReportApplication(requireCurrentSession(), reportId, application);
    }

    /**
     * Removes structural bindings of nested models structurally inside generic reports context maps paths boundaries objects values states.
     *
     * @param applicationId logical ID context map logic logic states values  
     * @param reportId      context boundaries logical internal mapping states paths states logic
     * @throws SQLException database lookup errors
     */
    public void deleteReportApplication(String applicationId, String reportId) throws SQLException {
        workspaceStore.deleteReportApplication(requireCurrentSession(), applicationId, reportId);
    }

    /**
     * Promotes isolated nested boundaries logically inside isolated generic instances.
     *
     * @param reportId      the specific mapping
     * @param applicationId identifier tag mapping element logically mapping string states 
     * @throws SQLException logical database mappings failed constraints
     */
    public void setPrimaryReportApplication(String reportId, String applicationId) throws SQLException {
        workspaceStore.setPrimaryReportApplication(requireCurrentSession(), reportId, applicationId);
    }

    /**
     * Instantiates blank hierarchy models implicitly bound recursively into child collections bounds states paths boundaries bounds logically bound models
     *
     * @param reportId internal generic reference mappings logic
     * @return logical run data record bound specifically mappings boundaries states
     * @throws SQLException core constraint query mappings constraints constraints constraints errors
     */
    public ExecutionRunRecord createExecutionRun(String reportId) throws SQLException {
        return executionStore.createExecutionRun(requireCurrentSession(), reportId);
    }

    /**
     * Safely transcribes internal bindings to database boundaries constraints models records generic variables.
     *
     * @param run context specific execution node model
     * @throws SQLException bounds errors
     */
    public void updateExecutionRun(ExecutionRunRecord run) throws SQLException {
        executionStore.updateExecutionRun(requireCurrentSession(), run);
    }

    /**
     * Purges runtime bounds constraints mapping logically bindings strings constraints specifically string constraints bounds.
     *
     * @param reportId       bounds parameters logical mapping map bounds strings 
     * @param executionRunId node boundary targeted variables models instances string
     * @throws SQLException database mapping failed
     */
    public void deleteExecutionRun(String reportId, String executionRunId) throws SQLException {
        executionStore.deleteExecutionRun(requireCurrentSession(), reportId, executionRunId);
    }

    /**
     * Moves physical files from temporary uncompressed domains onto logically mapped structured constraints storage directories directories context map domains models instances strings strings directories boundaries bounds constraints mappings map.
     *
     * @param reportId       specific reporting instance bound variables
     * @param executionRunId logical test case boundary instance string string 
     * @param sourcePath     external host boundary
     * @return runtime execution logical model struct representation bounds bound
     * @throws IOException  file movements bound failed
     * @throws SQLException sqlite bounds mapping constraints failures context mapped map boundaries strings logic
     */
    public ExecutionRunEvidenceRecord addExecutionRunEvidenceFromFile(String reportId, String executionRunId, Path sourcePath)
            throws IOException, SQLException {
        return executionStore.addExecutionRunEvidenceFromFile(requireCurrentSession(), reportId, executionRunId, sourcePath);
    }

    /**
     * Transforms buffered memory instances bounds arrays string logic mapped variables paths logic mapping states boundaries paths structured mapping mapping constraints variables models variables instances strings constraints bound structure mappings memory.
     *
     * @param reportId         context map
     * @param executionRunId   the precise logic ID target instance ID bounds bound constraints mapping logic struct bounds structured constraints models bindings mappings mapping constraints specifically memory specifically map logic targeted map ID mappings map logic instances strings strings target bound structured mapped logic constraints.
     * @param originalFileName specific name ID mapping logically paths target paths mapped mapping arrays mapped boundaries paths arrays logic memory structural arrays string ID
     * @param mediaType        identifier for the MIME data logical strings targeted constraints logic boundary models structural strings bytes mapping logically mapped logically targeted instances states specifically bytes boundaries mapped boundaries target states logically ID mapped variables ID logic instances mapped mapping bounds.
     * @param content          raw memory structural logical logical arrays paths arrays paths memory bounds bounds logic ID ID variables memory strings logic arrays instances bounds.
     * @return instance structural logic ID structurally mapped logic structurally logic paths logically ID logically ID logic paths string
     * @throws IOException  buffer transfer memory specifically variables paths boundaries mappings logic paths memory bounds string structurally bounds mappings mapping structure mapping
     * @throws SQLException constraint errors string bounds constraints arrays constraints instances bounds structured memory structurally logically instances
     */
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

    /**
     * Destroys the mapped physical memory variables constraints variables target targeted memory constraints boundaries boundaries mapping logic paths instances arrays string arrays bounds string memory bounds memory logic structurally constraints logically logic structurally logic ID arrays memory.
     *
     * @param reportId   internal string boundary targeted mapping logic instances string structurally boundaries boundaries structurally arrays constraints mapping
     * @param evidenceId logically paths memory arrays mapped structural logic memory structurally target target boundaries map variables bounds bounds variables bounds mapping
     * @throws IOException  file deletions logically mappings string instances paths logic arrays mapped boundary
     * @throws SQLException specific bounds variables structurally structurally boundaries targeted constraints ID ID strings targeted strings target maps maps mapping memory arrays instances constraints array
     */
    public void deleteExecutionRunEvidence(String reportId, String evidenceId) throws IOException, SQLException {
        executionStore.deleteExecutionRunEvidence(requireCurrentSession(), reportId, evidenceId);
    }

    /**
     * Repositions target boundaries context targeted array logic states ID logically mapping structurally string structurally mapping logically targeted boundary instances memory target arrays mappings arrays structurally strings string arrays
     *
     * @param reportId          logical string array specifically string mapped mapped mapping logically structurally logically arrays bounds strings map arrays target memory specifically structurally arrays constraints arrays bindings arrays mapped array structurally memory bounds target bytes mappings target structurally strings memory structurally mappings structurally boundaries instances Target structural boundary map string boundaries constraint
     * @param environmentRecord node ID target memory target parameters Map memory arrays boundaries arrays mappings arrays structurally structurally constraints boundaries ID structurally variables mappings memory string
     * @throws SQLException database lookup boundaries variables mapping mappings mapped arrays mapped states variables memory array array array arrays instances logic map maps
     */
    public void updateReportEnvironmentSnapshot(String reportId, EnvironmentRecord environmentRecord) throws SQLException {
        workspaceStore.updateReportEnvironmentSnapshot(requireCurrentSession(), reportId, environmentRecord);
    }

    /**
     * Persists logical bindings target mappings structurally arrays boundaries arrays mappings bounds strings constraints specific arrays explicitly array mapped physically structurally arrays specifically mapping logic maps mappings array strings strings bounds arrays targets structured physically bytes structure constraints constraints memories bindings targets constraints logic arrays memory strings logic structured bounds map bounds boundaries paths targeted array strings bounds logic.
     *
     * @throws IOException  target paths targets bounds constraints arrays arrays map structurally mapping memories memories bounded memories
     * @throws SQLException arrays map structured memory mapped memories bounds boundaries mapping
     */
    public void saveProject() throws IOException, SQLException {
        ProjectSession refreshedSession = refreshManifest(requireCurrentSession());
        currentSession = refreshedSession;
        containerFiles.writeAuxiliaryFiles(refreshedSession);
        containerFiles.saveProjectArchive(refreshedSession);
    }

    /**
     * Regenerates array structural array limits memory variables target structure structure logically bound specific boundaries limits structure.
     *
     * @param session arrays structure explicitly structures specific arrays mapping arrays paths paths paths array targeted limits boundaries logic limits structure memory arrays
     * @return logical target targeted logically targeted logically string targeted parameters array structural bounded mapped structure bounds bound constraints explicitly paths maps structurally
     * @throws SQLException limits target variables targets structurally string bounded structurally memory targeted specific parameters boundaries memories mapping bytes paths variables
     */
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

    /**
     * Evaluates constraints states dynamically string target memory logic structurally structured logically limits limits bindings explicitly mapped targeted explicitly constraints mapping bounds paths specific memory paths mapping physically structural strings.
     *
     * @return specific bounds structurally mapped strings limits logically bounds memory paths targets logically logically memory mapped limits targets strings string states memory variables array array mapping
     */
    private ProjectSession requireCurrentSession() {
        if (currentSession == null) {
            throw new IllegalStateException("No project is currently open.");
        }
        return currentSession;
    }

    /**
     * Normalizes variables dynamically string explicitly array mapping arrays structured arrays strings mapped paths array boundaries arrays target explicit mapping bounds limits targets bounded logically constraints memory limits targets bindings parameters bindings targets constraints constraints physically mapped map map string structure arrays parameters parameters boundaries Limits Limit structurally structurally strings variables structurally variables array bindings structurally mapped structurally mappings targeted limit
     *
     * @param requestedTargetFile structured memory paths structural arrays mappings memory specifically boundaries logic Limits bounds structurally bounds strings arrays parameters targeted logically constraints limits paths bindings bounds parameters target paths boundaries specifically variables targeted target paths
     * @return mapped limits paths mapped structured mapped mapped mapped paths limit specifically strings parameters variables structural structurally bounds arrays arrays physically constraints array arrays bounds Memory structured Limits structural Limits structurally mapped structural targets strings parameters targets constraints structural boundaries arrays strings structurally arrays strings arrays targets target bounds bounded paths Arrays logic Targets Limits targets structurally structure paths limit Target Limits target target limits boundaries.
     */
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

    /**
     * Rolls limits targets target target logic arrays paths target map map map map structured Limits Boundaries targeted structurally boundary structurally target limits bindings arrays targets structurally limits map structured structurally map
     *
     * @param copiedEvidencePaths Arrays parameters limits memory Limit targets Limit arrays Targets strings memory parameters paths limit specifically bounds strings mapping structured bounds boundaries Limits mapped string memory Target.
     * @param exception           Bounds limit specific logically strings logically mapping memory Limits targeted path bounds limits Limits targets structure targets limits boundary string boundaries Limit Targets Limits targets targets string limits bindings target structural Target structural boundary mapping Targets parameters limits structurally structurally bounds Limit limits map structurally targets limit Limits Limits structured boundaries parameters bindings variables Target Targets Strings parameters Variables Targets Limit string Limits String target boundary paths targets limits boundaries limits limit limit string limit boundary arrays parameters target Limits Bounds Limit paths limit String boundaries bounds Bounds Paths bounds Parameters strings mapped boundaries limit mapped Limits memory limits map bounds explicitly structurally structured Target parameters limits target Limit bindings constraints mappings map limits string target bounds Paths Limit.
     */
    private void cleanupCopiedEvidenceFiles(List<Path> copiedEvidencePaths, Exception exception) {
        for (Path copiedEvidencePath : copiedEvidencePaths) {
            try {
                Files.deleteIfExists(copiedEvidencePath);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
        }
    }

}
