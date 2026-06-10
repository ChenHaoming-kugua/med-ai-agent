package org.example.controller;

import org.example.service.StatsCache;
import org.example.service.StatsService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;
    private final StatsCache cache;

    public StatsController(StatsService statsService, StatsCache cache) {
        this.statsService = statsService;
        this.cache = cache;
    }

    /** 获取全部统计指标 */
    @GetMapping
    public Map<String, Object> getAllStats(
            @RequestParam(defaultValue = "2026-01-01") String startDate,
            @RequestParam(defaultValue = "2026-06-09") String endDate) {
        return statsService.getAllStats(startDate, endDate);
    }

    /** 获取单个指标。医保等慢查询指标首次返回进度，后续返回缓存结果 */
    @GetMapping("/{metric}")
    public Map<String, Object> getMetric(
            @PathVariable String metric,
            @RequestParam(defaultValue = "2026-01-01") String startDate,
            @RequestParam(defaultValue = "2026-06-09") String endDate) {
        Object val = statsService.getMetric(metric, startDate, endDate);
        if (val instanceof Map<?,?> m) {
            // 预计算状态信息，直接透传
            @SuppressWarnings("unchecked")
            Map<String, Object> status = (Map<String, Object>) m;
            Map<String, Object> r = new java.util.LinkedHashMap<>(status);
            r.put("metric", metric);
            r.put("timeRange", startDate + " ~ " + endDate);
            return r;
        }
        return Map.of("metric", metric,
            "timeRange", startDate + " ~ " + endDate,
            "value", val);
    }

    /** 列出所有可用指标名 */
    @GetMapping("/metrics")
    public List<String> listMetrics() {
        return StatsService.metricKeys();
    }

    /** 手动触发预计算（医保等慢查询指标） */
    @PostMapping("/refresh")
    public Map<String, String> refresh(
            @RequestParam(defaultValue = "2026-01-01") String startDate,
            @RequestParam(defaultValue = "2026-06-09") String endDate) {
        cache.refresh(startDate, endDate);
        return Map.of("status", "ok", "lastRefresh", cache.getLastRefreshTime());
    }

    /** 查看缓存状态 */
    @GetMapping("/cache-status")
    public Map<String, Object> cacheStatus() {
        return Map.of("lastRefresh", cache.getLastRefreshTime());
    }

    /** 执行自定义 SQL（仅 SELECT） */
    @PostMapping("/query")
    public List<Map<String, Object>> customQuery(@RequestBody Map<String, String> body) {
        String sql = body.get("sql");
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("sql is required");
        if (!sql.trim().toUpperCase().startsWith("SELECT")) throw new IllegalArgumentException("Only SELECT allowed");
        return statsService.executeQuery(sql);
    }
}
