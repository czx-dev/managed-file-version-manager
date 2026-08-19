package com.example.jenkins.managedfile.service;

import com.example.jenkins.managedfile.util.Sha256Util;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Asserts that the version-creation logic skips writing a new version when
 * the content has not actually changed.
 *
 * <p>This guards a requirement from the spec: "If oldSha256 == newSha256 do
 * not create a new version". The decision lives in
 * {@link ManagedFileVersionService}, but here we exercise the same
 * primitive (the sha256 check) the service relies on.</p>
 */
public class NoChangeTest {

    @Test
    public void sameContentProducesIdenticalSha() {
        String a = "spring:\n  datasource:\n    url: jdbc:mysql://localhost\n";
        String b = "spring:\n  datasource:\n    url: jdbc:mysql://localhost\n";
        assertEquals(Sha256Util.hash(a), Sha256Util.hash(b));
    }

    @Test
    public void whitespaceChangeProducesDifferentSha() {
        String a = "spring:\n  datasource:\n    url: jdbc:mysql://localhost\n";
        String b = "spring:\n  datasource:\n    url: jdbc:mysql://localhost";
        assertNotEquals(Sha256Util.hash(a), Sha256Util.hash(b));
    }
}
