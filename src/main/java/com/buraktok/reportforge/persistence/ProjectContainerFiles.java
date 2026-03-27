package com.buraktok.reportforge.persistence;

import tools.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class ProjectContainerFiles {
    private final ObjectMapper objectMapper;

    ProjectContainerFiles(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void initializeWorkspaceDirectories(Path workspaceRoot) throws IOException {
        Files.createDirectories(workspaceRoot.resolve("data"));
        Files.createDirectories(workspaceRoot.resolve("evidence"));
        Files.createDirectories(workspaceRoot.resolve("templates"));
        Files.createDirectories(workspaceRoot.resolve("settings"));
        Files.createDirectories(workspaceRoot.resolve("meta"));
    }

    void writeAuxiliaryFiles(ProjectSession session) throws IOException {
        writeManifest(session.workspaceRoot().resolve("manifest.json"), session.manifestData());
        writeTextFile(session.workspaceRoot().resolve("templates").resolve("branding.json"), ProjectContainerService.DEFAULT_BRANDING_JSON);
        writeTextFile(session.workspaceRoot().resolve("settings").resolve("export-presets.json"), ProjectContainerService.DEFAULT_EXPORT_PRESETS_JSON);
        writeChecksums(session.workspaceRoot().resolve("meta").resolve("checksums.json"), buildChecksums(session.workspaceRoot()));
    }

    void saveProjectArchive(ProjectSession session) throws IOException {
        Path targetFile = session.projectFile();
        Path parentDirectory = targetFile.getParent();
        if (parentDirectory != null) {
            Files.createDirectories(parentDirectory);
        }

        Path temporaryTarget = Files.createTempFile(parentDirectory, "reportforge-", ".tmp");
        try (OutputStream outputStream = Files.newOutputStream(temporaryTarget);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            java.util.List<Path> paths;
            try (var pathStream = Files.walk(session.workspaceRoot())) {
                paths = pathStream
                        .sorted(Comparator.comparing(path -> session.workspaceRoot().relativize(path).toString()))
                        .toList();
            }
            for (Path path : paths) {
                if (path.equals(session.workspaceRoot())) {
                    continue;
                }
                String relativePath = session.workspaceRoot().relativize(path).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(Files.isDirectory(path) ? relativePath + "/" : relativePath);
                zipOutputStream.putNextEntry(entry);
                if (Files.isRegularFile(path)) {
                    Files.copy(path, zipOutputStream);
                }
                zipOutputStream.closeEntry();
            }
        }

        Files.move(temporaryTarget, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    ManifestData readManifest(Path path) throws IOException {
        return readJsonFile(path, ManifestData.class);
    }

    void extractProjectContainer(Path projectFile, Path workspaceRoot) throws IOException {
        int fileCount = 0;
        long totalSize = 0L;

        try (InputStream inputStream = Files.newInputStream(projectFile);
             BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
             ZipInputStream zipInputStream = new ZipInputStream(bufferedInputStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String normalizedEntryName = normalizeZipEntryName(entry.getName());
                if (normalizedEntryName.isBlank()) {
                    zipInputStream.closeEntry();
                    continue;
                }
                if (!isAllowedEntry(normalizedEntryName)) {
                    throw new IOException("Failed to read project data.");
                }
                fileCount++;
                if (fileCount > ProjectContainerService.MAX_FILE_COUNT) {
                    throw new IOException("Failed to read project data.");
                }

                Path outputPath = workspaceRoot.resolve(normalizedEntryName.replace("/", "\\")).normalize();
                if (!outputPath.startsWith(workspaceRoot)) {
                    throw new IOException("Failed to read project data.");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                    zipInputStream.closeEntry();
                    continue;
                }

                Files.createDirectories(outputPath.getParent());
                long entryBytes = copyWithLimit(zipInputStream, outputPath, ProjectContainerService.MAX_ENTRY_SIZE);
                totalSize += entryBytes;
                if (totalSize > ProjectContainerService.MAX_TOTAL_UNCOMPRESSED_SIZE) {
                    throw new IOException("Failed to read project data.");
                }
                zipInputStream.closeEntry();
            }
        }
    }

    void validateChecksums(Path workspaceRoot) throws IOException {
        Path checksumPath = workspaceRoot.resolve("meta").resolve("checksums.json");
        if (!Files.exists(checksumPath)) {
            return;
        }
        ChecksumsData checksumsData = readChecksums(checksumPath);
        if (checksumsData == null || checksumsData.files() == null) {
            throw new IOException("Failed to read project data.");
        }
        for (Map.Entry<String, String> entry : checksumsData.files().entrySet()) {
            Path filePath = workspaceRoot.resolve(entry.getKey().replace("/", "\\"));
            if (!Files.exists(filePath) || !Objects.equals(entry.getValue(), sha256(filePath))) {
                throw new IOException("Failed to read project data.");
            }
        }
    }

    Path resolveWorkspacePath(ProjectSession session, String relativePath) {
        Path workspaceRoot = session.workspaceRoot().toAbsolutePath().normalize();
        if (relativePath == null || relativePath.isBlank()) {
            return workspaceRoot;
        }
        try {
            Path candidatePath = Path.of(relativePath.replace("/", "\\"));
            if (candidatePath.isAbsolute()) {
                throw new IllegalArgumentException("Workspace path must be relative.");
            }
            Path resolvedPath = workspaceRoot.resolve(candidatePath).normalize();
            if (!resolvedPath.startsWith(workspaceRoot)) {
                throw new IllegalArgumentException("Workspace path escapes the project workspace.");
            }
            return resolvedPath;
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Workspace path is invalid.", exception);
        }
    }

    void deleteDirectorySilently(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        FileVisitor<Path> visitor = new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        };
        try {
            Files.walkFileTree(root, visitor);
        } catch (IOException ignored) {
            // Best-effort cleanup for temporary working directories.
        }
    }

    private ChecksumsData buildChecksums(Path workspaceRoot) throws IOException {
        Path manifestPath = workspaceRoot.resolve("manifest.json");
        Path databasePath = workspaceRoot.resolve(ProjectContainerService.DEFAULT_DATABASE_PATH.replace("/", "\\"));
        Map<String, String> checksums = new LinkedHashMap<>();
        checksums.put("manifest.json", sha256(manifestPath));
        checksums.put(ProjectContainerService.DEFAULT_DATABASE_PATH, sha256(databasePath));
        Path evidenceRoot = workspaceRoot.resolve("evidence");
        if (Files.isDirectory(evidenceRoot)) {
            try (var evidenceFiles = Files.walk(evidenceRoot)) {
                evidenceFiles
                        .filter(Files::isRegularFile)
                        .sorted()
                        .forEach(path -> checksums.put(
                                workspaceRoot.relativize(path).toString().replace("\\", "/"),
                                sha256Unchecked(path)
                        ));
            }
        }
        return new ChecksumsData(checksums);
    }

    private long copyWithLimit(InputStream inputStream, Path outputPath, long maxBytes) throws IOException {
        long totalCopied = 0L;
        byte[] buffer = new byte[8192];
        try (OutputStream outputStream = Files.newOutputStream(outputPath)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                totalCopied += read;
                if (totalCopied > maxBytes) {
                    throw new IOException("Failed to read project data.");
                }
                outputStream.write(buffer, 0, read);
            }
        }
        return totalCopied;
    }

    private boolean isAllowedEntry(String entryName) {
        return entryName.equals("manifest.json")
                || entryName.equals(ProjectContainerService.DEFAULT_DATABASE_PATH)
                || entryName.equals("templates/branding.json")
                || entryName.equals("settings/export-presets.json")
                || entryName.equals("meta/checksums.json")
                || entryName.startsWith("evidence/")
                || entryName.startsWith("templates/")
                || entryName.startsWith("settings/")
                || entryName.startsWith("meta/")
                || entryName.startsWith("data/");
    }

    private String normalizeZipEntryName(String entryName) {
        String normalized = entryName.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.contains("..")) {
            return "";
        }
        return normalized;
    }

    private void writeTextFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private void writeManifest(Path path, ManifestData manifestData) throws IOException {
        writeJsonFile(path, manifestData);
    }

    private void writeChecksums(Path path, ChecksumsData checksumsData) throws IOException {
        writeJsonFile(path, checksumsData);
    }

    private ChecksumsData readChecksums(Path path) throws IOException {
        return readJsonFile(path, ChecksumsData.class);
    }

    private void writeJsonFile(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private <T> T readJsonFile(Path path, Class<T> type) throws IOException {
        return objectMapper.readValue(path.toFile(), type);
    }

    private String sha256(Path path) throws IOException {
        try (InputStream inputStream = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder builder = new StringBuilder();
            for (byte value : digest.digest()) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("Unable to compute checksums.", exception);
        }
    }

    private String sha256Unchecked(Path path) {
        try {
            return sha256(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to compute checksums.", exception);
        }
    }
}
