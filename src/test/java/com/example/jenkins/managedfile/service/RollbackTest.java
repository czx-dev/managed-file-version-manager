package com.example.jenkins.managedfile.service;

import com.example.jenkins.managedfile.model.ManagedFileVersion;
import com.example.jenkins.managedfile.model.Operation;
import com.example.jenkins.managedfile.store.VersionStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Pure disk-level rollback simulation. Does not depend on the listener or
 * on {@link org.jenkinsci.plugins.configfiles.GlobalConfigFiles}.
 */
public class RollbackTest {

    private VersionStore store;
    private File tmpRoot;

    @Before
    public void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("mfvm-rollback").toFile();
        store = new VersionStore();
        store.overrideRootForTest(tmpRoot);
    }

    @After
    public void tearDown() throws IOException {
        deleteRecursive(tmpRoot);
    }

    @Test
    public void rollbackAppendsNewVersion() {
        ManagedFileVersion v1 = store.saveVersion("f", "f", "alice", "alice", Operation.CREATE, null, "V1 content", null);
        store.saveVersion("f", "f", "alice", "alice", Operation.UPDATE, null, "V2 content", null);
        store.saveVersion("f", "f", "alice", "alice", Operation.UPDATE, null, "V3 content", null);

        // simulate "rollback to V1" - this is exactly what the
        // ManagedFileRollbackAction does via VersionStore + ROLLBACK operation.
        ManagedFileVersion rolledBack = store.saveVersion(
                "f", "f", "bob", "bob",
                Operation.ROLLBACK, 1, "V1 content", "rollback to V1");

        assertEquals(4, rolledBack.getVersion());
        assertEquals(1, rolledBack.getRollbackFromVersion().intValue());
        assertEquals(Operation.ROLLBACK, rolledBack.getOperation());
        assertEquals(v1.getSha256(), rolledBack.getSha256());

        // history contains V1..V4, V1 itself is preserved
        List<ManagedFileVersion> history = store.listVersions("f");
        assertEquals(4, history.size());
        assertTrue(history.stream().anyMatch(v -> v.getVersion() == 1));
    }

    @Test
    public void rollbackNeverOverwritesHistoricalContent() {
        store.saveVersion("f", "f", "u", "u", Operation.CREATE, null, "A", null);
        store.saveVersion("f", "f", "u", "u", Operation.UPDATE, null, "B", null);

        // rollback to V1 should not change the content of V2
        store.saveVersion("f", "f", "u", "u", Operation.ROLLBACK, 1, "A", null);

        assertEquals("A", store.getContent("f", 1));
        assertEquals("B", store.getContent("f", 2));
        assertEquals("A", store.getContent("f", 3));
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
