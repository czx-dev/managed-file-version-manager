package com.example.jenkins.managedfile.store;

import com.example.jenkins.managedfile.model.ManagedFileVersion;
import com.example.jenkins.managedfile.model.Operation;
import com.example.jenkins.managedfile.util.Sha256Util;
import jenkins.model.Jenkins;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class VersionStore {

    private static final Logger LOGGER = Logger.getLogger(VersionStore.class.getName());
    public static final Pattern FILE_ID_PATTERN = Pattern.compile("[A-Za-z0-9._\\-]+");
    private static final VersionStore INSTANCE = new VersionStore();

    public static VersionStore getInstance() { return INSTANCE; }

    private final ReentrantLock startupLock = new ReentrantLock();
    private final ConcurrentHashMap<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();
    private File _rootDir;

    public VersionStore() {}

    public void overrideRootForTest(File root) { this._rootDir = root; }

    public void init() {
        startupLock.lock();
        try {
            if (_rootDir == null) {
                File jenkinsHome = Jenkins.get().getRootDir();
                File dir = new File(jenkinsHome, "managed-file-version-manager");
                if (!dir.exists() && !dir.mkdirs()) {
                    LOGGER.warning("Cannot create managed-file-version-manager dir at " + dir);
                }
                _rootDir = dir;
                LOGGER.info("Managed File Version Manager storage root: " + _rootDir.getAbsolutePath());
            }
        } finally {
            startupLock.unlock();
        }
    }

    private ReentrantLock lockFor(String fileId) {
        return fileLocks.computeIfAbsent(fileId, k -> new ReentrantLock());
    }

    public static void validateFileId(String fileId) {
        if (fileId == null || fileId.isEmpty() || !FILE_ID_PATTERN.matcher(fileId).matches()) {
            throw new IllegalArgumentException("Invalid fileId: " + fileId);
        }
    }

    private File rootDir() {
        if (_rootDir == null) throw new IllegalStateException("VersionStore.init() has not been called");
        return _rootDir;
    }

    public int nextVersionNumber(String fileId) {
        validateFileId(fileId);
        File dir = new File(rootDir(), fileId);
        if (!dir.exists()) return 1;
        File[] children = dir.listFiles();
        if (children == null) return 1;
        int max = 0;
        for (File f : children) {
            if (f.isDirectory()) {
                try {
                    int v = Integer.parseInt(f.getName());
                    if (v > max) max = v;
                } catch (NumberFormatException ignore) {}
            }
        }
        return max + 1;
    }

    public ManagedFileVersion saveVersion(String fileId, String fileName, String user, String userId,
                                         Operation operation, Integer rollbackFromVersion,
                                         String content, String comment) {
        validateFileId(fileId);
        ReentrantLock lock = lockFor(fileId);
        lock.lock();
        try {
            int version = nextVersionNumber(fileId);
            String sha = Sha256Util.hash(content);
            Instant ts = Instant.now();
            ManagedFileVersion v = new ManagedFileVersion(
                    version, fileId, fileName, user, userId, ts,
                    operation, rollbackFromVersion, sha, comment);

            File dir = new File(new File(rootDir(), fileId), Integer.toString(version));
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create version dir: " + dir);
            writeContent(dir, content);
            writeMetadata(dir, v);
            LOGGER.log(Level.INFO,
                    "Managed file version created: fileId={0}, version={1}, user={2}, operation={3}",
                    new Object[]{fileId, version, userId, operation});
            return v;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to persist version for " + fileId, e);
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    public List<ManagedFileVersion> listVersions(String fileId) {
        validateFileId(fileId);
        File dir = new File(rootDir(), fileId);
        if (!dir.exists()) return Collections.emptyList();
        File[] children = dir.listFiles();
        if (children == null) return Collections.emptyList();
        List<ManagedFileVersion> versions = new ArrayList<>();
        for (File f : children) {
            if (f.isDirectory()) {
                ManagedFileVersion v = readMetadata(f);
                if (v != null) versions.add(v);
            }
        }
        versions.sort((a, b) -> Integer.compare(b.getVersion(), a.getVersion()));
        return versions;
    }

    public ManagedFileVersion getVersion(String fileId, int version) {
        validateFileId(fileId);
        if (version <= 0) return null;
        File dir = new File(new File(rootDir(), fileId), Integer.toString(version));
        if (!dir.exists()) return null;
        return readMetadata(dir);
    }

    public String getContent(String fileId, int version) {
        validateFileId(fileId);
        File dir = new File(new File(rootDir(), fileId), Integer.toString(version));
        File content = new File(dir, "content");
        if (!content.exists()) return null;
        try {
            return Files.readString(Paths.get(content.toURI()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to read content for " + fileId + " v" + version, e);
            return null;
        }
    }

    private void writeContent(File versionDir, String content) throws IOException {
        Files.writeString(Paths.get(new File(versionDir, "content").toURI()),
                content == null ? "" : content, StandardCharsets.UTF_8);
    }

    private void writeMetadata(File versionDir, ManagedFileVersion v) {
        File metaFile = new File(versionDir, "metadata.xml");
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.setOutputProperty(OutputKeys.VERSION, "1.0");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.transform(new DOMSource(toDom(v)), new StreamResult(metaFile));
        } catch (TransformerException e) {
            throw new RuntimeException("Failed to write metadata.xml", e);
        }
    }

    private ManagedFileVersion readMetadata(File versionDir) {
        File metaFile = new File(versionDir, "metadata.xml");
        if (!metaFile.exists()) return null;
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(metaFile);
            return fromDom(doc.getDocumentElement());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Corrupt metadata in " + versionDir + " - skipping", e);
            return null;
        }
    }

    private Document toDom(ManagedFileVersion v) {
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.newDocument();
            doc.setXmlVersion("1.0");
            Element root = doc.createElement("version");
            doc.appendChild(root);
            addElement(doc, root, "version", String.valueOf(v.getVersion()));
            addElement(doc, root, "fileId", v.getFileId());
            addElement(doc, root, "fileName", v.getFileName());
            addElement(doc, root, "user", v.getUser());
            addElement(doc, root, "userId", v.getUserId());
            addElement(doc, root, "timestamp", v.getTimestamp() != null ? v.getTimestamp().toString() : "");
            addElement(doc, root, "operation", v.getOperation() != null ? v.getOperation().name() : "");
            addElement(doc, root, "rollbackFromVersion",
                    v.getRollbackFromVersion() != null ? String.valueOf(v.getRollbackFromVersion()) : "");
            addElement(doc, root, "sha256", v.getSha256());
            addElement(doc, root, "comment", v.getComment() != null ? v.getComment() : "");
            return doc;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void addElement(Document doc, Element parent, String name, String text) {
        Element el = doc.createElement(name);
        el.setTextContent(text != null ? text : "");
        parent.appendChild(el);
    }

    private ManagedFileVersion fromDom(Element root) {
        return new ManagedFileVersion(
                intVal(root, "version"),
                textVal(root, "fileId"),
                textVal(root, "fileName"),
                textVal(root, "user"),
                textVal(root, "userId"),
                parseInstant(textVal(root, "timestamp")),
                parseOperation(textVal(root, "operation")),
                parseInteger(textVal(root, "rollbackFromVersion")),
                textVal(root, "sha256"),
                textVal(root, "comment")
        );
    }

    private String textVal(Element parent, String child) {
        NodeList list = parent.getElementsByTagName(child);
        if (list.getLength() == 0) return null;
        org.w3c.dom.Node node = list.item(0).getFirstChild();
        return node != null ? node.getNodeValue() : null;
    }

    private int intVal(Element parent, String child) {
        String s = textVal(parent, child);
        try { return s != null && !s.isEmpty() ? Integer.parseInt(s) : 0; }
        catch (NumberFormatException e) { return 0; }
    }

    private Integer parseInteger(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return null; }
    }

    private Instant parseInstant(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Instant.parse(s); }
        catch (Exception e) { return null; }
    }

    private Operation parseOperation(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Operation.valueOf(s); }
        catch (IllegalArgumentException e) { return null; }
    }
}
