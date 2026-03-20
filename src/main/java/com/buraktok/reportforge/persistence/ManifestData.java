package com.buraktok.reportforge.persistence;

public record ManifestData(
        String formatVersion,
        String appVersion,
        String projectId,
        String projectName,
        String databasePath,
        String createdAt,
        String updatedAt
) {
}
