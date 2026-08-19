package com.example.jenkins.managedfile.model;

import java.time.Instant;

/**
 * Immutable metadata describing a single version of a managed file.
 */
public class ManagedFileVersion {

    private final int version;
    private final String fileId;
    private final String fileName;
    private final String user;
    private final String userId;
    private final Instant timestamp;
    private final Operation operation;
    private final Integer rollbackFromVersion;
    private final String sha256;
    private final String comment;

    public ManagedFileVersion(int version,
                              String fileId,
                              String fileName,
                              String user,
                              String userId,
                              Instant timestamp,
                              Operation operation,
                              Integer rollbackFromVersion,
                              String sha256,
                              String comment) {
        this.version = version;
        this.fileId = fileId;
        this.fileName = fileName;
        this.user = user;
        this.userId = userId;
        this.timestamp = timestamp;
        this.operation = operation;
        this.rollbackFromVersion = rollbackFromVersion;
        this.sha256 = sha256;
        this.comment = comment;
    }

    public int getVersion() {
        return version;
    }

    public String getFileId() {
        return fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getUser() {
        return user;
    }

    public String getUserId() {
        return userId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Operation getOperation() {
        return operation;
    }

    public Integer getRollbackFromVersion() {
        return rollbackFromVersion;
    }

    public String getSha256() {
        return sha256;
    }

    public String getComment() {
        return comment;
    }

    public boolean isRollback() {
        return operation == Operation.ROLLBACK;
    }
}
