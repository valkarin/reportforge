package com.buraktok.reportforge.persistence;

import com.buraktok.reportforge.model.RecentProject;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.prefs.Preferences;

public final class RecentProjectsService {
    private static final String RECENT_PROJECTS_KEY = "recentProjects";
    private static final int MAX_RECENT_PROJECTS = 15;
    private static final Logger LOGGER = LoggerFactory.getLogger(RecentProjectsService.class);

    private final Preferences preferences = Preferences.userNodeForPackage(RecentProjectsService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Loads the list of recently opened projects from the user's system preferences.
     * Projects are sorted by the most recently opened date.
     *
     * @return a list of recent projects, up to the maximum limit
     */
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
            LOGGER.warn("Unable to read recent projects from preferences.", exception);
            return List.of();
        }
    }

    /**
     * Updates or creates a recent project entry to indicate it was just opened.
     * Moves the updated project to the top of the recent projects list.
     *
     * @param projectPath the absolute path to the project file
     * @param name        the display name of the project
     */
    public void touchProject(Path projectPath, String name) {
        List<RecentProject> updatedProjects = new ArrayList<>(loadRecentProjects());
        updatedProjects.removeIf(project -> project.getPath().equalsIgnoreCase(projectPath.toString()));
        updatedProjects.add(0, new RecentProject(name, projectPath.toString(), Instant.now().toString()));
        List<RecentProject> trimmedProjects = updatedProjects.stream().limit(MAX_RECENT_PROJECTS).toList();
        writeRecentProjects(trimmedProjects);
    }

    /**
     * Removes a project from the recent projects list.
     *
     * @param projectPath the absolute path of the project to remove
     */
    public void removeProject(Path projectPath) {
        List<RecentProject> updatedProjects = new ArrayList<>(loadRecentProjects());
        updatedProjects.removeIf(project -> project.getPath().equalsIgnoreCase(projectPath.toString()));
        writeRecentProjects(updatedProjects);
    }

    /**
     * Serializes the recent projects list and writes it to the user preferences.
     *
     * @param recentProjects the current list of recent projects to persist
     */
    private void writeRecentProjects(List<RecentProject> recentProjects) {
        try {
            List<RecentProjectData> data = recentProjects.stream()
                    .map(project -> new RecentProjectData(project.getName(), project.getPath(), project.getLastOpenedAt()))
                    .toList();
            preferences.put(RECENT_PROJECTS_KEY, objectMapper.writeValueAsString(data));
        } catch (Exception exception) {
            LOGGER.error("Unable to write recent projects to preferences.", exception);
        }
    }
}
