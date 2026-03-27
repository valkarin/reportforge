package com.buraktok.reportforge.persistence;

import com.buraktok.reportforge.model.ExecutionReportSnapshot;
import com.buraktok.reportforge.model.ExecutionRunEvidenceRecord;
import com.buraktok.reportforge.model.ExecutionRunRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static com.buraktok.reportforge.persistence.PersistenceSupport.openConnection;
import static com.buraktok.reportforge.persistence.PersistenceSupport.withTransaction;

final class ProjectExecutionStore {
    private final ProjectWorkspaceStore workspaceStore;
    private final ProjectExecutionEvidenceStore evidenceStore;
    private final ProjectExecutionRunStore runStore;

    ProjectExecutionStore(ProjectContainerFiles containerFiles, ProjectWorkspaceStore workspaceStore) {
        this.workspaceStore = workspaceStore;
        this.evidenceStore = new ProjectExecutionEvidenceStore(containerFiles, workspaceStore);
        this.runStore = new ProjectExecutionRunStore(evidenceStore, new ExecutionMetricsAssembler());
    }

    ExecutionReportSnapshot loadExecutionReportSnapshot(ProjectSession session, String reportId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
            return runStore.loadExecutionReportSnapshot(connection, reportId);
        }
    }

    ExecutionRunRecord createExecutionRun(ProjectSession session, String reportId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
            return withTransaction(connection, tx -> {
                ExecutionRunRecord run = runStore.createExecutionRun(tx, reportId);
                workspaceStore.touchReport(tx, reportId);
                return run;
            });
        }
    }

    void createInitialExecutionRun(Connection connection, String reportId) throws SQLException {
        runStore.createInitialExecutionRun(connection, reportId);
    }

    void updateExecutionRun(ProjectSession session, ExecutionRunRecord run) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
            withTransaction(connection, tx -> {
                runStore.updateExecutionRun(tx, run);
                workspaceStore.touchReport(tx, run.getReportId());
                return null;
            });
        }
    }

    void deleteExecutionRun(ProjectSession session, String reportId, String executionRunId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
            withTransaction(connection, tx -> {
                runStore.deleteExecutionRun(tx, reportId, executionRunId);
                workspaceStore.touchReport(tx, reportId);
                return null;
            });
        }
    }

    ExecutionRunEvidenceRecord addExecutionRunEvidenceFromFile(ProjectSession session, String reportId, String executionRunId, Path sourcePath)
            throws IOException, SQLException {
        ProjectExecutionEvidenceStore.PreparedEvidenceUpload upload = evidenceStore.prepareEvidenceUpload(sourcePath);
        return addExecutionRunEvidence(
                session,
                reportId,
                executionRunId,
                upload.originalFileName(),
                upload.mediaType(),
                upload.content()
        );
    }

    ExecutionRunEvidenceRecord addExecutionRunEvidence(
            ProjectSession session,
            String reportId,
            String executionRunId,
            String originalFileName,
            String mediaType,
            byte[] content
    ) throws IOException, SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
            try {
                return withTransaction(connection, tx -> {
                    try {
                        return evidenceStore.addExecutionRunEvidence(
                                tx,
                                session,
                                reportId,
                                executionRunId,
                                originalFileName,
                                mediaType,
                                content
                        );
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
            } catch (UncheckedIOException exception) {
                throw exception.getCause();
            }
        }
    }

    void deleteExecutionRunEvidence(ProjectSession session, String reportId, String evidenceId) throws IOException, SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
            try {
                withTransaction(connection, tx -> {
                    try {
                        evidenceStore.deleteExecutionRunEvidence(tx, session, reportId, evidenceId);
                        return null;
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
            } catch (UncheckedIOException exception) {
                throw exception.getCause();
            }
        }
    }

    void copyExecutionHierarchy(
            Connection connection,
            ProjectSession session,
            String sourceReportId,
            String targetReportId,
            List<Path> copiedEvidencePaths
    ) throws SQLException {
        runStore.copyExecutionHierarchy(connection, session, sourceReportId, targetReportId, copiedEvidencePaths);
    }
}
