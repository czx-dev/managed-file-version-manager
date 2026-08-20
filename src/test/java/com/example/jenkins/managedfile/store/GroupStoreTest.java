package com.example.jenkins.managedfile.store;

import com.example.jenkins.managedfile.model.Group;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class GroupStoreTest {

    private GroupStore store;
    private File tmpRoot;

    @Before
    public void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("mfvm-group-test").toFile();
        store = new GroupStore();
        store.overrideRootForTest(tmpRoot);
        store.init();
    }

    @After
    public void tearDown() throws IOException {
        deleteRecursive(tmpRoot);
    }

    @Test
    public void createAndListGroup() {
        store.createGroup("prod", "Production", "live env");
        List<Group> groups = store.listGroups();
        assertEquals(1, groups.size());
        assertEquals("prod", groups.get(0).getId());
        assertEquals("Production", groups.get(0).getName());
        assertEquals("live env", groups.get(0).getDescription());
    }

    @Test(expected = IllegalStateException.class)
    public void duplicateGroupIdRejected() {
        store.createGroup("prod", "Production", null);
        store.createGroup("prod", "Other", null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidGroupIdRejected() {
        store.createGroup("../bad", "X", null);
    }

    @Test
    public void assignAndResolve() {
        store.createGroup("prod", "Production", null);
        store.assign("my-app.yml", "prod");
        assertEquals("prod", store.getFileGroupId("my-app.yml"));
        assertEquals("Production", store.getGroup("prod").getName());
    }

    @Test
    public void unassignByEmptyClearsMapping() {
        store.createGroup("prod", "Production", null);
        store.assign("my-app.yml", "prod");
        store.assign("my-app.yml", "");
        assertNull(store.getFileGroupId("my-app.yml"));
    }

    @Test
    public void deletingGroupClearsAssignments() {
        store.createGroup("prod", "Production", null);
        store.assign("a.yml", "prod");
        store.assign("b.yml", "prod");
        store.deleteGroup("prod");
        assertNull(store.getFileGroupId("a.yml"));
        assertNull(store.getFileGroupId("b.yml"));
        assertEquals(0, store.listGroups().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void assignToUnknownGroupRejected() {
        store.assign("a.yml", "ghost");
    }

    @Test(expected = IllegalArgumentException.class)
    public void assignInvalidFileIdRejected() {
        store.assign("../bad", null);
    }

    @Test
    public void persistsAcrossInstances() throws IOException {
        store.createGroup("prod", "Production", null);
        store.assign("a.yml", "prod");

        // Re-open the same rootDir with a fresh GroupStore.
        GroupStore reloaded = new GroupStore();
        reloaded.overrideRootForTest(tmpRoot);
        reloaded.init();

        List<Group> groups = reloaded.listGroups();
        assertEquals(1, groups.size());
        assertEquals("prod", groups.get(0).getId());
        Map<String, String> assignments = reloaded.snapshotAssignments();
        assertEquals("prod", assignments.get("a.yml"));
    }

    @Test
    public void corruptJsonYieldsEmptyState() throws IOException {
        File groupsFile = new File(tmpRoot, "groups.json");
        Files.writeString(groupsFile.toPath(), "{not json");

        GroupStore fresh = new GroupStore();
        fresh.overrideRootForTest(tmpRoot);
        fresh.init();
        assertEquals(0, fresh.listGroups().size());
        assertEquals(0, fresh.snapshotAssignments().size());
    }

    private void deleteRecursive(File f) throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        Files.deleteIfExists(f.toPath());
    }
}
