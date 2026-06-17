package org.example.controller;

import org.example.service.MsfxSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/msfx-sync")
public class MsfxSyncController {

    private static final Logger log = LoggerFactory.getLogger(MsfxSyncController.class);
    private final MsfxSyncService service;

    public MsfxSyncController(MsfxSyncService service) { this.service = service; }

    /**
     * 入参示例: { "rows": [ {"name":"...","appkey":"...","refEntId":"...","entId":"..."}, ... ] }
     */
    @PostMapping("/diagnose")
    public Map<String, Object> diagnose(@RequestBody Map<String, Object> body) {
        long t0 = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> rows = (List<Map<String, String>>) body.get("rows");
        log.info("==> POST /api/msfx-sync/diagnose rows={}", rows == null ? 0 : rows.size());
        Map<String, Object> result = service.diagnose(rows);
        log.info("<== POST /api/msfx-sync/diagnose cost={}ms summary={}",
            System.currentTimeMillis() - t0, result.get("summary"));
        return result;
    }

    @PostMapping("/retail-diagnosis-sql")
    public Map<String, Object> retailDiagnosisSql(@RequestBody Map<String, Object> body) {
        String parentAreaCode = numericOrDefault(body.get("parentAreaCode"), "161");
        String placepointId = numericOrDefault(body.get("placepointId"), "106016");
        String rsaid = numericOrBlank(body.get("rsaid"));
        String businessDate = dateOrBlank(body.get("businessDate"));
        List<String> screenshotDetailIds = numericList(body.get("screenshotDetailIds"));
        List<String> realDetailIds = numericList(body.get("realDetailIds"));
        List<String> traceCodes = numericList(body.get("traceCodes"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("parentAreaCode", parentAreaCode);
        result.put("placepointId", placepointId);
        result.put("businessDate", businessDate);
        result.put("screenshotDetailIds", screenshotDetailIds);
        result.put("realDetailIds", realDetailIds);
        result.put("traceCodes", traceCodes);

        Map<String, String> sql = new LinkedHashMap<>();
        sql.put("01_group_buy_mapping", groupBuyMappingSql(screenshotDetailIds, realDetailIds));
        sql.put("02_real_sale_detail", realSaleDetailSql(realDetailIds, rsaid));
        sql.put("03_bms_ecode_by_screenshot_detail", bmsEcodeByScreenshotDetailSql(screenshotDetailIds, traceCodes));
        sql.put("04_trace_join_check", traceJoinCheckSql(realDetailIds));
        sql.put("05_date_window_check", dateWindowCheckSql(placepointId, realDetailIds, businessDate));
        sql.put("06_full_candidate_check", fullCandidateCheckSql(parentAreaCode, placepointId, realDetailIds, businessDate));
        sql.put("07_success_log_check", successLogCheckSql(placepointId, realDetailIds, rsaid, traceCodes));
        sql.put("08_request_log_check", requestLogCheckSql(rsaid, realDetailIds));
        sql.put("09_abnormal_check", abnormalCheckSql(placepointId, realDetailIds, rsaid, traceCodes));
        result.put("sql", sql);
        return result;
    }

    private static String groupBuyMappingSql(List<String> screenshotDetailIds, List<String> realDetailIds) {
        return "SELECT groupbuydtlid AS screenshot_rsadtlid, rsadtlid AS real_rsadtlid\n"
            + "  FROM gygdpos.ZX_GROUP_BUY_DTL\n"
            + " WHERE groupbuydtlid IN (" + csvOrNull(screenshotDetailIds) + ")\n"
            + "    OR rsadtlid IN (" + csvOrNull(realDetailIds) + ")\n"
            + " ORDER BY groupbuydtlid, rsadtlid";
    }

    private static String realSaleDetailSql(List<String> realDetailIds, String rsaid) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT gsl.rsaid, gsl.rsadtlid, gso.placepointid,\n")
            .append("       TO_CHAR(gso.credate, 'yyyy-mm-dd hh24:mi:ss') AS gresa_credate,\n")
            .append("       TO_CHAR(gso.useday, 'yyyy-mm-dd') AS useday,\n")
            .append("       gsl.goodsid, g.goodsname, TO_NUMBER(gsl.goodsqty) AS goodscnt,\n")
            .append("       gsl.batchid, gsl.lotid, gso.usestatus AS doc_status, gsl.usestatus AS dtl_status\n")
            .append("  FROM gygdpos.gresa_sa_dtl gsl\n")
            .append("  JOIN gygdpos.gresa_sa_doc gso ON gsl.rsaid = gso.rsaid\n")
            .append("  JOIN gygdpos.pub_goods g ON gsl.goodsid = g.goodsid\n")
            .append(" WHERE gsl.rsadtlid IN (").append(csvOrNull(realDetailIds)).append(")");
        if (!rsaid.isEmpty()) {
            sql.append("\n    OR gsl.rsaid = ").append(rsaid);
        }
        sql.append("\n ORDER BY gsl.rsadtlid");
        return sql.toString();
    }

    private static String bmsEcodeByScreenshotDetailSql(List<String> screenshotDetailIds, List<String> traceCodes) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ecode, placepointid, comefrom, sourceid AS screenshot_rsadtlid, goodsid, lotid, inputmanid,\n")
            .append("       TO_CHAR(credate, 'yyyy-mm-dd hh24:mi:ss') AS bms_credate\n")
            .append("  FROM gygdpos.BMS_ECODE_RECORD\n")
            .append(" WHERE sourceid IN (").append(csvOrNull(screenshotDetailIds)).append(")");
        if (!traceCodes.isEmpty()) {
            sql.append("\n    OR ecode IN (").append(quotedCsv(traceCodes)).append(")");
        }
        sql.append("\n ORDER BY sourceid, ecode");
        return sql.toString();
    }

    private static String traceJoinCheckSql(List<String> realDetailIds) {
        return "SELECT gsl.rsadtlid AS real_rsadtlid, d.groupbuydtlid AS screenshot_rsadtlid,\n"
            + "       er.ecode, er.placepointid, er.goodsid, er.sourceid AS bms_sourceid,\n"
            + "       TO_CHAR(er.credate, 'yyyy-mm-dd hh24:mi:ss') AS bms_credate\n"
            + "  FROM gygdpos.gresa_sa_dtl gsl\n"
            + "  JOIN gygdpos.gresa_sa_doc gso ON gsl.rsaid = gso.rsaid\n"
            + "  LEFT JOIN gygdpos.ZX_GROUP_BUY_DTL d ON d.rsadtlid = gsl.rsadtlid\n"
            + "  JOIN gygdpos.BMS_ECODE_RECORD er\n"
            + "    ON er.placepointid = gso.placepointid\n"
            + "   AND er.goodsid = gsl.goodsid\n"
            + "   AND (er.sourceid = gsl.rsadtlid OR er.sourceid = d.groupbuydtlid)\n"
            + " WHERE gsl.rsadtlid IN (" + csvOrNull(realDetailIds) + ")\n"
            + " ORDER BY gsl.rsadtlid, er.ecode";
    }

    private static String dateWindowCheckSql(String placepointId, List<String> realDetailIds, String businessDate) {
        String date = businessDate.isEmpty() ? "<yyyy-mm-dd>" : businessDate;
        return "SELECT COUNT(*) AS cnt\n"
            + "  FROM gygdpos.gresa_sa_dtl gsl\n"
            + "  JOIN gygdpos.gresa_sa_doc gso ON gsl.rsaid = gso.rsaid\n"
            + " WHERE gso.placepointid = " + placepointId + "\n"
            + "   AND gsl.rsadtlid IN (" + csvOrNull(realDetailIds) + ")\n"
            + "   AND gso.credate >= TO_DATE('" + date + "', 'YYYY-MM-DD')\n"
            + "   AND gso.credate <  TO_DATE('" + date + "', 'YYYY-MM-DD') + 1";
    }

    private static String fullCandidateCheckSql(String parentAreaCode, String placepointId, List<String> realDetailIds, String businessDate) {
        String date = businessDate.isEmpty() ? "<yyyy-mm-dd>" : businessDate;
        return "SELECT gsl.rsadtlid, gsl.rsaid, gsl.goodsid, g.goodsname, TO_NUMBER(gsl.goodsqty) AS goodscnt,\n"
            + "       TO_CHAR(gso.credate, 'yyyy-mm-dd hh24:mi:ss') AS setl_time,\n"
            + "       syns.area_code, syns.parent_area_code, NVL(t1.strongcontrol, 0) AS strongcontrol,\n"
            + "       CASE WHEN t2.ecodetype IN (2,3,4,5,6) THEN 1 ELSE 0 END AS spececode,\n"
            + "       (SELECT COUNT(*) FROM gygdpos.BMS_ECODE_RECORD er\n"
            + "         WHERE er.placepointid = gso.placepointid AND er.goodsid = gsl.goodsid\n"
            + "           AND (er.sourceid = gsl.rsadtlid OR EXISTS (SELECT 1 FROM gygdpos.ZX_GROUP_BUY_DTL d WHERE d.rsadtlid = gsl.rsadtlid AND d.groupbuydtlid = er.sourceid))) AS trace_count\n"
            + "  FROM gygdpos.gresa_sa_dtl gsl\n"
            + "  JOIN gygdpos.gresa_sa_doc gso ON gsl.rsaid = gso.rsaid\n"
            + "  JOIN gygdpos.pub_goods g ON gsl.goodsid = g.goodsid AND g.usestatus = 1\n"
            + "  JOIN gygdpos.pub_goods_area ga ON gsl.goodsid = ga.goodsid AND ga.isecode = 1\n"
            + "  JOIN gygdpos.ali_health_sync_store_d syns ON syns.placepointid = gso.placepointid AND syns.usestatus = 1\n"
            + "  JOIN gygdpos.gpcs_placepoint gp ON gso.placepointid = gp.placepointid\n"
            + "  LEFT JOIN (\n"
            + "       SELECT a.area, a.goodsid, a.contype, b.strongcontrol, TO_NUMBER(" + parentAreaCode + ") AS placepointid\n"
            + "         FROM gygdpos.ZX_PUB_GOODS_AREA_DTL a, gygdpos.ZX_AREA_GOODS_QUALITY b\n"
            + "        WHERE a.dtlid = b.dtlid AND NVL(a.usestatus, 0) = 1 AND a.qualityid = 9 AND a.area = " + parentAreaCode + "\n"
            + "       UNION\n"
            + "       SELECT a.area, a.goodsid, a.contype, c.strongcontrol, c.placepointid\n"
            + "         FROM gygdpos.ZX_PUB_GOODS_AREA_DTL a, gygdpos.ZX_POINT_GOODS_QUALITY c\n"
            + "        WHERE a.dtlid = c.dtlid AND NVL(a.usestatus, 0) = 1 AND a.qualityid = 9 AND a.area = " + parentAreaCode + "\n"
            + "  ) t1 ON gsl.goodsid = t1.goodsid AND t1.placepointid IN (gso.placepointid, " + parentAreaCode + ")\n"
            + "  LEFT JOIN gygdpos.special_ecode_goods t2 ON gsl.goodsid = t2.goodsid\n"
            + " WHERE gso.usestatus = 1 AND gsl.usestatus = 1\n"
            + "   AND gso.placepointid = " + placepointId + "\n"
            + "   AND gsl.rsadtlid IN (" + csvOrNull(realDetailIds) + ")\n"
            + "   AND gso.credate >= TO_DATE('" + date + "', 'YYYY-MM-DD')\n"
            + "   AND gso.credate <  TO_DATE('" + date + "', 'YYYY-MM-DD') + 1\n"
            + "   AND ga.area = syns.parent_area_code\n"
            + "   AND NVL(t1.strongcontrol, 0) = 1\n"
            + "   AND CASE WHEN t2.ecodetype IN (2,3,4,5,6) THEN 1 ELSE 0 END = 0\n"
            + "   AND NOT EXISTS (SELECT 1 FROM MSFX.ALI_HEALTH_ECODE_SYNC_D l WHERE l.area_code = syns.area_code AND l.placepointid = gso.placepointid AND l.rsaid = gsl.rsaid AND l.rsadtlid = gsl.rsadtlid AND l.goods_id = gsl.goodsid)\n"
            + "   AND NOT EXISTS (SELECT 1 FROM MSFX.ALI_HEALTH_ALREADY_SALE_ECODE m WHERE m.placepointid = gso.placepointid AND m.rsadtlid = gsl.rsadtlid AND m.goods_id = gsl.goodsid)\n"
            + "   AND NOT EXISTS (SELECT 1 FROM MSFX.ALI_HEALTH_ABNORMAL_ECODE q WHERE q.placepointid = gso.placepointid AND q.rsadtlid = gsl.rsadtlid AND q.goods_id = gsl.goodsid)\n"
            + "   AND NOT EXISTS (SELECT 1 FROM MSFX.ALI_SYNC_ECODE_ABNORMAL_DATA n WHERE n.area_code = syns.area_code AND n.placepointid = gso.placepointid AND n.rsaid = gsl.rsaid AND n.rsadtlid = gsl.rsadtlid AND n.goods_id = gsl.goodsid)\n"
            + "   AND EXISTS (SELECT 1 FROM gygdpos.BMS_ECODE_RECORD er WHERE er.placepointid = gso.placepointid AND er.goodsid = gsl.goodsid AND (er.sourceid = gsl.rsadtlid OR EXISTS (SELECT 1 FROM gygdpos.ZX_GROUP_BUY_DTL d WHERE d.rsadtlid = gsl.rsadtlid AND d.groupbuydtlid = er.sourceid)))\n"
            + " ORDER BY gsl.rsadtlid";
    }

    private static String successLogCheckSql(String placepointId, List<String> realDetailIds, String rsaid, List<String> traceCodes) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT area_code, placepointid, placepointname, comefrom, rsaid, rsadtlid, goods_id, trace_code, setl_time, create_time, request_log_id\n")
            .append("  FROM MSFX.ALI_HEALTH_ECODE_SYNC_D\n")
            .append(" WHERE placepointid = ").append(placepointId)
            .append("\n   AND (rsadtlid IN (").append(csvOrNull(realDetailIds)).append(")");
        if (!rsaid.isEmpty()) {
            sql.append(" OR rsaid = ").append(rsaid);
        }
        if (!traceCodes.isEmpty()) {
            sql.append(" OR trace_code IN (").append(quotedCsv(traceCodes)).append(")");
        }
        sql.append(")\n ORDER BY rsadtlid, trace_code");
        return sql.toString();
    }

    private static String requestLogCheckSql(String rsaid, List<String> realDetailIds) {
        List<String> requestLogIds = new ArrayList<>();
        if (!rsaid.isEmpty()) {
            for (String id : realDetailIds) {
                requestLogIds.add("GDYFSA_" + rsaid + id);
            }
        }
        return "SELECT request_log_id, placepointid, refentid, request_id, response_success, msg_info\n"
            + "  FROM MSFX.ALI_HEALTH_SYNC_REQUSET_LOG\n"
            + " WHERE request_log_id IN (" + quotedCsv(requestLogIds) + ")\n"
            + " ORDER BY request_log_id";
    }

    private static String abnormalCheckSql(String placepointId, List<String> realDetailIds, String rsaid, List<String> traceCodes) {
        String detailCsv = csvOrNull(realDetailIds);
        String traceCsv = quotedCsv(traceCodes);
        String rsaidPredicate = rsaid.isEmpty() ? "1 = 0" : "rsaid = " + rsaid;
        return "SELECT area_code, placepointid, comefrom, rsaid, rsadtlid, goods_id, ext_msg_reason, 'PRECHECK' AS source_table\n"
            + "  FROM MSFX.ALI_SYNC_ECODE_ABNORMAL_DATA\n"
            + " WHERE placepointid = " + placepointId + " AND (rsadtlid IN (" + detailCsv + ") OR " + rsaidPredicate + ")\n"
            + "UNION ALL\n"
            + "SELECT NULL AS area_code, placepointid, NULL AS comefrom, NULL AS rsaid, rsadtlid, goods_id, abnormal_desc AS ext_msg_reason, 'ABNORMAL_ECODE' AS source_table\n"
            + "  FROM MSFX.ALI_HEALTH_ABNORMAL_ECODE\n"
            + " WHERE placepointid = " + placepointId + " AND (rsadtlid IN (" + detailCsv + ") OR tracecode IN (" + traceCsv + "))\n"
            + "UNION ALL\n"
            + "SELECT NULL AS area_code, placepointid, NULL AS comefrom, NULL AS rsaid, rsadtlid, goods_id, '已售出码' AS ext_msg_reason, 'ALREADY_SALE' AS source_table\n"
            + "  FROM MSFX.ALI_HEALTH_ALREADY_SALE_ECODE\n"
            + " WHERE placepointid = " + placepointId + " AND (rsadtlid IN (" + detailCsv + ") OR tracecode IN (" + traceCsv + "))";
    }

    private static String numericOrDefault(Object value, String defaultValue) {
        String s = value == null ? "" : value.toString().trim();
        return s.matches("\\d+") ? s : defaultValue;
    }

    private static String numericOrBlank(Object value) {
        String s = value == null ? "" : value.toString().trim();
        return s.matches("\\d+") ? s : "";
    }

    private static String dateOrBlank(Object value) {
        String s = value == null ? "" : value.toString().trim();
        return s.matches("\\d{4}-\\d{2}-\\d{2}") ? s : "";
    }

    private static List<String> numericList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                String s = numericOrBlank(item);
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        } else {
            String s = numericOrBlank(value);
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static String csvOrNull(List<String> values) {
        return values.isEmpty() ? "NULL" : String.join(",", values);
    }

    private static String quotedCsv(List<String> values) {
        if (values.isEmpty()) {
            return "NULL";
        }
        List<String> quoted = new ArrayList<>();
        for (String value : values) {
            quoted.add("'" + value + "'");
        }
        return String.join(",", quoted);
    }
}
