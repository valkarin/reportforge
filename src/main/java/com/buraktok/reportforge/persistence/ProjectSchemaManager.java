package com.buraktok.reportforge.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static com.buraktok.reportforge.persistence.PersistenceSupport.openConnection;

final class ProjectSchemaManager {
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

            ensureColumn(connection, "report_execution_runs", "test_case_key", "TEXT");
            ensureColumn(connection, "report_execution_runs", "section_name", "TEXT");
            ensureColumn(connection, "report_execution_runs", "subsection_name", "TEXT");
            ensureColumn(connection, "report_execution_runs", "test_case_name", "TEXT");
            ensureColumn(connection, "report_execution_runs", "priority", "TEXT");
            ensureColumn(connection, "report_execution_runs", "module_name", "TEXT");
            ensureColumn(connection, "report_execution_runs", "status", "TEXT");
            ensureColumn(connection, "report_execution_runs", "execution_time", "TEXT");
            ensureColumn(connection, "report_execution_runs", "expected_result_summary", "TEXT");
            ensureColumn(connection, "report_execution_runs", "actual_result", "TEXT");
            ensureColumn(connection, "report_execution_runs", "related_issue", "TEXT");
            ensureColumn(connection, "report_execution_runs", "remarks", "TEXT");
            ensureColumn(connection, "report_execution_runs", "blocked_reason", "TEXT");
            ensureColumn(connection, "report_execution_runs", "defect_summary", "TEXT");
        }
    }

    private void ensureColumn(Connection connection, String tableName, String columnName, String definition) throws SQLException {
        if (columnExists(connection, tableName, columnName)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }
}
