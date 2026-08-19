package com.example.jenkins.managedfile.store;

import com.example.jenkins.managedfile.model.ManagedFileVersion;
import com.example.jenkins.managedfile.model.Operation;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

public class VersionStoreTest {

    private VersionStore store;
    private File tmpRoot;

    @Before
    public void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("mfvm-test").toFile();
        store = new VersionStore();
        store.overrideRootForTest(tmpRoot);
    }

    @After
    public void tearDown() throws IOException {
        deleteRecursive(tmpRoot);
    }

    @Test
    public void createAndReadVersion() {
        ManagedFileVersion v = store.saveVersion(
                "app.yml", "app.yml", "alice", "alice",
                Operation.CREATE, null, "hello\n", null);
        assertEquals(1, v.getVersion());
        assertEquals("hello\n", store.getContent("app.yml", 1));
        assertNotNull(store.getVersion("app.yml", 1));
    }

    @Test
    public void versionsAreReturnedNewestFirst() {
        store.saveVersion("f", "f", "u", "u", Operation.CREATE, null, "v1", null);
        store.saveVersion("f", "f", "u", "u", Operation.UPDATE, null, "v2", null);
        store.saveVersion("f", "f", "u", "u", Operation.UPDATE, null, "v3", null);

        List<ManagedFileVersion> versions = store.listVersions("f");
        assertEquals(3, versions.size());
        assertEquals(3, versions.get(0).getVersion());
        assertEquals(1, versions.get(2).getVersion());
    }

    @Test
    public void nextVersionNumberResumesAfterRestart() {
        store.saveVersion("f", "f", "u", "u", Operation.CREATE, null, "v1", null);
        store.saveVersion("f", "f", "u", "u", Operation.UPDATE, null, "v2", null);

        // Simulate restart: new VersionStore reads from disk.
        VersionStore second = new VersionStore();
        second.overrideRootForTest(tmpRoot);
        assertEquals(3, second.nextVersionNumber("f"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidFileIdIsRejected() {
        store.saveVersion("../etc/passwd", "x", "u", "u", Operation.CREATE, null, "x", null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyFileIdIsRejected() {
        store.saveVersion("", "x", "u", "u", Operation.CREATE, null, "x", null);
    }

    @Test
    public void missingVersionReturnsNull() {
        assertNull(store.getVersion("does-not-exist", 1));
        assertNull(store.getContent("does-not-exist", 1));
    }

    @Test
    public void corruptMetadataIsSkipped() throws IOException {
        // Save one good version
        store.saveVersion("f", "f", "u", "u", Operation.CREATE, null, "good", null);

        // Manually create a corrupt directory
        File versionDir = new File(new File(tmpRoot, "f"), "99");
        versionDir.mkdirs();
        Files.write(new File(versionDir, "metadata.xml").toPath(),
                "<not-xml".getBytes());

        List<ManagedFileVersion> versions = store.listVersions("f");
        // The corrupt entry must be skipped, not crash
        assertEquals(1, versions.size());
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
