package com.buraktok.reportforge.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectSchemaManagerTest {
    @TempDir
    Path tempDir;

    private final ProjectSchemaManager schemaManager = new ProjectSchemaManager();

    @Test
    void initializeSchemaAddsMissingExecutionRunColumns() throws Exception {
        Path databasePath = tempDir.resolve("schema-manager-test.db");
        try (Connection connection = PersistenceSupport.openConnection(databasePath);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE report_execution_runs (
                        id TEXT PRIMARY KEY,
                        report_id TEXT NOT NULL,
                        sort_order INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
        }

        schemaManager.initializeSchema(databasePath);

        Set<String> columns = new HashSet<>();
        try (Connection connection = PersistenceSupport.openConnection(databasePath);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(report_execution_runs)")) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("name"));
            }
        }

        assertAll(
                () -> assertTrue(columns.contains("test_case_key")),
                () -> assertTrue(columns.contains("status")),
                () -> assertTrue(columns.contains("comments_text")),
                () -> assertTrue(columns.contains("test_steps_text")),
                () -> assertTrue(columns.contains("defect_summary"))
        );
    }
}
