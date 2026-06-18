package org.example.controller;

import org.example.service.PosGitReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
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
        return review("POST /api/pos-review/git-ref", str(body.get("repoPath")), str(body.get("ref")),
            str(body.get("baseRef")), str(body.get("requirement")));
    }

    @PostMapping(value = "/git-ref/markdown", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String reviewGitRefMarkdown(@RequestBody Map<String, Object> body) {
        return markdown(review("POST /api/pos-review/git-ref/markdown", str(body.get("repoPath")), str(body.get("ref")),
            str(body.get("baseRef")), str(body.get("requirement"))));
    }

    @GetMapping(value = "/git-ref/markdown", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String reviewGitRefMarkdownGet(@RequestParam(defaultValue = "") String repoPath,
                                          @RequestParam String ref,
                                          @RequestParam(defaultValue = "") String baseRef,
                                          @RequestParam(defaultValue = "POS code review only; provide feedback; do not modify code") String requirement) {
        return markdown(review("GET /api/pos-review/git-ref/markdown", repoPath, ref, baseRef, requirement));
    }

    private Map<String, Object> review(String endpoint, String repoPath, String ref, String baseRef, String requirement) {
        long t0 = System.currentTimeMillis();
        log.info("==> {} repoPath={} ref={} baseRef={}", endpoint, repoPath, ref, baseRef);
        Map<String, Object> result = service.review(repoPath, ref, baseRef, requirement);
        log.info("<== {} ref={} cost={}ms error={}", endpoint, ref, System.currentTimeMillis() - t0, result.get("error"));
        return result;
    }

    private static String markdown(Map<String, Object> result) {
        if (result.get("error") != null || str(result.get("review")).isBlank()) {
            if (result.get("error") == null) {
                result.put("error", "后端未生成 review 内容");
            }
            return formatError(result);
        }
        return String.valueOf(result.get("review"));
    }

    private static String formatError(Map<String, Object> result) {
        StringBuilder sb = new StringBuilder();
        sb.append("## POS Git Review 失败\n");
        sb.append("- ref: ").append(result.getOrDefault("ref", "")).append('\n');
        sb.append("- repo: ").append(result.getOrDefault("repoPath", "")).append('\n');
        sb.append("- 已自动 fetch 远程: ").append(Boolean.TRUE.equals(result.get("fetchedRemote")) ? "是" : "否").append('\n');
        sb.append("- error: ").append(result.getOrDefault("error", "")).append('\n');
        if (result.get("suggestion") != null) {
            sb.append("- 建议: ").append(result.get("suggestion")).append('\n');
        }
        if (result.get("recentRemoteCommits") != null) {
            sb.append("\n### 最近远程提交候选\n```\n").append(result.get("recentRemoteCommits")).append("\n```\n");
        }
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
