package com.example.branchmerger.controller;

import com.example.branchmerger.dto.MergeRequest;
import com.example.branchmerger.dto.MergeResult;
import com.example.branchmerger.service.GitMergeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MergeController {

    private final GitMergeService gitMergeService;

    public MergeController(GitMergeService gitMergeService) {
        this.gitMergeService = gitMergeService;
    }

    /**
     * POST /api/merge
     * Body: { "branch": "feature/login" }
     */
    @PostMapping("/merge")
    public ResponseEntity<MergeResult> merge(@Valid @RequestBody MergeRequest request) {
        MergeResult result = gitMergeService.mergeMainInto(request.getBranch().trim());

        HttpStatus status = switch (result.getStatus()) {
            case MERGED_AND_PUSHED, ALREADY_UP_TO_DATE -> HttpStatus.OK;
            case CONFLICTS -> HttpStatus.CONFLICT;
            case FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(result);
    }
}
