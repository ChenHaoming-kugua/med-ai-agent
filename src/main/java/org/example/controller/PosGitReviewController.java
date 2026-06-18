package org.example.controller;

import org.example.service.PosGitReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/pos-review")
public class PosGitReviewController {

    private static final Logger log = LoggerFactory.getLogger(PosGitReviewController.class);
    private final PosGitReviewService service;

    public PosGitReviewController(PosGitReviewService service) { this.service = service; }

    @PostMapping("/git-ref")
    public Map<String, Object> reviewGitRef(@RequestBody Map<String, Object> body) {
        long t0 = System.currentTimeMillis();
        String repoPath = str(body.get("repoPath"));
        String ref = str(body.get("ref"));
        String baseRef = str(body.get("baseRef"));
        String requirement = str(body.get("requirement"));
        log.info("==> POST /api/pos-review/git-ref repoPath={} ref={} baseRef={}", repoPath, ref, baseRef);
        Map<String, Object> result = service.review(repoPath, ref, baseRef, requirement);
        log.info("<== POST /api/pos-review/git-ref ref={} cost={}ms error={}",
            ref, System.currentTimeMillis() - t0, result.get("error"));
        return result;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
