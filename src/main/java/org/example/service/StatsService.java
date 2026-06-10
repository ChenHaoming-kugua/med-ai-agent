package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final JdbcTemplate jdbc;
    private final StatsCache cache;

    public StatsService(JdbcTemplate jdbc, StatsCache cache) { this.jdbc = jdbc; this.cache = cache; }

    private static final String HQ_EXISTS =
        "AND EXISTS(SELECT 1 FROM gygdpos.gpcs_placepoint p"
        + " WHERE p.placepointid=a.placepointid AND p.placepointname NOT LIKE '%'||'测试'||'%') ";

    /* ========== 公共 ========== */

    public Map<String, Object> getAllStats(String start, String end) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("timeRange", start + " ~ " + end);
        for (String key : metricKeys()) stats.put(key, getMetric(key, start, end));
        return stats;
    }

    public Object getMetric(String key, String start, String end) {
        return switch (key) {
            case "有销售门店数"          -> countDist("a.placepointid", "", "", start, end);
            case "有销售门店数_医保店"     -> cache.getOrCompute("有销售门店数_医保店", start, end);
            case "有销售门店数_b2c_o2o"   -> countB2cO2o(start, end);
            case "含医保收款方式单据数"    -> q("SELECT SUM(c.num) FROM gygdpos.test_cs_02 c "
                + "WHERE c.gathertype IN(SELECT gathertypeid FROM gygdpos.gather_int_mapping)");
            case "当前有库存的门店数"      -> q("SELECT COUNT(DISTINCT a.storageid) FROM gygdpos.bms_st_qty_lst a "
                + "WHERE a.goodsqty>0 AND EXISTS(SELECT 1 FROM gygdpos.gpcs_placepoint p WHERE p.storageid=a.storageid AND p.placepointname NOT LIKE '%'||'测试'||'%')");
            case "销售商品SKU数"         -> countDist("d.goodsid",
                "JOIN gygdpos.gresa_sa_dtl d ON a.rsaid=d.rsaid ", "", start, end); // DTL 用 JOIN 尚可，RSAID 是一对多但 GOODSID 去重后不算太大
            case "三方收款销售单数_不含医保" -> q("SELECT SUM(c.num) FROM gygdpos.test_cs_02 c "
                + "WHERE c.gathertype IN(SELECT gathertypeid FROM gygdpos.gather_int_mapping)");
            case "销售追溯码采集记录数"    -> sumWeeks(start, end, "SALE");
            case "销退追溯码采集记录数"    -> sumWeeks(start, end, "SALE_RETURN");
            case "配收追溯码采集记录数"    -> sumWeeks(start, end, "DIST_RECEIVE");
            case "配退追溯码采集记录数"    -> sumWeeks(start, end, "DIST_RETURN");
            case "其他追溯码采集记录数"    -> sumWeeksOther(start, end);
            case "全部追溯码采集记录数"    -> sumWeeksAll(start, end);
            case "码上放心日均上传量_6月"   -> q("SELECT ROUND(COUNT(*)/EXTRACT(DAY FROM LAST_DAY(SYSDATE))) "
                + "FROM ALI_HEALTH_PURCH_ECODE_D "
                + "WHERE create_time>=TO_DATE('2026-06-01','YYYY-MM-DD') AND create_time<TO_DATE('2026-06-10','YYYY-MM-DD')");
            case "三方处方数量"           -> q("SELECT COUNT(*) FROM gygdpos.ext_prescription_doc "
                + "WHERE credate BETWEEN TO_DATE(?,'YYYY-MM-DD') AND TO_DATE(?,'YYYY-MM-DD') AND usestatus=1", start, end);
            case "促销策略数量"           -> q("SELECT COUNT(DISTINCT p.placepointid) FROM gygdpos.promrule_placepoint_master p "
                + "WHERE EXISTS(SELECT 1 FROM gygdpos.gpcs_placepoint pp WHERE pp.placepointid=p.placepointid AND pp.placepointname NOT LIKE '%'||'测试'||'%')");
            case "含返利促销策略数量"      -> q("SELECT COUNT(DISTINCT p.placepointid) FROM gygdpos.promrule_placepoint_master p "
                + "JOIN gygdpos.newprom_rule r ON p.ruleid=r.ruleid WHERE r.has_rebate='Y' "
                + "AND EXISTS(SELECT 1 FROM gygdpos.gpcs_placepoint pp WHERE pp.placepointid=p.placepointid AND pp.placepointname NOT LIKE '%'||'测试'||'%')");
            default -> -1L;
        };
    }

    public static List<String> metricKeys() {
        return List.of("有销售门店数","有销售门店数_医保店","有销售门店数_b2c_o2o",
            "含医保收款方式单据数","当前有库存的门店数","销售商品SKU数",
            "三方收款销售单数_不含医保","销售追溯码采集记录数","销退追溯码采集记录数",
            "配收追溯码采集记录数","配退追溯码采集记录数","其他追溯码采集记录数",
            "全部追溯码采集记录数","码上放心日均上传量_6月",
            "b2b_o2o_库存推送OMS次数","b2b_o2o_库存每次推送OMS数量","b2b_o2o_接单量",
            "三方处方数量","CRM交互量","促销策略数量","含返利促销策略数量");
    }

    /* ========== 按周拆分：每种子查询 ~1.7s，23 周 ≈ 40s 出结果 ========== */

    private long countDist(String field, String extraJoin, String extraWhere, String start, String end) {
        List<String> weeks = weeks(start, end);
        if (weeks.size() <= 5) {
            // 范围小，直接一次查
            String[] p0 = weeks.get(0).split("\\|"), p1 = weeks.get(weeks.size()-1).split("\\|");
            return q("SELECT COUNT(DISTINCT " + field + ") FROM gygdpos.gresa_sa_doc a " + extraJoin
                + "WHERE a.usestatus=1 AND a.useday BETWEEN TO_DATE(?,'YYYY-MM-DD') AND TO_DATE(?,'YYYY-MM-DD') "
                + extraWhere + HQ_EXISTS, p0[0], p1[1]);
        }
        // 范围大，按周拆开查，Java Set 去重
        Set<Long> ids = new HashSet<>();
        for (String w : weeks) {
            String[] p = w.split("\\|");
            try {
                List<Long> list = jdbc.queryForList(
                    "SELECT DISTINCT " + field + " FROM gygdpos.gresa_sa_doc a " + extraJoin
                    + "WHERE a.usestatus=1 AND a.useday BETWEEN TO_DATE(?,'YYYY-MM-DD') AND TO_DATE(?,'YYYY-MM-DD') "
                    + extraWhere + HQ_EXISTS,
                    Long.class, p[0], p[1]);
                if (list != null) ids.addAll(list);
            } catch (Exception e) { log.warn("{} [{}]: {}", field, w, e.getMessage()); }
        }
        log.info("{} = {} ({} 周合并)", field, ids.size(), weeks.size());
        return ids.size();
    }

    /** B2C/O2O：逐天查有平台订单的门店，数量饱和(连续N天无新增)即停 */
    private long countB2cO2o(String start, String end) {
        Set<Long> found = new HashSet<>();
        List<LocalDate> days = dayList(start, end);
        int noNewDays = 0;
        for (int di = 0; di < days.size(); di++) {
            String day = days.get(di).format(DF);
            int before = found.size();
            try {
                List<Long> hit = jdbc.queryForList(
                    "SELECT DISTINCT a.placepointid FROM gygdpos.gresa_sa_doc a "
                    + "JOIN gygdpos.gresa_sa_orderdoc o ON a.rsaid=o.rsaid "
                    + "WHERE a.usestatus=1 AND a.useday=TO_DATE(?,'YYYY-MM-DD')",
                    Long.class, day);
                if (hit != null) found.addAll(hit);
            } catch (Exception e) { log.warn("O2O day={}: {}", day, e.getMessage()); }
            int added = found.size() - before;
            noNewDays = (added == 0) ? noNewDays + 1 : 0;
            log.info("O2O day={}: +{} new, total={}", day, added, found.size());
            if (noNewDays >= 3) { log.info("O2O 连续3天无新增,停止(已查{}天)", di+1); break; }
        }
        return found.size();
    }

    /* ========== 追溯码（按周求和） ========== */

    private long sumWeeks(String start, String end, String docType) {
        long total = 0;
        for (String w : weeks(start, end)) {
            String[] p = w.split("\\|");
            long n = q("SELECT COUNT(*) FROM ALI_HEALTH_ECODE_SYNC_D e "
                + "WHERE e.create_time>=TO_DATE(?,'YYYY-MM-DD') AND e.create_time<TO_DATE(?,'YYYY-MM-DD')+1 "
                + "AND e.doc_type=? "
                + "AND EXISTS(SELECT 1 FROM gygdpos.gpcs_placepoint p WHERE p.placepointid=e.placepointid AND p.placepointname NOT LIKE '%'||'测试'||'%')",
                p[0], p[1], docType);
            if (n > 0) total += n;
        }
        return total;
    }

    private long sumWeeksOther(String start, String end) {
        long total = 0;
        for (String w : weeks(start, end)) {
            String[] p = w.split("\\|");
            long n = q("SELECT COUNT(*) FROM ALI_HEALTH_ECODE_SYNC_D e "
                + "WHERE e.create_time>=TO_DATE(?,'YYYY-MM-DD') AND e.create_time<TO_DATE(?,'YYYY-MM-DD')+1 "
                + "AND e.doc_type NOT IN('SALE','SALE_RETURN','DIST_RECEIVE','DIST_RETURN') "
                + "AND EXISTS(SELECT 1 FROM gygdpos.gpcs_placepoint p WHERE p.placepointid=e.placepointid AND p.placepointname NOT LIKE '%'||'测试'||'%')",
                p[0], p[1]);
            if (n > 0) total += n;
        }
        return total;
    }

    private long sumWeeksAll(String start, String end) {
        long total = 0;
        for (String w : weeks(start, end)) {
            String[] p = w.split("\\|");
            long n = q("SELECT COUNT(*) FROM ALI_HEALTH_ECODE_SYNC_D e "
                + "WHERE e.create_time>=TO_DATE(?,'YYYY-MM-DD') AND e.create_time<TO_DATE(?,'YYYY-MM-DD')+1 "
                + "AND EXISTS(SELECT 1 FROM gygdpos.gpcs_placepoint p WHERE p.placepointid=e.placepointid AND p.placepointname NOT LIKE '%'||'测试'||'%')",
                p[0], p[1]);
            if (n > 0) total += n;
        }
        return total;
    }

    /* ========== 工具 ========== */

    private List<LocalDate> dayList(String start, String end) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate s = LocalDate.parse(start, DF), e = LocalDate.parse(end, DF);
        while (!s.isAfter(e)) { days.add(s); s = s.plusDays(1); }
        return days;
    }

    private List<String> weeks(String start, String end) {
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

    private long q(String sql, Object... params) {
        try {
            Long val = jdbc.queryForObject(sql, Long.class, params);
            return val != null ? val : 0L;
        } catch (Exception e) {
            log.warn("SQL 失败: {}", e.getMessage());
            return -1L;
        }
    }

    public List<Map<String, Object>> executeQuery(String sql) {
        return jdbc.queryForList(sql);
    }
}
