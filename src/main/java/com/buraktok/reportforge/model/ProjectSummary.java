package com.buraktok.reportforge.model;

public class ProjectSummary {
    private final String id;
    private final String name;
    private final String description;
    private final String createdAt;
    private final String updatedAt;

    public ProjectSummary(String id, String name, String description, String createdAt, String updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }
}
