package com.buraktok.reportforge.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ProjectWorkspace {
    private final ProjectSummary project;
    private final List<ApplicationEntry> projectApplications;
    private final List<EnvironmentRecord> environments;
    private final Map<String, List<ReportRecord>> reportsByEnvironment;

    public ProjectWorkspace(
            ProjectSummary project,
            List<ApplicationEntry> projectApplications,
            List<EnvironmentRecord> environments,
            Map<String, List<ReportRecord>> reportsByEnvironment
    ) {
        this.project = project;
        this.projectApplications = List.copyOf(projectApplications);
        this.environments = List.copyOf(environments);
        this.reportsByEnvironment = Map.copyOf(reportsByEnvironment);
    }

    public ProjectSummary getProject() {
        return project;
    }

    public List<ApplicationEntry> getProjectApplications() {
        return projectApplications;
    }

    public List<EnvironmentRecord> getEnvironments() {
        return environments;
    }

    public List<ReportRecord> getReportsForEnvironment(String environmentId) {
        return reportsByEnvironment.getOrDefault(environmentId, Collections.emptyList());
    }

    public Map<String, List<ReportRecord>> getReportsByEnvironment() {
        return reportsByEnvironment;
    }
}
