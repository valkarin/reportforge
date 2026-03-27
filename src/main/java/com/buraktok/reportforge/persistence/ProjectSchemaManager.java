package com.buraktok.reportforge.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static com.buraktok.reportforge.persistence.PersistenceSupport.openConnection;

final class ProjectSchemaManager {
    private static final String REPORT_EXECUTION_RUNS_TABLE_INFO_SQL = "PRAGMA table_info(report_execution_runs)";

    void initializeSchema(Path databasePath) throws SQLException {
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
                        comments_text TEXT,
                        test_steps_text TEXT,
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
                        remarks TEXT,
                        blocked_reason TEXT,
                        defect_summary TEXT,
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
                    CREATE TABLE IF NOT EXISTS report_execution_run_evidence (
                        id TEXT PRIMARY KEY,
                        execution_run_id TEXT NOT NULL,
                        stored_path TEXT NOT NULL,
                        original_file_name TEXT,
                        media_type TEXT,
                        sort_order INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY(execution_run_id) REFERENCES report_execution_runs(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_execution_run_evidence_run_id ON report_execution_run_evidence(execution_run_id)");

            ensureExecutionRunColumns(connection);
        }
    }

    private void ensureExecutionRunColumns(Connection connection) throws SQLException {
        for (ExecutionRunTextColumn column : ExecutionRunTextColumn.values()) {
            ensureExecutionRunColumn(connection, column);
        }
    }

    private void ensureExecutionRunColumn(Connection connection, ExecutionRunTextColumn column) throws SQLException {
        if (executionRunColumnExists(connection, column.columnName())) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(column.alterSql());
        }
    }

    private boolean executionRunColumnExists(Connection connection, String columnName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(REPORT_EXECUTION_RUNS_TABLE_INFO_SQL)) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private enum ExecutionRunTextColumn {
        TEST_CASE_KEY(
                "test_case_key",
                "ALTER TABLE report_execution_runs ADD COLUMN test_case_key TEXT"
        ),
        SECTION_NAME(
                "section_name",
                "ALTER TABLE report_execution_runs ADD COLUMN section_name TEXT"
        ),
        SUBSECTION_NAME(
                "subsection_name",
                "ALTER TABLE report_execution_runs ADD COLUMN subsection_name TEXT"
        ),
        TEST_CASE_NAME(
                "test_case_name",
                "ALTER TABLE report_execution_runs ADD COLUMN test_case_name TEXT"
        ),
        PRIORITY(
                "priority",
                "ALTER TABLE report_execution_runs ADD COLUMN priority TEXT"
        ),
        MODULE_NAME(
                "module_name",
                "ALTER TABLE report_execution_runs ADD COLUMN module_name TEXT"
        ),
        STATUS(
                "status",
                "ALTER TABLE report_execution_runs ADD COLUMN status TEXT"
        ),
        EXECUTION_TIME(
                "execution_time",
                "ALTER TABLE report_execution_runs ADD COLUMN execution_time TEXT"
        ),
        EXPECTED_RESULT_SUMMARY(
                "expected_result_summary",
                "ALTER TABLE report_execution_runs ADD COLUMN expected_result_summary TEXT"
        ),
        ACTUAL_RESULT(
                "actual_result",
                "ALTER TABLE report_execution_runs ADD COLUMN actual_result TEXT"
        ),
        COMMENTS_TEXT(
                "comments_text",
                "ALTER TABLE report_execution_runs ADD COLUMN comments_text TEXT"
        ),
        TEST_STEPS_TEXT(
                "test_steps_text",
                "ALTER TABLE report_execution_runs ADD COLUMN test_steps_text TEXT"
        ),
        RELATED_ISSUE(
                "related_issue",
                "ALTER TABLE report_execution_runs ADD COLUMN related_issue TEXT"
        ),
        REMARKS(
                "remarks",
                "ALTER TABLE report_execution_runs ADD COLUMN remarks TEXT"
        ),
        BLOCKED_REASON(
                "blocked_reason",
                "ALTER TABLE report_execution_runs ADD COLUMN blocked_reason TEXT"
        ),
        DEFECT_SUMMARY(
                "defect_summary",
                "ALTER TABLE report_execution_runs ADD COLUMN defect_summary TEXT"
        );

        private final String columnName;
        private final String alterSql;

        ExecutionRunTextColumn(String columnName, String alterSql) {
            this.columnName = columnName;
            this.alterSql = alterSql;
        }

        String columnName() {
            return columnName;
        }

        String alterSql() {
            return alterSql;
        }
    }
}
