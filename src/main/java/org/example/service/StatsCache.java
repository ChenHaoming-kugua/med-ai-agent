package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class StatsCache {

    private static final Logger log = LoggerFactory.getLogger(StatsCache.class);
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public enum State { IDLE, COMPUTING, DONE, FAILED }

    private final Map<String, Long> cache = new ConcurrentHashMap<>();
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private volatile String lastRefreshTime = "未刷新";
    private volatile int totalWeeks = 0;
    private final AtomicInteger doneWeeks = new AtomicInteger(0);

    private final JdbcTemplate jdbc;

    private static final String GT =
        "5002,5004,5005,5007,5008,5009,5014,5096,5127,5129,6025,6079,"
        + "19003,19009,19010,19014,19015,29006,29007,29014,29015,29017,29018,29019,"
        + "29024,29028,29034,29064,29068,29069,29079,29082,29090,29098,29099,29102,"
        + "29103,29104,29123,29125,29127,29129,29130,29132,29137,29151,29164,29172,"
        + "29212,29222,29228,29232,29236,29237,29240,29265,29287,29362,29385,29386,"
        + "29420,29524,29559,29560,29589,29610,290002,380001,440002,440003,440004,"
        + "440010,440014,440016,440017,460001,600269,600279,600298";

    public StatsCache(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /* ====== 查询 ====== */

    public Object getOrCompute(String key, String start, String end) {
        State state = states.getOrDefault(key, State.IDLE);
        return switch (state) {
            case DONE -> cache.getOrDefault(key, -1L);
            case COMPUTING -> Map.of("status","computing","progress",doneWeeks.get()+"/"+totalWeeks+" 周");
            case FAILED -> Map.of("status","failed","msg","上次失败，POST /api/stats/refresh 重试");
            default -> {
                startAsync(key, start, end);
                yield Map.of("status","started","msg","预计算已启动("+totalWeeks+"周)，请稍后重试");
            }
        };
    }

    public String getLastRefreshTime() { return lastRefreshTime; }

    /* ====== 同步刷新 ====== */

    public void refresh(String start, String end) {
        compute("有销售门店数_医保店", start, end);
    }

    @Async
    public void startAsync(String key, String start, String end) {
        compute(key, start, end);
    }

    /* ====== 核心：按周 → 分批100门店查LST，每批只扫~40K DOC行 ====== */

    private void compute(String key, String start, String end) {
        states.put(key, State.COMPUTING);
        doneWeeks.set(0);
        List<String> weeks = weekList(start, end);
        totalWeeks = weeks.size();
        lastRefreshTime = "计算中...";
        log.info("[{}] 开始, {} 周", key, totalWeeks);

        Set<Long> allIds = new HashSet<>();
        for (int wi = 0; wi < weeks.size(); wi++) {
            String[] p = weeks.get(wi).split("\\|");
            try {
                // 第1步：拿本周所有门店ID（快，~2s）
                List<Long> pids = jdbc.queryForList(
                    "SELECT DISTINCT a.placepointid FROM gygdpos.gresa_sa_doc a "
                    + "WHERE a.usestatus=1 AND a.useday BETWEEN TO_DATE(?,'YYYY-MM-DD') AND TO_DATE(?,'YYYY-MM-DD') "
                    + "AND EXISTS(SELECT 1 FROM gygdpos.gpcs_placepoint p WHERE p.placepointid=a.placepointid AND p.placepointname NOT LIKE '%'||'测试'||'%')",
                    Long.class, p[0], p[1]);
                if (pids == null || pids.isEmpty()) { doneWeeks.incrementAndGet(); continue; }

                // 第2步：分批 100 个门店，在 LST 中检查是否有医保收款
                int batchSize = 100;
                for (int i = 0; i < pids.size(); i += batchSize) {
                    int be = Math.min(i + batchSize, pids.size());
                    List<Long> batch = pids.subList(i, be);
                    String inClause = batch.stream().map(Object::toString).reduce((a,b)->a+","+b).orElse("0");
                    List<Long> hit = jdbc.queryForList(
                        "SELECT DISTINCT a.placepointid FROM gygdpos.gresa_sa_doc a "
                        + "WHERE a.usestatus=1 AND a.placepointid IN(" + inClause + ") "
                        + "AND a.useday BETWEEN TO_DATE(?,'YYYY-MM-DD') AND TO_DATE(?,'YYYY-MM-DD') "
                        + "AND EXISTS(SELECT 1 FROM gygdpos.gresa_sa_lst b WHERE b.rsaid=a.rsaid AND b.gathertype IN("+GT+"))",
                        Long.class, p[0], p[1]);
                    if (hit != null) allIds.addAll(hit);
                }
                doneWeeks.incrementAndGet();
                log.info("[{}] {}/{} 周完成, 累计 {} 医保门店", key, doneWeeks.get(), totalWeeks, allIds.size());
            } catch (Exception e) {
                doneWeeks.incrementAndGet();
                log.error("[{}] 第{}周失败: {}", key, wi+1, e.getMessage());
                states.put(key, State.FAILED);
                lastRefreshTime = "失败: " + e.getMessage();
                return;
            }
        }
        cache.put(key, (long) allIds.size());
        states.put(key, State.DONE);
        lastRefreshTime = LocalDate.now().format(DF) + " " + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        log.info("[{}] 完成! 结果={}, 刷新时间={}", key, allIds.size(), lastRefreshTime);
    }

    private List<String> weekList(String start, String end) {
        List<String> w = new ArrayList<>();
        LocalDate s = LocalDate.parse(start, DF), e = LocalDate.parse(end, DF);
        while (!s.isAfter(e)) {
            LocalDate we = s.plusDays(6);
            if (we.isAfter(e)) we = e;
            w.add(s.format(DF) + "|" + we.format(DF));
            s = s.plusDays(7);
        }
        return w;
    }
}
