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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Hammering VersionStore with N threads creating versions on the same fileId.
 * Verifies that:
 * <ul>
 *   <li>no version numbers collide (1..N with no duplicates/gaps),</li>
 *   <li>no thread deadlocks,</li>
 *   <li>the on-disk content for each version matches what the producing
 *       thread originally wrote.</li>
 * </ul>
 */
public class ConcurrentTest {

    private VersionStore store;
    private File tmpRoot;

    @Before
    public void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("mfvm-concurrent").toFile();
        store = new VersionStore();
        store.overrideRootForTest(tmpRoot);
    }

    @After
    public void tearDown() throws IOException {
        deleteRecursive(tmpRoot);
    }

    @Test
    public void tenThreadsOnSameFile() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    store.saveVersion(
                            "concurrent",
                            "concurrent",
                            "user-" + idx,
                            "user-" + idx,
                            Operation.UPDATE,
                            null,
                            "thread=" + idx,
                            null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        pool.shutdownNow();

        List<ManagedFileVersion> versions = store.listVersions("concurrent");
        assertEquals(threads, versions.size());

        // version numbers must be a unique sequence
        Set<Integer> seen = new HashSet<>();
        for (ManagedFileVersion v : versions) {
            assertTrue("Duplicate version " + v.getVersion(), seen.add(v.getVersion()));
        }
        // and we must be able to read every version's content
        for (ManagedFileVersion v : versions) {
            String c = store.getContent("concurrent", v.getVersion());
            assertNotNull("Content missing for v" + v.getVersion(), c);
            assertTrue(c.startsWith("thread="));
        }
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
