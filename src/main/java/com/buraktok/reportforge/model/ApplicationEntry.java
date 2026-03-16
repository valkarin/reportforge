package com.buraktok.reportforge.model;

public class ApplicationEntry {
    private final String id;
    private final String name;
    private final String versionOrBuild;
    private final String moduleList;
    private final String platform;
    private final String description;
    private final String relatedServices;
    private final boolean primary;
    private final int sortOrder;

    public ApplicationEntry(
            String id,
            String name,
            String versionOrBuild,
            String moduleList,
            String platform,
            String description,
            String relatedServices,
            boolean primary,
            int sortOrder
    ) {
        this.id = id;
        this.name = name;
        this.versionOrBuild = versionOrBuild;
        this.moduleList = moduleList;
        this.platform = platform;
        this.description = description;
        this.relatedServices = relatedServices;
        this.primary = primary;
        this.sortOrder = sortOrder;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVersionOrBuild() {
        return versionOrBuild;
    }

    public String getModuleList() {
        return moduleList;
    }

    public String getPlatform() {
        return platform;
    }

    public String getDescription() {
        return description;
    }

    public String getRelatedServices() {
        return relatedServices;
    }

    public boolean isPrimary() {
        return primary;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    @Override
    public String toString() {
        String primaryMarker = primary ? " (Primary)" : "";
        String versionMarker = versionOrBuild == null || versionOrBuild.isBlank()
                ? ""
                : " - " + versionOrBuild;
        return name + versionMarker + primaryMarker;
    }
}
