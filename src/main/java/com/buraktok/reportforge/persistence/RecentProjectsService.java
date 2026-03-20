package com.buraktok.reportforge.persistence;

import com.buraktok.reportforge.model.RecentProject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.prefs.Preferences;

public final class RecentProjectsService {
    private static final String RECENT_PROJECTS_KEY = "recentProjects";
    private static final int MAX_RECENT_PROJECTS = 15;

    private final Preferences preferences = Preferences.userNodeForPackage(RecentProjectsService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<RecentProject> loadRecentProjects() {
        String raw = preferences.get(RECENT_PROJECTS_KEY, "");
        if (raw.isBlank()) {
            return List.of();
        }
        try {
            List<RecentProjectData> data = objectMapper.readValue(raw, new TypeReference<>() { });
            return data.stream()
                    .map(entry -> new RecentProject(entry.name(), entry.path(), entry.lastOpenedAt()))
                    .sorted(Comparator.comparing(RecentProject::getLastOpenedAt).reversed())
                    .limit(MAX_RECENT_PROJECTS)
                    .toList();
        } catch (Exception exception) {
            return List.of();
        }
    }

    public void touchProject(Path projectPath, String name) {
        List<RecentProject> updatedProjects = new ArrayList<>(loadRecentProjects());
        updatedProjects.removeIf(project -> project.getPath().equalsIgnoreCase(projectPath.toString()));
        updatedProjects.add(0, new RecentProject(name, projectPath.toString(), Instant.now().toString()));
        List<RecentProject> trimmedProjects = updatedProjects.stream().limit(MAX_RECENT_PROJECTS).toList();
        writeRecentProjects(trimmedProjects);
    }

    public void removeProject(Path projectPath) {
        List<RecentProject> updatedProjects = new ArrayList<>(loadRecentProjects());
        updatedProjects.removeIf(project -> project.getPath().equalsIgnoreCase(projectPath.toString()));
        writeRecentProjects(updatedProjects);
    }

    private void writeRecentProjects(List<RecentProject> recentProjects) {
        try {
            List<RecentProjectData> data = recentProjects.stream()
                    .map(project -> new RecentProjectData(project.getName(), project.getPath(), project.getLastOpenedAt()))
                    .toList();
            preferences.put(RECENT_PROJECTS_KEY, objectMapper.writeValueAsString(data));
        } catch (Exception exception) {
            preferences.remove(RECENT_PROJECTS_KEY);
        }
    }
}
