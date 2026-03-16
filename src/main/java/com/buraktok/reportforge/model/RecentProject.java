package com.buraktok.reportforge.model;

public class RecentProject {
    private final String name;
    private final String path;
    private final String lastOpenedAt;

    public RecentProject(String name, String path, String lastOpenedAt) {
        this.name = name;
        this.path = path;
        this.lastOpenedAt = lastOpenedAt;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public String getLastOpenedAt() {
        return lastOpenedAt;
    }
}
