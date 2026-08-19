package com.example.jenkins.managedfile.model;

/**
 * In-memory snapshot of a managed file. Used by the listener to compute
 * deltas without touching the disk for every comparison.
 */
public class ConfigSnapshot {

    private final String id;
    private final String name;
    private final String content;
    private final String sha256;

    public ConfigSnapshot(String id, String name, String content, String sha256) {
        this.id = id;
        this.name = name;
        this.content = content;
        this.sha256 = sha256;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getContent() {
        return content;
    }

    public String getSha256() {
        return sha256;
    }
}
