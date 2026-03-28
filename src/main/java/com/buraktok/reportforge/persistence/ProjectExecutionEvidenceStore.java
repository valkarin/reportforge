package com.buraktok.reportforge.persistence;

import com.buraktok.reportforge.model.ExecutionRunEvidenceRecord;
import com.buraktok.reportforge.model.ExecutionRunRecord;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.buraktok.reportforge.persistence.PersistenceSupport.nextScopedSortOrder;
import static com.buraktok.reportforge.persistence.PersistenceSupport.nullableText;
import static com.buraktok.reportforge.persistence.PersistenceSupport.placeholders;

final class ProjectExecutionEvidenceStore {
    private final ProjectContainerFiles containerFiles;
    private final ProjectWorkspaceStore workspaceStore;

    ProjectExecutionEvidenceStore(ProjectContainerFiles containerFiles, ProjectWorkspaceStore workspaceStore) {
        this.containerFiles = containerFiles;
        this.workspaceStore = workspaceStore;
    }

    PreparedEvidenceUpload prepareEvidenceUpload(Path sourcePath) throws IOException {
        if (sourcePath == null || !Files.exists(sourcePath)) {
            throw new IOException("Evidence file not found.");
        }
        byte[] content = Files.readAllBytes(sourcePath);
        String fileName = sourcePath.getFileName() == null ? "evidence" : sourcePath.getFileName().toString();
        String mediaType = probeEvidenceMediaType(sourcePath, fileName);
        return new PreparedEvidenceUpload(fileName, mediaType, content);
    }

    ExecutionRunEvidenceRecord addExecutionRunEvidence(
            Connection connection,
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

        EvidenceMediaOptimizer.OptimizedEvidencePayload optimizedPayload = EvidenceMediaOptimizer.optimizeToWebp(normalizedMediaType, content);
        byte[] optimizedContent = optimizedPayload.bytes();
        normalizedMediaType = optimizedPayload.mediaType();

        String timestamp = Instant.now().toString();
        String evidenceId = UUID.randomUUID().toString();
        String storedPath = storeEvidenceContent(session, executionRunId, originalFileName, optimizedContent, normalizedMediaType);

        try {
            ExecutionRunEvidenceRecord evidence = new ExecutionRunEvidenceRecord(
                    evidenceId,
                    executionRunId,
                    storedPath,
                    nullableText(originalFileName),
                    normalizedMediaType,
                    nextScopedSortOrder(connection, PersistenceSupport.ScopedSortTarget.REPORT_EXECUTION_RUN_EVIDENCE, executionRunId),
                    timestamp,
                    timestamp
            );
            insertExecutionRunEvidence(connection, evidence);
            workspaceStore.touchReport(connection, reportId);
            return evidence;
        } catch (SQLException | RuntimeException exception) {
            Files.deleteIfExists(containerFiles.resolveWorkspacePath(session, storedPath));
            throw exception;
        }
    }

    void deleteExecutionRunEvidence(Connection connection, ProjectSession session, String reportId, String evidenceId)
            throws IOException, SQLException {
        try {
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
                try {
                    Files.deleteIfExists(containerFiles.resolveWorkspacePath(session, storedPath));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    Map<String, List<ExecutionRunEvidenceRecord>> loadExecutionRunEvidenceByRun(
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

    void copyExecutionRunEvidence(
            Connection connection,
            ProjectSession session,
            ExecutionRunEvidenceRecord evidence,
            String targetRunId,
            List<Path> copiedEvidencePaths
    ) throws SQLException {
        String copiedStoredPath = evidence.getStoredPath();
        Path sourcePath = containerFiles.resolveWorkspacePath(session, evidence.getStoredPath());
        if (Files.exists(sourcePath)) {
            try {
                copiedStoredPath = storeEvidenceContent(session, targetRunId, evidence.getDisplayName(), Files.readAllBytes(sourcePath), evidence.getMediaType());
                copiedEvidencePaths.add(containerFiles.resolveWorkspacePath(session, copiedStoredPath));
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

    private String storeEvidenceContent(ProjectSession session, String executionRunId, String originalFileName, byte[] content, String mediaType) throws IOException {
        String extension = evidenceFileExtension(originalFileName);
        if (EvidenceMediaOptimizer.WEBP_MIME_TYPE.equals(mediaType)) {
            extension = ".webp";
        }
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

    record PreparedEvidenceUpload(String originalFileName, String mediaType, byte[] content) {
    }
}
