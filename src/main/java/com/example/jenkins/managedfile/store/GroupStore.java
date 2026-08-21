package com.example.jenkins.managedfile.store;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import com.example.jenkins.managedfile.model.Group;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Sidecar persistence for Managed File <em>groups</em>.
 *
 * <p>Stored as a single JSON document at
 * {@code <JENKINS_HOME>/managed-file-version-manager/groups.json} with shape:
 *
 * <pre>
 * {
 *   "groups": [ {"id":"g1","name":"Production","description":"..."} , ... ],
 *   "assignments": { "fileId1": "g1", "fileId2": "g2" }
 * }
 * </pre>
 *
 * <p>JSON (de)serialisation is delegated to fastjson2. The store is
 * concurrency-safe with a single {@link ReentrantLock}; traffic is low
 * (UI edits only) so contention is irrelevant.</p>
 */
public class GroupStore {

    private static final Logger LOGGER = Logger.getLogger(GroupStore.class.getName());

    public static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9._\\-]+");

    private static final GroupStore INSTANCE = new GroupStore();

    public static GroupStore getInstance() {
        return INSTANCE;
    }

    private final ReentrantLock lock = new ReentrantLock();
    private File _rootDir;

    public GroupStore() {
    }

    /** Test-only seam. */
    public void overrideRootForTest(File root) {
        this._rootDir = root;
    }

    public void init() {
        lock.lock();
        try {
            if (_rootDir == null) {
                File jenkinsHome = Jenkins.get().getRootDir();
                File dir = new File(jenkinsHome, "managed-file-version-manager");
                if (!dir.exists() && !dir.mkdirs()) {
                    LOGGER.warning("Cannot create managed-file-version-manager dir at " + dir);
                }
                _rootDir = dir;
            }
            ensureLoaded();
        } finally {
            lock.unlock();
        }
    }

    private File rootDir() {
        if (_rootDir == null) throw new IllegalStateException("GroupStore.init() has not been called");
        return _rootDir;
    }

    /** Absolute path of the on-disk storage root, for display only. */
    public String getStoragePath() {
        return _rootDir == null ? "(uninitialised)" : _rootDir.getAbsolutePath();
    }

    private File groupsFile() {
        return new File(rootDir(), "groups.json");
    }

    // ---------- domain ----------

    public static void validateId(String id) {
        if (id == null || id.isEmpty() || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid group id: " + id);
        }
    }

    public List<Group> listGroups() {
        ensureLoaded();
        List<Group> copy;
        lock.lock();
        try {
            copy = new ArrayList<>(state.groups);
        } finally {
            lock.unlock();
        }
        copy.sort(Comparator.comparing(g -> g.getName() == null ? g.getId() : g.getName(),
                String.CASE_INSENSITIVE_ORDER));
        return copy;
    }

    public Group getGroup(String groupId) {
        if (groupId == null) return null;
        ensureLoaded();
        lock.lock();
        try {
            for (Group g : state.groups) {
                if (groupId.equals(g.getId())) return g;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Create a new group. Throws if the id already exists.
     */
    public Group createGroup(String id, String name, String description) {
        validateId(id);
        ensureLoaded();
        lock.lock();
        try {
            for (Group g : state.groups) {
                if (id.equals(g.getId())) {
                    throw new IllegalStateException("Group already exists: " + id);
                }
            }
            Group g = new Group(id, name, description);
            state.groups.add(g);
            persistLocked();
            return g;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Delete a group. Any file mapping to it is also cleared so a file is
     * never left pointing at a non-existent group.
     */
    public void deleteGroup(String id) {
        if (id == null) return;
        ensureLoaded();
        lock.lock();
        try {
            state.groups.removeIf(g -> id.equals(g.getId()));
            state.assignments.values().remove(id);
            persistLocked();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Assign a file to a group. Pass {@code null} or empty {@code groupId}
     * to clear the mapping. Silently no-ops if the file does not exist in
     * {@link VersionStore#FILE_ID_PATTERN} constraints.
     */
    public void assign(String fileId, String groupId) {
        VersionStore.validateFileId(fileId);
        ensureLoaded();
        lock.lock();
        try {
            if (groupId == null || groupId.isEmpty()) {
                state.assignments.remove(fileId);
            } else {
                validateId(groupId);
                boolean groupExists = false;
                for (Group g : state.groups) {
                    if (groupId.equals(g.getId())) {
                        groupExists = true;
                        break;
                    }
                }
                if (!groupExists) {
                    throw new IllegalArgumentException("Unknown group id: " + groupId);
                }
                state.assignments.put(fileId, groupId);
            }
            persistLocked();
        } finally {
            lock.unlock();
        }
    }

    public void unassign(String fileId) {
        VersionStore.validateFileId(fileId);
        ensureLoaded();
        lock.lock();
        try {
            state.assignments.remove(fileId);
            persistLocked();
        } finally {
            lock.unlock();
        }
    }

    /** @return the group id assigned to {@code fileId}, or {@code null}. */
    public String getFileGroupId(String fileId) {
        if (fileId == null) return null;
        ensureLoaded();
        lock.lock();
        try {
            return state.assignments.get(fileId);
        } finally {
            lock.unlock();
        }
    }

    /** @return all assignments, keyed by fileId. Snapshot copy, safe to iterate. */
    public Map<String, String> snapshotAssignments() {
        ensureLoaded();
        lock.lock();
        try {
            return new LinkedHashMap<>(state.assignments);
        } finally {
            lock.unlock();
        }
    }

    // ---------- persistence ----------

    private static final class State {
        final List<Group> groups = new ArrayList<>();
        final Map<String, String> assignments = new LinkedHashMap<>();
    }

    private State state = new State();
    private boolean loaded = false;

    private void ensureLoaded() {
        lock.lock();
        try {
            if (_rootDir == null) {
                // The listener's @PostConstruct may not have run yet (e.g. when a
                // Jelly template is rendered before all extensions are
                // initialised). Fall back to lazy init so first-touch never
                // throws.
                File jenkinsHome = Jenkins.get().getRootDir();
                File dir = new File(jenkinsHome, "managed-file-version-manager");
                if (!dir.exists() && !dir.mkdirs()) {
                    LOGGER.warning("Cannot create managed-file-version-manager dir at " + dir);
                }
                _rootDir = dir;
            }
            if (loaded) return;
            loaded = true;
            File f = groupsFile();
            if (!f.exists()) {
                state = new State();
                return;
            }
            try {
                String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                state = parse(json);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "groups.json is corrupt - starting with empty state", e);
                state = new State();
            }
        } finally {
            lock.unlock();
        }
    }

    private void persistLocked() {
        File f = groupsFile();
        try {
            Path target = f.toPath();
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            // Make sure the parent directory exists. The listener's
            // @PostConstruct should have created it, but defensive
            // mkdirs() makes this robust to first-use before initialiseSnapshot
            // or a misconfigured JENKINS_HOME.
            File parent = Objects.requireNonNull(target.getParent(), "groups.json target must have a parent").toFile();
            if (!parent.exists() && !parent.mkdirs()) {
                throw new IOException("Cannot create parent dir: " + parent);
            }
            Files.writeString(tmp, format(state), StandardCharsets.UTF_8);
            try {
                java.nio.file.Files.move(tmp, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException atomic) {
                java.nio.file.Files.move(tmp, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to persist groups.json", e);
            throw new RuntimeException(e);
        }
    }

    // ---------- (de)serialisation ----------
    //
    // Delegated to fastjson2. We carry state as POJOs and let fastjson2 do the
    // pretty-printing / parsing. Pretty format keeps diffs of groups.json
    // human-readable.

    /** DTO for {@link State#groups}. Only fields present in the on-disk schema are declared. */
    private static final class GroupDto {
        public String id;
        public String name;
        public String description;
    }

    /** Top-level DTO that mirrors the on-disk JSON document. */
    private static final class DocumentDto {
        public List<GroupDto> groups = new ArrayList<>();
        public Map<String, String> assignments = new LinkedHashMap<>();
    }

    private static String format(State s) {
        DocumentDto doc = new DocumentDto();
        for (Group g : s.groups) {
            GroupDto gd = new GroupDto();
            gd.id = g.getId();
            gd.name = g.getName();
            gd.description = g.getDescription();
            doc.groups.add(gd);
        }
        doc.assignments = new LinkedHashMap<>(s.assignments);
        return JSON.toJSONString(doc, JSONWriter.Feature.PrettyFormat);
    }

    private static State parse(String json) {
        State s = new State();
        // JSONReader.Feature.SupportSmartMatch lets numbers coerce to String fields
        // (we store everything as String) without surprising type errors.
        DocumentDto doc = JSON.parseObject(json, DocumentDto.class, JSONReader.Feature.SupportSmartMatch);
        if (doc == null) return s;
        if (doc.groups != null) {
            for (GroupDto gd : doc.groups) {
                if (gd == null || gd.id == null || gd.id.isEmpty()) continue;
                s.groups.add(new Group(gd.id, gd.name, gd.description));
            }
        }
        if (doc.assignments != null) {
            for (Map.Entry<String, String> e : doc.assignments.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    s.assignments.put(e.getKey(), e.getValue());
                }
            }
        }
        return s;
    }

    /** Used by tests to start clean. */
    void resetForTest() {
        lock.lock();
        try {
            state = new State();
            loaded = false;
        } finally {
            lock.unlock();
        }
    }

    /** Convenience used by tests; not part of the public contract. */
    List<Group> rawGroupsForTest() {
        lock.lock();
        try {
            return Collections.unmodifiableList(state.groups);
        } finally {
            lock.unlock();
        }
    }
}
