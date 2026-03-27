package com.buraktok.reportforge.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectContainerFilesTest {
    @TempDir
    Path tempDir;

    private final ProjectContainerService service = new ProjectContainerService();

    @AfterEach
    void tearDown() {
        service.closeCurrentSession();
    }

    @Test
    void resolveProjectPathNormalizesContainedPaths() throws Exception {
        service.createProject(tempDir.resolve("path-normalization.rforge"), "Path Validation", "QA", List.of("Portal"));

        Path workspaceRoot = service.getCurrentSession().workspaceRoot().toAbsolutePath().normalize();
        Path resolvedPath = service.resolveProjectPath("evidence/../meta/./checksums.json");

        assertAll(
                () -> assertEquals(workspaceRoot.resolve("meta").resolve("checksums.json"), resolvedPath),
                () -> assertTrue(resolvedPath.startsWith(workspaceRoot))
        );
    }

    @Test
    void resolveProjectPathRejectsPathsThatEscapeWorkspace() throws Exception {
        service.createProject(tempDir.resolve("path-escape.rforge"), "Path Validation", "QA", List.of("Portal"));

        assertThrows(IllegalArgumentException.class, () -> service.resolveProjectPath("..\\..\\outside.txt"));
    }

    @Test
    void resolveProjectPathRejectsAbsolutePaths() throws Exception {
        service.createProject(tempDir.resolve("absolute-path.rforge"), "Path Validation", "QA", List.of("Portal"));

        Path outsidePath = tempDir.resolve("outside.txt").toAbsolutePath();
        assertThrows(IllegalArgumentException.class, () -> service.resolveProjectPath(outsidePath.toString()));
    }
}
