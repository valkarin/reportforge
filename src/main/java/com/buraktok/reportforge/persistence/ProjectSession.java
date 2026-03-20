package com.buraktok.reportforge.persistence;

import java.nio.file.Path;

public record ProjectSession(Path projectFile, Path workspaceRoot, ManifestData manifestData) {
    public Path databasePath() {
        return workspaceRoot.resolve(manifestData.databasePath().replace("/", "\\"));
    }
}
