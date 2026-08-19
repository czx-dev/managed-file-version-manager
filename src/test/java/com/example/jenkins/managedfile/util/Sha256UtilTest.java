package com.example.jenkins.managedfile.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class Sha256UtilTest {

    @Test
    public void sameContentProducesSameHash() {
        String a = Sha256Util.hash("hello world");
        String b = Sha256Util.hash("hello world");
        assertEquals(a, b);
    }

    @Test
    public void differentContentProducesDifferentHash() {
        String a = Sha256Util.hash("hello world");
        String b = Sha256Util.hash("hello WORLD");
        assertNotEquals(a, b);
    }

    @Test
    public void nullContentIsTreatedAsEmpty() {
        assertEquals(Sha256Util.hash(""), Sha256Util.hash(null));
    }

    @Test
    public void knownVector() {
        // SHA-256 of "abc"
        String expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assertEquals(expected, Sha256Util.hash("abc"));
    }
}
