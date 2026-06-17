package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 把"码上放心"导出的 Excel 行同步到 gygdpos.ALI_HEALTH_SYNC_STORE_D 的诊断逻辑。
 * 输入 Excel 行（仅授权状态=是的），输出 INSERT/UPDATE/SKIP 分类 + 可执行 SQL 文本。
 */
@Service
public class MsfxSyncService {

    private static final Logger log = LoggerFactory.getLogger(MsfxSyncService.class);
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbc;

    public MsfxSyncService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 入参：Excel 行 list. 每行需 name, appkey, refEntId, entId. */
    public Map<String, Object> diagnose(List<Map<String, String>> rows) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> resp = new LinkedHashMap<>();

        if (rows == null || rows.isEmpty()) {
            resp.put("error", "rows 为空");
            return resp;
        }

        // 1. 按 appkey 推断 parent_area_code
        Map<String, ParentArea> parentAreaByAppkey = new LinkedHashMap<>();
        Set<String> appkeys = new LinkedHashSet<>();
        for (Map<String, String> r : rows) {
            String ak = trim(r.get("appkey"));
            if (!ak.isEmpty()) appkeys.add(ak);
        }
        for (String ak : appkeys) {
            parentAreaByAppkey.put(ak, resolveParentAreaForAppkey(ak, rows));
        }

        // 2. 逐行分类
        List<RowResult> results = new ArrayList<>();
        for (Map<String, String> r : rows) {
            results.add(classify(r, parentAreaByAppkey));
        }

        // 3. 生成 SQL
        String sql = renderSql(results, parentAreaByAppkey);

        // 4. 摘要
        int ins = 0, upd = 0, skip = 0, noop = 0;
        for (RowResult rr : results) {
            switch (rr.action) {
                case "INSERT" -> ins++;
                case "UPDATE" -> upd++;
                case "NOOP" -> noop++;
                default -> skip++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRows", rows.size());
        summary.put("insertCount", ins);
        summary.put("updateCount", upd);
        summary.put("noopCount", noop);
        summary.put("skipCount", skip);
        summary.put("appkeys", parentAreaByAppkey);
        resp.put("summary", summary);
        resp.put("sql", sql);
        resp.put("sqlFileName", "update_ali_health_sync_store_" + LocalDate.now().format(DF) + ".sql");
        resp.put("details", toDetailList(results));
        resp.put("costMs", System.currentTimeMillis() - t0);
        log.info("msfx-sync diagnose 完成 total={} ins={} upd={} noop={} skip={} cost={}ms",
            rows.size(), ins, upd, noop, skip, System.currentTimeMillis() - t0);
        return resp;
    }

    /* ============== 单行分类 ============== */

    private RowResult classify(Map<String, String> row, Map<String, ParentArea> parentAreaByAppkey) {
        String name = trim(row.get("name"));
        String appkey = trim(row.get("appkey"));
        String refEntId = trim(row.get("refEntId"));
        String entId = trim(row.get("entId"));

        RowResult rr = new RowResult();
        rr.name = name;
        rr.appkey = appkey;
        rr.refEntId = refEntId;
        rr.entId = entId;

        if (name.isEmpty()) {
            rr.action = "SKIP";
            rr.reason = "Excel 企业名为空";
            return rr;
        }

        // 1) 查 PUB_COMPANY，先精确，再括号互换
        PubCompany pc = findPubCompany(name);
        if (pc == null) {
            // 2) 名字找不到 → 尝试 refEntId 反查 ALI 表（改名场景）
            if (!refEntId.isEmpty()) {
                Map<String, Object> oldRec = findAliByRefEntId(refEntId);
                if (oldRec != null) {
                    rr.action = "UPDATE";
                    rr.reason = "门店改名：按 ref_ent_id 命中旧记录 " + oldRec.get("PLACEPOINTNAME");
                    rr.placepointid = ((Number) oldRec.get("PLACEPOINTID")).longValue();
                    rr.placepointname = name;
                    rr.parentAreaCode = strOf(oldRec.get("PARENT_AREA_CODE"));
                    rr.parentAreaName = strOf(oldRec.get("PARENT_AREA_NAME"));
                    rr.areaCode = strOf(oldRec.get("AREA_CODE"));
                    rr.areaName = strOf(oldRec.get("AREA_NAME"));
                    rr.existing = oldRec;
                    return rr;
                }
            }
            rr.action = "SKIP";
            rr.reason = "PUB_COMPANY 中未找到，需人工确认";
            return rr;
        }

        rr.placepointid = pc.companyId;
        rr.placepointname = pc.companyName;
        rr.areaCode = String.valueOf(pc.parentCompanyId);
        rr.areaName = pc.parentCompanyName;
        rr.selfStore = pc.selfFlag;

        // 3) 验证是门店（GPCS_PLACEPOINT 中存在）
        Placepoint pp = findPlacepoint(pc.companyId);
        if (pp == null) {
            rr.action = "SKIP";
            rr.reason = "PUB_COMPANY 命中但 GPCS_PLACEPOINT 中无 → 总公司/组织节点";
            return rr;
        }
        rr.medinsStore = pp.medAgenCode != null && !pp.medAgenCode.isBlank() ? 1 : 0;
        rr.dualChannelStore = pp.dualChannelFlag;

        // 4) parent_area
        ParentArea pa = parentAreaByAppkey.get(appkey);
        if (pa != null) {
            rr.parentAreaCode = pa.code;
            rr.parentAreaName = pa.name;
        }

        // 5) 比对 ALI 表
        Map<String, Object> existing = findAliByName(pc.companyName);
        if (existing == null && !pc.companyName.equals(name)) {
            existing = findAliByName(name);
        }
        if (existing != null) {
            rr.existing = existing;
            String dbApp = strOf(existing.get("APP_KEY"));
            String dbRef = strOf(existing.get("REF_ENT_ID"));
            String dbEnt = strOf(existing.get("ENT_ID"));
            if (dbApp.equals(appkey) && dbRef.equals(refEntId) && dbEnt.equals(entId)) {
                rr.action = "NOOP";
                rr.reason = "已存在且 (APP_KEY,REF_ENT_ID,ENT_ID) 一致";
            } else {
                rr.action = "UPDATE";
                List<String> diffs = new ArrayList<>();
                if (!dbApp.equals(appkey)) diffs.add("APP_KEY:" + dbApp + "→" + appkey);
                if (!dbRef.equals(refEntId)) diffs.add("REF_ENT_ID:" + dbRef + "→" + refEntId);
                if (!dbEnt.equals(entId)) diffs.add("ENT_ID:" + dbEnt + "→" + entId);
                rr.reason = "已存在但需补全/修正: " + String.join("; ", diffs);
            }
        } else {
            rr.action = "INSERT";
            rr.reason = "ALI 表中不存在，新增同步映射";
        }
        return rr;
    }

    /* ============== DB 查询 ============== */

    private PubCompany findPubCompany(String name) {
        PubCompany pc = queryPubCompany(name);
        if (pc != null) return pc;
        String swapped = swapBrackets(name);
        if (!swapped.equals(name)) {
            pc = queryPubCompany(swapped);
        }
        return pc;
    }

    private PubCompany queryPubCompany(String name) {
        try {
            return jdbc.queryForObject(
                "SELECT a.companyid, a.companyname, a.parentcompanyid, a.selfflag, "
                + "       (SELECT b.companyname FROM gygdpos.pub_company b WHERE b.companyid = a.parentcompanyid) AS parentname "
                + "FROM gygdpos.pub_company a WHERE a.companyname = ? AND ROWNUM = 1",
                (rs, i) -> {
                    PubCompany p = new PubCompany();
                    p.companyId = rs.getLong(1);
                    p.companyName = rs.getString(2);
                    p.parentCompanyId = rs.getLong(3);
                    p.selfFlag = rs.getObject(4) == null ? null : rs.getInt(4);
                    p.parentCompanyName = rs.getString(5);
                    return p;
                },
                name);
        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            log.warn("queryPubCompany 失败 name={} err={}", name, e.getMessage());
            return null;
        }
    }

    private Placepoint findPlacepoint(long companyId) {
        try {
            return jdbc.queryForObject(
                "SELECT placepointid, medagencode, dual_channel_flag "
                + "FROM gygdpos.gpcs_placepoint WHERE placepointid = ? AND ROWNUM = 1",
                (rs, i) -> {
                    Placepoint p = new Placepoint();
                    p.placepointId = rs.getLong(1);
                    p.medAgenCode = rs.getString(2);
                    p.dualChannelFlag = rs.getObject(3) == null ? null : rs.getInt(3);
                    return p;
                },
                companyId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            log.warn("findPlacepoint 失败 id={} err={}", companyId, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> findAliByName(String name) {
        try {
            return jdbc.queryForMap(
                "SELECT * FROM gygdpos.ali_health_sync_store_d WHERE placepointname = ? AND ROWNUM = 1",
                name);
        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            log.warn("findAliByName 失败 name={} err={}", name, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> findAliByRefEntId(String refEntId) {
        try {
            return jdbc.queryForMap(
                "SELECT * FROM gygdpos.ali_health_sync_store_d WHERE ref_ent_id = ? AND ROWNUM = 1",
                refEntId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /* ============== parent_area 解析 ============== */

    private ParentArea resolveParentAreaForAppkey(String appkey, List<Map<String, String>> rows) {
        // 1) 取 ALI 表中该 appkey 的最常见 parent_area
        try {
            List<Map<String, Object>> hits = jdbc.queryForList(
                "SELECT parent_area_code, parent_area_name, COUNT(*) AS c "
                + "FROM gygdpos.ali_health_sync_store_d WHERE app_key = ? "
                + "GROUP BY parent_area_code, parent_area_name ORDER BY COUNT(*) DESC",
                appkey);
            if (!hits.isEmpty()) {
                Map<String, Object> top = hits.get(0);
                ParentArea pa = new ParentArea();
                pa.code = strOf(top.get("PARENT_AREA_CODE"));
                pa.name = strOf(top.get("PARENT_AREA_NAME"));
                pa.source = "DB-most-common(" + top.get("C") + ")";
                return pa;
            }
        } catch (Exception e) {
            log.warn("查 appkey 最常见 parent_area 失败 appkey={}", appkey);
        }
        // 2) 该 appkey 在 DB 完全没有：递归向上找
        for (Map<String, String> r : rows) {
            if (!appkey.equals(trim(r.get("appkey")))) continue;
            PubCompany pc = findPubCompany(trim(r.get("name")));
            if (pc == null) continue;
            ParentArea pa = recurseToTopArea(pc.parentCompanyId);
            if (pa != null) {
                pa.source = "recurse-from-" + pc.companyId;
                return pa;
            }
        }
        ParentArea pa = new ParentArea();
        pa.source = "未推断到，请人工指定";
        return pa;
    }

    private ParentArea recurseToTopArea(long companyId) {
        Long current = companyId;
        Long previous = null;
        String previousName = null;
        int hops = 0;
        while (current != null && current > 0 && hops < 10) {
            try {
                Map<String, Object> row = jdbc.queryForMap(
                    "SELECT companyid, companyname, parentcompanyid FROM gygdpos.pub_company WHERE companyid = ?",
                    current);
                Long parent = row.get("PARENTCOMPANYID") == null
                    ? null : ((Number) row.get("PARENTCOMPANYID")).longValue();
                if (parent == null || parent == 0) {
                    ParentArea pa = new ParentArea();
                    pa.code = String.valueOf(row.get("COMPANYID"));
                    pa.name = strOf(row.get("COMPANYNAME"));
                    return pa;
                }
                previous = ((Number) row.get("COMPANYID")).longValue();
                previousName = strOf(row.get("COMPANYNAME"));
                current = parent;
                hops++;
            } catch (Exception e) {
                break;
            }
        }
        if (previous != null) {
            ParentArea pa = new ParentArea();
            pa.code = String.valueOf(previous);
            pa.name = previousName;
            return pa;
        }
        return null;
    }

    /* ============== SQL 渲染 ============== */

    private String renderSql(List<RowResult> results, Map<String, ParentArea> parentAreaByAppkey) {
        StringBuilder sb = new StringBuilder();
        String today = LocalDate.now().toString();
        sb.append("-- 码上放心同步表 (gygdpos.ALI_HEALTH_SYNC_STORE_D) 增量更新\n");
        sb.append("-- 生成时间: ").append(today).append("\n");
        sb.append("-- ⚠️ 请在 SQL Developer 事务环境中执行，校验无误后再 COMMIT\n");
        sb.append("--\n-- 各 appkey 推断的 PARENT_AREA:\n");
        for (Map.Entry<String, ParentArea> e : parentAreaByAppkey.entrySet()) {
            ParentArea pa = e.getValue();
            sb.append("--   appkey=").append(e.getKey())
              .append(" → ").append(pa.code).append(" / ").append(pa.name)
              .append("  [").append(pa.source).append("]\n");
        }
        sb.append("\nSET DEFINE OFF;\n\n");

        // UPDATE 段
        List<RowResult> updates = filter(results, "UPDATE");
        if (!updates.isEmpty()) {
            sb.append("-- ========== UPDATE: ").append(updates.size()).append(" 条 ==========\n");
            for (RowResult rr : updates) {
                sb.append("-- ").append(rr.name).append("  reason: ").append(rr.reason).append("\n");
                sb.append("UPDATE gygdpos.ali_health_sync_store_d SET ")
                  .append("app_key = ").append(sqlStr(rr.appkey)).append(", ")
                  .append("ref_ent_id = ").append(sqlStr(rr.refEntId)).append(", ")
                  .append("ent_id = ").append(sqlStr(rr.entId)).append(", ")
                  .append("placepointname = ").append(sqlStr(rr.placepointname)).append(" ")
                  .append("WHERE placepointid = ").append(rr.placepointid).append(";\n\n");
            }
        }

        // INSERT 段
        List<RowResult> inserts = filter(results, "INSERT");
        if (!inserts.isEmpty()) {
            sb.append("-- ========== INSERT: ").append(inserts.size()).append(" 条 ==========\n");
            for (RowResult rr : inserts) {
                sb.append("-- ").append(rr.name).append("  pid=").append(rr.placepointid).append("\n");
                sb.append("INSERT INTO gygdpos.ali_health_sync_store_d (")
                  .append("placepointid, placepointname, area_code, area_name, parent_area_code, parent_area_name, ")
                  .append("self_store, medins_store, dual_channel_store, network_type, usestatus, ")
                  .append("app_key, ref_ent_id, ent_id, credate")
                  .append(") VALUES (")
                  .append(rr.placepointid).append(", ")
                  .append(sqlStr(rr.placepointname)).append(", ")
                  .append(sqlStr(rr.areaCode)).append(", ")
                  .append(sqlStr(rr.areaName)).append(", ")
                  .append(sqlStr(rr.parentAreaCode)).append(", ")
                  .append(sqlStr(rr.parentAreaName)).append(", ")
                  .append(rr.selfStore == null ? "NULL" : rr.selfStore).append(", ")
                  .append(rr.medinsStore).append(", ")
                  .append(rr.dualChannelStore == null ? "NULL" : rr.dualChannelStore).append(", ")
                  .append("200, 1, ")
                  .append(sqlStr(rr.appkey)).append(", ")
                  .append(sqlStr(rr.refEntId)).append(", ")
                  .append(sqlStr(rr.entId)).append(", ")
                  .append("SYSDATE);\n\n");
            }
        }

        // 已一致段
        List<RowResult> noops = filter(results, "NOOP");
        if (!noops.isEmpty()) {
            sb.append("-- ========== 已一致(NOOP): ").append(noops.size()).append(" 条 ==========\n");
            for (RowResult rr : noops) {
                sb.append("-- ").append(rr.name).append("  ").append(rr.reason).append("\n");
            }
            sb.append("\n");
        }

        // SKIP 段
        List<RowResult> skips = filter(results, "SKIP");
        if (!skips.isEmpty()) {
            sb.append("-- ========== SKIP (人工核对): ").append(skips.size()).append(" 条 ==========\n");
            for (RowResult rr : skips) {
                sb.append("-- ").append(rr.name).append("  → ").append(rr.reason).append("\n");
            }
            sb.append("\n");
        }

        sb.append("-- COMMIT 由人工执行\n-- COMMIT;\n");
        return sb.toString();
    }

    private List<RowResult> filter(List<RowResult> rs, String action) {
        List<RowResult> out = new ArrayList<>();
        for (RowResult r : rs) if (action.equals(r.action)) out.add(r);
        return out;
    }

    private List<Map<String, Object>> toDetailList(List<RowResult> rs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RowResult r : rs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.name);
            m.put("appkey", r.appkey);
            m.put("action", r.action);
            m.put("reason", r.reason);
            m.put("placepointid", r.placepointid);
            out.add(m);
        }
        return out;
    }

    /* ============== 工具 ============== */

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static String strOf(Object o) { return o == null ? "" : o.toString(); }

    private static String sqlStr(String s) {
        if (s == null) return "NULL";
        return "'" + s.replace("'", "''") + "'";
    }

    private static String swapBrackets(String s) {
        return s.replace('(', '（').replace(')', '）').equals(s)
            ? s.replace('（', '(').replace('）', ')')
            : s.replace('(', '（').replace(')', '）');
    }

    /* ============== POJO ============== */

    private static class PubCompany {
        long companyId;
        String companyName;
        long parentCompanyId;
        String parentCompanyName;
        Integer selfFlag;
    }

    private static class Placepoint {
        long placepointId;
        String medAgenCode;
        Integer dualChannelFlag;
    }

    private static class ParentArea {
        public String code = "";
        public String name = "";
        public String source = "";
    }

    private static class RowResult {
        String name;
        String appkey;
        String refEntId;
        String entId;
        String action;          // INSERT / UPDATE / NOOP / SKIP
        String reason;
        Long placepointid;
        String placepointname;
        String areaCode;
        String areaName;
        String parentAreaCode;
        String parentAreaName;
        Integer selfStore;
        Integer medinsStore;
        Integer dualChannelStore;
        Map<String, Object> existing;
    }
}
