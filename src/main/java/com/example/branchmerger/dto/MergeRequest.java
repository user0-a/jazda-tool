package com.example.branchmerger.dto;

import jakarta.validation.constraints.NotBlank;

public class MergeRequest {

    @NotBlank(message = "branch must not be blank")
    private String branch;

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }
}
