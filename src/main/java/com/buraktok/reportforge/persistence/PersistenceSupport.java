package com.buraktok.reportforge.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashSet;

final class PersistenceSupport {
    private PersistenceSupport() {
    }

    enum ScopedSortTarget {
        REPORT_EXECUTION_RUN(
                "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM report_execution_runs WHERE report_id = ?"
        ),
        REPORT_EXECUTION_RUN_EVIDENCE(
                "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM report_execution_run_evidence WHERE execution_run_id = ?"
        );

        private final String sql;

        ScopedSortTarget(String sql) {
            this.sql = sql;
        }

        String sql() {
            return sql;
        }
    }

    @FunctionalInterface
    interface TransactionWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    static Connection openConnection(Path databasePath) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    static <T> T withTransaction(Connection connection, TransactionWork<T> work) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        if (!originalAutoCommit) {
            return work.execute(connection);
        }
        connection.setAutoCommit(false);
        try {
            T result = work.execute(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            rollbackAfterFailure(connection, exception);
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static String nullableText(String value) {
        return value == null ? "" : value.trim();
    }

    static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be empty.");
        }
        return value.trim();
    }

    static void setNullableInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
            return;
        }
        statement.setInt(index, value);
    }

    static Integer toNullableInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static Integer readNullableInteger(ResultSet resultSet, String columnName) throws SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }

    static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    static String selectBoundaryDate(String currentValue, String candidateValue, boolean earliest) {
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

    static LocalDate parseDateOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    static String normalizeOutcome(String outcome) {
        return outcome == null || outcome.isBlank() ? "NOT_EXECUTED" : outcome;
    }

    static String normalizeResultStatus(String status) {
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

    static String aggregateOutcome(LinkedHashSet<String> outcomes) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String outcome : outcomes) {
            normalized.add(normalizeOutcome(outcome));
        }
        if (normalized.isEmpty()) {
            return "NOT_EXECUTED";
        }
        return normalized.size() == 1 ? normalized.iterator().next() : "MIXED";
    }

    static int nextScopedSortOrder(Connection connection, ScopedSortTarget target, String scopeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(target.sql())) {
            statement.setString(1, scopeId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    static int countRows(Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private static void rollbackAfterFailure(Connection connection, Exception exception) throws SQLException {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            exception.addSuppressed(rollbackException);
        }
    }
}
