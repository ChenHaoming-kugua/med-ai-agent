package org.example.service;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PosGitReviewService {

    private static final Logger log = LoggerFactory.getLogger(PosGitReviewService.class);
    private static final String DEFAULT_REPO = "C:/Users/haoming/IdeaProjects/gygd_pos";
    private static final int MAX_DIFF_CHARS = 60_000;

    private static final String SYSTEM_PROMPT = """
        你是国大药房 gygd_pos 项目的资深代码 reviewer。
        你只根据用户提供的 git diff / commit 信息做 review，不臆造未展示代码。
        审查重点：
        1. 是否符合 POS 项目最小侵入原则，优先复用现有实现。
        2. SQL / MyBatis / Mapper 是否可能引入性能问题、N+1、全表扫描、日期条件错误、空值错误。
        3. 是否影响现有业务路径、定时任务、接口兼容性、UAT 数据。
        4. 是否有明显 bug：空指针、类型转换、金额/数量精度、事务边界、并发风险。
        5. 是否有安全风险：SQL 注入、越权、日志泄密。
        输出中文 Markdown，不要只输出思考过程，不要把最终报告放在 <think> 标签里。必须包含：
        - 总体结论：可合并 / 建议修改后合并 / 不建议合并
        - 高风险问题（没有就写“无”）
        - 中低风险建议
        - 建议修改点（尽量给出文件/函数/代码方向）
        - UAT 自测建议
        """;

    private final ChatLanguageModel chatLanguageModel;

    public PosGitReviewService(@Qualifier("posGitReviewChatLanguageModel") ChatLanguageModel chatLanguageModel) {
        this.chatLanguageModel = chatLanguageModel;
    }

    public Map<String, Object> review(String repoPath, String ref, String baseRef, String requirement) {
        long t0 = System.currentTimeMillis();
        String repo = blank(repoPath) ? DEFAULT_REPO : repoPath.trim();
        String targetRef = blank(ref) ? "HEAD" : ref.trim();
        String base = blank(baseRef) ? "" : baseRef.trim();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoPath", repo);
        result.put("ref", targetRef);
        result.put("baseRef", base);

        try {
            if (!Files.isDirectory(Path.of(repo))) {
                result.put("error", "repoPath 目录不存在: " + repo);
                return result;
            }

            GitResult inside = runGit(repo, List.of("rev-parse", "--is-inside-work-tree"));
            if (inside.exitCode != 0 || !inside.stdout.trim().equals("true")) {
                result.put("error", "repoPath 不是 git 仓库: " + repo);
                return result;
            }

            GitResult targetType = ensureRefAvailable(repo, targetRef, result);
            if (targetType.exitCode != 0) {
                result.put("error", "git ref 不存在，已自动 fetch 远程后仍未找到: " + targetRef);
                result.put("detail", targetType.stderr);
                result.put("suggestion", "请确认版本号是否属于当前 gygd_pos 远程仓库；也可参考 recentRemoteCommits 中的最近远程提交");
                result.put("recentRemoteCommits", recentRemoteCommits(repo));
                return result;
            }

            String showStat = runGit(repo, List.of("show", "--stat", "--oneline", "--no-renames", targetRef)).stdout;
            String diff;
            String diffRange;
            if (base.isEmpty()) {
                diffRange = targetRef + "^!";
                diff = runGit(repo, List.of("show", "--format=fuller", "--no-renames", "--find-renames=40%", "--unified=80", targetRef)).stdout;
            } else {
                diffRange = base + "..." + targetRef;
                diff = runGit(repo, List.of("diff", "--no-renames", "--find-renames=40%", "--unified=80", diffRange)).stdout;
            }

            boolean truncated = diff.length() > MAX_DIFF_CHARS;
            String reviewInput = diff.length() > MAX_DIFF_CHARS ? diff.substring(0, MAX_DIFF_CHARS) : diff;
            log.info("POS review input ready ref={} diffRange={} statChars={} diffChars={} truncated={}",
                targetRef, diffRange, showStat.length(), diff.length(), truncated);
            String report = callLlm(showStat, diffRange, reviewInput, truncated, requirement);
            if (blank(report)) {
                result.put("error", "LLM 返回空 review，请检查模型响应或稍后重试");
                result.put("diffRange", diffRange);
                result.put("stat", showStat);
                result.put("truncated", truncated);
                result.put("costMs", System.currentTimeMillis() - t0);
                log.warn("POS review LLM returned blank ref={} diffRange={} diffChars={}", targetRef, diffRange, diff.length());
                return result;
            }
            log.info("POS review LLM returned ref={} reviewChars={}", targetRef, report.length());

            result.put("diffRange", diffRange);
            result.put("stat", showStat);
            result.put("truncated", truncated);
            result.put("review", report);
            result.put("costMs", System.currentTimeMillis() - t0);
            return result;
        } catch (Exception e) {
            log.error("POS git review failed repo={} ref={}", repo, targetRef, e);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            result.put("costMs", System.currentTimeMillis() - t0);
            return result;
        }
    }

    private GitResult ensureRefAvailable(String repo, String targetRef, Map<String, Object> result) throws Exception {
        GitResult targetType = runGit(repo, List.of("cat-file", "-t", targetRef));
        if (targetType.exitCode == 0) {
            result.put("fetchedRemote", false);
            return targetType;
        }
        log.info("POS ref {} 本地不存在，尝试 git fetch --all --prune", targetRef);
        GitResult fetch = runGit(repo, List.of("fetch", "--all", "--prune"), Duration.ofMinutes(3));
        result.put("fetchedRemote", true);
        result.put("fetchOutput", (fetch.stdout + fetch.stderr).trim());
        return runGit(repo, List.of("cat-file", "-t", targetRef));
    }

    private String recentRemoteCommits(String repo) throws Exception {
        GitResult r = runGit(repo, List.of("log", "--all", "--remotes", "--oneline", "--decorate", "--max-count=30"));
        return r.stdout.trim();
    }

    private String callLlm(String stat, String diffRange, String diff, boolean truncated, String requirement) {
        String userPrompt = """
            请 review 以下 POS 代码变更。

            ## 需求/背景
            %s

            ## Git 范围
            %s

            ## Stat
            ```
            %s
            ```

            ## Diff%s
            ```diff
            %s
            ```

            请直接输出最终中文 Markdown review 报告，不要只输出 <think> 思考过程。
            """.formatted(blank(requirement) ? "用户未提供额外需求背景。" : requirement,
            diffRange, stat, truncated ? "（已截断，请在报告中提示可能需要人工补充查看完整 diff）" : "", diff);

        ChatRequest request = ChatRequest.builder()
            .messages(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userPrompt))
            .build();
        ChatResponse response = chatLanguageModel.chat(request);
        String text = response.aiMessage().text();
        String cleaned = cleanLlmText(text);
        log.info("POS review LLM rawChars={} cleanedChars={}", text == null ? 0 : text.length(), cleaned.length());
        return cleaned;
    }

    private String cleanLlmText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = text.replaceAll("(?s)<think>.*?</think>", "").trim();
        if (!cleaned.isBlank()) {
            return cleaned;
        }
        String think = text.replaceAll("(?s)^.*?<think>", "").replaceAll("(?s)</think>.*$", "").trim();
        if (!think.isBlank()) {
            return "## POS Git Review 报告\n\n" + think;
        }
        return text.trim();
    }

    private GitResult runGit(String repo, List<String> args) throws Exception {
        return runGit(repo, args, Duration.ofSeconds(30));
    }

    private GitResult runGit(String repo, List<String> args, Duration timeout) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        pb.command(buildCommand(args));
        pb.directory(Path.of(repo).toFile());
        Process p = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        p.getInputStream().transferTo(out);
        p.getErrorStream().transferTo(err);
        boolean finished = p.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("git command timeout: " + String.join(" ", args));
        }
        return new GitResult(p.exitValue(), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private List<String> buildCommand(List<String> args) {
        java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.addAll(args);
        return cmd;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private record GitResult(int exitCode, String stdout, String stderr) {}
}
