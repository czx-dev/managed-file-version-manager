package com.example.jenkins.managedfile.store;

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
 * <p>Hand-rolled parsing keeps the dependency surface flat (no Jackson). The
 * store is concurrency-safe with a single {@link ReentrantLock}; traffic is
 * low (UI edits only) so contention is irrelevant.</p>
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
    // Hand-rolled to keep the dependency footprint flat. The format is
    // intentionally trivial: a flat object with two known keys. Anything
    // else is rejected.

    private static String format(State s) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"groups\": [\n");
        boolean first = true;
        for (Group g : s.groups) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append("    {")
              .append("\"id\":").append(jsonString(g.getId())).append(",")
              .append("\"name\":").append(jsonString(g.getName())).append(",")
              .append("\"description\":").append(jsonString(g.getDescription()))
              .append("}");
        }
        sb.append("\n  ],\n  \"assignments\": {\n");
        first = true;
        for (Map.Entry<String, String> e : s.assignments.entrySet()) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append("    ")
              .append(jsonString(e.getKey())).append(": ")
              .append(jsonString(e.getValue()));
        }
        sb.append("\n  }\n}\n");
        return sb.toString();
    }

    private static String jsonString(String raw) {
        if (raw == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static State parse(String json) {
        State s = new State();
        Parser p = new Parser(json);
        p.skipWs();
        p.expect('{');
        p.skipWs();
        // We expect key "groups" then key "assignments" in any order, but
        // since we control the writer we only handle this canonical order.
        p.expectKey("groups");
        p.skipWs();
        p.expect('[');
        p.skipWs();
        if (p.peek() != ']') {
            while (true) {
                p.skipWs();
                p.expect('{');
                String id = null, name = null, desc = null;
                p.skipWs();
                if (p.peek() != '}') {
                    while (true) {
                        String k = p.readString();
                        p.skipWs();
                        p.expect(':');
                        p.skipWs();
                        String v = p.readStringOrNull();
                        switch (k) {
                            case "id": id = v; break;
                            case "name": name = v; break;
                            case "description": desc = v; break;
                            default: /* ignore unknown */
                        }
                        p.skipWs();
                        if (p.peek() == ',') { p.advance(); continue; }
                        break;
                    }
                }
                p.expect('}');
                if (id != null) s.groups.add(new Group(id, name, desc));
                p.skipWs();
                if (p.peek() == ',') { p.advance(); continue; }
                break;
            }
        }
        p.expect(']');
        p.skipWs();
        if (p.peek() == ',') p.advance();
        p.skipWs();
        p.expectKey("assignments");
        p.skipWs();
        p.expect('{');
        p.skipWs();
        if (p.peek() != '}') {
            while (true) {
                String k = p.readString();
                p.skipWs();
                p.expect(':');
                p.skipWs();
                String v = p.readStringOrNull();
                if (v != null) s.assignments.put(k, v);
                p.skipWs();
                if (p.peek() == ',') { p.advance(); continue; }
                break;
            }
        }
        p.expect('}');
        p.skipWs();
        if (p.peek() == ',') p.advance();
        p.skipWs();
        p.expect('}');
        return s;
    }

    /** Tiny hand-rolled JSON parser sufficient for our canonical format. */
    private static final class Parser {
        private final String src;
        private int pos;
        Parser(String src) { this.src = src; this.pos = 0; }
        char peek() { return src.charAt(pos); }
        void advance() { pos++; }
        void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }
        void expect(char c) {
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw new IllegalStateException("Expected '" + c + "' at " + pos);
            }
            pos++;
        }
        void expectKey(String key) {
            skipWs();
            String s = readString();
            if (!key.equals(s)) throw new IllegalStateException("Expected key '" + key + "' got '" + s + "'");
        }
        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = src.charAt(pos++);
                    switch (e) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'u':
                            int cp = Integer.parseInt(src.substring(pos, pos + 4), 16);
                            sb.append((char) cp);
                            pos += 4;
                            break;
                        default: throw new IllegalStateException("Bad escape: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalStateException("Unterminated string");
        }
        String readStringOrNull() {
            skipWs();
            if (peek() == 'n') {
                if (src.startsWith("null", pos)) { pos += 4; return null; }
            }
            return readString();
        }
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
