package com.example.branchmerger.dto;

import jakarta.validation.constraints.NotBlank;

public class MergeRequest {

    @NotBlank(message = "branch must not be blank")
    private String branch;

    /** Optional: when true, bump the version marker to main's version + 1 on its last segment. */
    private boolean currentVersionUpgrade = false;

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public boolean isCurrentVersionUpgrade() {
        return currentVersionUpgrade;
    }

    public void setCurrentVersionUpgrade(boolean currentVersionUpgrade) {
        this.currentVersionUpgrade = currentVersionUpgrade;
    }
}
