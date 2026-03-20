package com.buraktok.reportforge.persistence;

import com.buraktok.reportforge.model.ApplicationEntry;
import com.buraktok.reportforge.model.EnvironmentRecord;
import com.buraktok.reportforge.model.ProjectSummary;
import com.buraktok.reportforge.model.ProjectWorkspace;
import com.buraktok.reportforge.model.ReportRecord;
import com.buraktok.reportforge.model.ReportStatus;
import com.buraktok.reportforge.model.TestExecutionSection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.buraktok.reportforge.persistence.PersistenceSupport.countRows;
import static com.buraktok.reportforge.persistence.PersistenceSupport.nextSortOrder;
import static com.buraktok.reportforge.persistence.PersistenceSupport.nullableText;
import static com.buraktok.reportforge.persistence.PersistenceSupport.openConnection;
import static com.buraktok.reportforge.persistence.PersistenceSupport.requireText;

final class ProjectWorkspaceStore {
    ProjectWorkspace loadWorkspace(ProjectSession session) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
            ProjectSummary summary = loadProjectSummary(connection);
            List<ApplicationEntry> projectApplications = loadApplications(connection, "project_applications", null);
            List<EnvironmentRecord> environments = loadEnvironments(connection);
            Map<String, List<ReportRecord>> reportsByEnvironment = loadReportsByEnvironment(connection);
            return new ProjectWorkspace(summary, projectApplications, environments, reportsByEnvironment);
        }
    }

    Map<String, String> loadReportFields(ProjectSession session, String reportId) throws SQLException {
        Map<String, String> fieldValues = new HashMap<>();
        try (Connection connection = openConnection(session.databasePath());
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

    List<ApplicationEntry> loadReportApplications(ProjectSession session, String reportId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
            return loadApplications(connection, "report_applications", reportId);
        }
    }

    EnvironmentRecord loadReportEnvironmentSnapshot(ProjectSession session, String reportId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath());
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

    void updateProject(ProjectSession session, String name, String description) throws SQLException {
        String now = Instant.now().toString();
        try (Connection connection = openConnection(session.databasePath());
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE project_info SET name = ?, description = ?, updated_at = ? WHERE rowid = 1")) {
            statement.setString(1, requireText(name, "Project Name"));
            statement.setString(2, nullableText(description));
            statement.setString(3, now);
            statement.executeUpdate();
        }
    }

    EnvironmentRecord createEnvironment(ProjectSession session, String name) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
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

    void updateEnvironment(ProjectSession session, EnvironmentRecord environment) throws SQLException {
        try (Connection connection = openConnection(session.databasePath());
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

    void deleteEnvironment(ProjectSession session, String environmentId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
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

    void upsertProjectApplication(ProjectSession session, ApplicationEntry application) throws SQLException {
        upsertApplication(session, "project_applications", null, application);
    }

    void deleteProjectApplication(ProjectSession session, String applicationId) throws SQLException {
        deleteApplication(session, "project_applications", applicationId, null);
    }

    void setPrimaryProjectApplication(ProjectSession session, String applicationId) throws SQLException {
        setPrimaryApplication(session, "project_applications", applicationId, null);
    }

    ReportRecord loadReport(ProjectSession session, String reportId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath());
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

    void updateReportStatus(ProjectSession session, String reportId, ReportStatus status) throws SQLException {
        updateReportColumn(session, reportId, "status", status.name());
    }

    void updateReportTitle(ProjectSession session, String reportId, String title) throws SQLException {
        updateReportColumn(session, reportId, "title", requireText(title, "Report Title"));
    }

    void updateLastSelectedSection(ProjectSession session, String reportId, TestExecutionSection section) throws SQLException {
        updateReportColumn(session, reportId, "last_selected_section", section.name());
    }

    void updateReportField(ProjectSession session, String reportId, String fieldKey, String fieldValue) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
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

    void upsertReportApplication(ProjectSession session, String reportId, ApplicationEntry application) throws SQLException {
        upsertApplication(session, "report_applications", reportId, application);
        touchReport(session, reportId);
    }

    void deleteReportApplication(ProjectSession session, String applicationId, String reportId) throws SQLException {
        deleteApplication(session, "report_applications", applicationId, reportId);
        touchReport(session, reportId);
    }

    void setPrimaryReportApplication(ProjectSession session, String reportId, String applicationId) throws SQLException {
        setPrimaryApplication(session, "report_applications", applicationId, reportId);
        touchReport(session, reportId);
    }

    void updateReportEnvironmentSnapshot(ProjectSession session, String reportId, EnvironmentRecord environmentRecord) throws SQLException {
        try (Connection connection = openConnection(session.databasePath());
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

    void moveReportToEnvironment(ProjectSession session, String reportId, String targetEnvironmentId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath());
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE reports SET environment_id = ?, updated_at = ? WHERE id = ?")) {
            statement.setString(1, targetEnvironmentId);
            statement.setString(2, Instant.now().toString());
            statement.setString(3, reportId);
            statement.executeUpdate();
            snapshotEnvironment(connection, reportId, targetEnvironmentId);
        }
    }

    void deleteReport(ProjectSession session, String reportId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath());
             PreparedStatement statement = connection.prepareStatement("DELETE FROM reports WHERE id = ?")) {
            statement.setString(1, reportId);
            statement.executeUpdate();
        }
    }

    void insertInitialApplications(Connection connection, List<String> applicationNames) throws SQLException {
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

    void insertEnvironment(Connection connection, EnvironmentRecord environment) throws SQLException {
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

    void snapshotProjectOverview(Connection connection, String reportId) throws SQLException {
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

    void snapshotApplications(Connection connection, String reportId) throws SQLException {
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

    void snapshotEnvironment(Connection connection, String reportId, String environmentId) throws SQLException {
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

    void copyReportFields(Connection connection, String sourceReportId, String targetReportId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO report_fields (report_id, field_key, field_value) " +
                        "SELECT ?, field_key, field_value FROM report_fields WHERE report_id = ?")) {
            statement.setString(1, targetReportId);
            statement.setString(2, sourceReportId);
            statement.executeUpdate();
        }
    }

    void copyReportApplications(Connection connection, String sourceReportId, String targetReportId) throws SQLException {
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

    void touchReport(ProjectSession session, String reportId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
            touchReport(connection, reportId);
        }
    }

    void touchReport(Connection connection, String reportId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE reports SET updated_at = ? WHERE id = ?")) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, reportId);
            statement.executeUpdate();
        }
    }

    private void updateReportColumn(ProjectSession session, String reportId, String columnName, String value) throws SQLException {
        String sql = "UPDATE reports SET " + columnName + " = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = openConnection(session.databasePath());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.setString(2, Instant.now().toString());
            statement.setString(3, reportId);
            statement.executeUpdate();
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

    private void upsertApplication(ProjectSession session, String tableName, String reportId, ApplicationEntry application) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
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

    private void deleteApplication(ProjectSession session, String tableName, String applicationId, String reportId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
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

    private void setPrimaryApplication(ProjectSession session, String tableName, String applicationId, String reportId) throws SQLException {
        try (Connection connection = openConnection(session.databasePath())) {
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
}
