package com.example.jenkins.managedfile.model;

import java.util.Objects;

/**
 * Logical grouping for Managed Files. Persisted by {@code GroupStore} in a
 * sidecar JSON file. Group ids share the safe-character alphabet used for
 * file ids ({@code [A-Za-z0-9._-]+}) but the value never reaches the
 * filesystem - it is only a JSON key.
 */
public final class Group {

    private final String id;
    private final String name;
    private final String description;

    public Group(String id, String name, String description) {
        this.id = id;
        this.name = name == null ? id : name;
        this.description = description == null ? "" : description;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Group g)) return false;
        return Objects.equals(id, g.id)
                && Objects.equals(name, g.name)
                && Objects.equals(description, g.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, description);
    }
}
