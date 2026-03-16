package com.buraktok.reportforge.model;

public class EnvironmentRecord {
    private final String id;
    private final String name;
    private final String type;
    private final String baseUrl;
    private final String osPlatform;
    private final String browserClient;
    private final String backendVersion;
    private final String notes;
    private final int sortOrder;

    public EnvironmentRecord(
            String id,
            String name,
            String type,
            String baseUrl,
            String osPlatform,
            String browserClient,
            String backendVersion,
            String notes,
            int sortOrder
    ) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.baseUrl = baseUrl;
        this.osPlatform = osPlatform;
        this.browserClient = browserClient;
        this.backendVersion = backendVersion;
        this.notes = notes;
        this.sortOrder = sortOrder;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getOsPlatform() {
        return osPlatform;
    }

    public String getBrowserClient() {
        return browserClient;
    }

    public String getBackendVersion() {
        return backendVersion;
    }

    public String getNotes() {
        return notes;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    @Override
    public String toString() {
        return name;
    }
}
