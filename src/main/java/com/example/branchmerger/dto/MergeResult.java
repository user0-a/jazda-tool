package com.example.branchmerger.dto;

import java.util.List;

public class MergeResult {

    public enum Status {
        /** Branch already contained everything from main; nothing to do. */
        ALREADY_UP_TO_DATE,
        /** Merge succeeded and the branch was pushed to the remote. */
        MERGED_AND_PUSHED,
        /** Merge produced conflicts; left for the (future) conflict resolver. */
        CONFLICTS,
        /** Something went wrong. See message. */
        FAILED
    }

    private Status status;
    private String branch;
    private String message;
    private List<String> conflictingFiles;
    private String mergeCommitId;

    public MergeResult() {
    }

    public static MergeResult of(Status status, String branch, String message) {
        MergeResult r = new MergeResult();
        r.status = status;
        r.branch = branch;
        r.message = message;
        return r;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<String> getConflictingFiles() {
        return conflictingFiles;
    }

    public void setConflictingFiles(List<String> conflictingFiles) {
        this.conflictingFiles = conflictingFiles;
    }

    public String getMergeCommitId() {
        return mergeCommitId;
    }

    public void setMergeCommitId(String mergeCommitId) {
        this.mergeCommitId = mergeCommitId;
    }
}
